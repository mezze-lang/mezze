package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `IntoResponse` and the per-method route verbs, plus the Response builders.
 *
 * A handler's job is to answer, and most answers are a string or some JSON.
 * `on_get`/`on_post`/… accept anything implementing `IntoResponse`, so a
 * handler no longer has to spell out a five-field Response record. `route`
 * itself is unchanged and still takes a Response-returning handler.
 *
 * A handler returning a BARE record literal works too, and took a fix of its
 * own: see [a bare response literal is recognised as a Response].
 */
class HttpIntoResponseTest {

    private val head = """
        use std::effects::http::{ Request, Response, Get, Post, Put, Delete, created, no_content, not_found, bad_request, server_error, redirect, redirect_with, response_ok_text, response_json, response_stream }
        use std::effects::http::{ HttpError, Timeout, ConnectionFailed, TlsError, RequestFailed, DecodeError, InvalidUrl, EncodeError, Cancelled, IntoResponse, IntoErrorResponse, or_throw }
        use std::effects::exception::{ ExceptionToResult, unwrap, throw }
        use std::effects::http::{ HttpError, Timeout, ConnectionFailed, TlsError, RequestFailed, DecodeError, InvalidUrl, EncodeError, Cancelled, IntoResponse, IntoErrorResponse }
        use std::effects::exception::{ ExceptionToResult, unwrap }
        use std::effects::http_server::{ get_request }
        use std::effects::http_router::{ on_get, on_post, on_put, on_delete, group, catching, Matched, Routes, routes, otherwise, or_else }
        use std::codec::json::{ JString }
        use std::collections::map
        use std::collections::bytes
        use std::collections::seq
        use std::collections::iter
        use std::str
        use std::int

        let fallback: { req: Request } -> Response = { req } -> not_found { body = "nope" }
        let body_of: { resp: Response } -> Str = { resp } -> match bytes::to_str { b = resp.body } is
          Ok { val } -> val
          Err { error = _ } -> "err"
        let header_of: { resp: Response, name: Str } -> Str = { resp, name } ->
          match resp.header { name = name } is
            Some { val } -> val
            None -> "MISSING"
    """.trimIndent()

    private fun run(body: String): Any = MezzeTestHelper.run("$head\n$body")

    // ---- IntoResponse ----------------------------------------------------

    @Test
    fun `a handler may answer with a plain string`() {
        val r = run(
            """
            let app = on_get { path = "/ping", handler = { m } -> "pong" }
              .otherwise { fallback = fallback }
            body_of { resp = app { req = get_request { url = "/ping" } } }
            """.trimIndent()
        )
        assertEquals("pong", r)
    }

    @Test
    fun `a string answer is a 200`() {
        val r = run(
            """
            let app = on_get { path = "/ping", handler = { m } -> "pong" }
              .otherwise { fallback = fallback }
            app { req = get_request { url = "/ping" } }.status
            """.trimIndent()
        )
        assertEquals(200L, r)
    }

    @Test
    fun `a handler may answer with Json, and the body is encoded`() {
        val r = run(
            """
            let app = on_get { path = "/j", handler = { m } -> JString { val = "jay" } }
              .otherwise { fallback = fallback }
            body_of { resp = app { req = get_request { url = "/j" } } }
            """.trimIndent()
        )
        assertEquals("\"jay\"", r, "a JString encodes with its quotes")
    }

    @Test
    fun `a Json answer carries the json content type`() {
        val r = run(
            """
            let app = on_get { path = "/j", handler = { m } -> JString { val = "jay" } }
              .otherwise { fallback = fallback }
            header_of { resp = app { req = get_request { url = "/j" } }, name = "content-type" }
            """.trimIndent()
        )
        assertEquals("application/json", r)
    }

    /** The identity impl: a handler that builds its own Response still works. */
    @Test
    fun `a handler may answer with a Response it built itself`() {
        val r = run(
            """
            let app = on_post { path = "/users", handler = { m } -> created { body = "made" } }
              .otherwise { fallback = fallback }
            app { req = { method = Post, url = "/users", headers = map::empty {}, body = None,
                          timeout_ms = None, chunks = None } }.status
            """.trimIndent()
        )
        assertEquals(201L, r)
    }

    @Test
    fun `a path parameter reaches a string-answering handler`() {
        val r = run(
            """
            let app = on_get {
              path = "/users/:id",
              handler = { m } -> m.param_or { name = "id", default = "?" },
            }.otherwise { fallback = fallback }
            body_of { resp = app { req = get_request { url = "/users/42" } } }
            """.trimIndent()
        )
        assertEquals("42", r)
    }

    @Test
    fun `the verbs dispatch on method, not only on path`() {
        val r = run(
            """
            let app = on_get { path = "/thing", handler = { m } -> "got" }
              .or_else { b = on_put { path = "/thing", handler = { m } -> "put" } }
              .or_else { b = on_delete { path = "/thing", handler = { m } -> "gone" } }
              .otherwise { fallback = fallback }
            body_of { resp = app { req = { method = Delete, url = "/thing",
              headers = map::empty {}, body = None, timeout_ms = None, chunks = None } } }
            """.trimIndent()
        )
        assertEquals("gone", r)
    }

    @Test
    fun `a method with no route falls through to the fallback`() {
        val r = run(
            """
            let app = on_get { path = "/thing", handler = { m } -> "got" }
              .otherwise { fallback = fallback }
            app { req = { method = Post, url = "/thing", headers = map::empty {}, body = None,
                          timeout_ms = None, chunks = None } }.status
            """.trimIndent()
        )
        assertEquals(404L, r)
    }

    /**
     * A handler returning a BARE record literal, with nothing anywhere saying
     * it is a Response.
     *
     * This answered WRONGLY for a long time — it selected `impl IntoResponse
     * for Str` and handed it the record, dying in a native with `Expected
     * String for field 's'`. The literal leaves `chunks` undetermined
     * (`Option 'a` against `Option (Iter Bytes)`), so alias adoption — which
     * compares field types exactly — cannot label it, the constraint's type
     * variable is solved to an ANONYMOUS record, the nominal `impl … for
     * Response` target does not match it, and resolution fell through to a
     * FIELD's impl: `IntoResponse for Str`, off `url: Str`.
     *
     * Fixed on the argument side the way the receiver side already was: a
     * literal passed for a constrained type variable takes its expectation from
     * the ability's own impl target, matching on field NAMES, since the field
     * types are exactly what is still open. Unification then pins `chunks` and
     * adoption labels the record by itself.
     *
     * A record matching NO alias is untouched and still fails — `{ name, age }`
     * has no `IntoResponse` impl and says so.
     */
    @Test
    fun `a bare response literal is recognised as a Response`() {
        val r = run(
            """
            let app = on_get {
              path = "/lit",
              handler = { m } -> {
                status = 203, headers = map::empty {},
                body = bytes::from_str { s = "bare" }, url = "", chunks = None,
              },
            }.otherwise { fallback = fallback }
            let resp = app { req = get_request { url = "/lit" } }
            str::concat { l = int::to_str { n = resp.status }, r = body_of { resp = resp } }
            """.trimIndent()
        )
        assertEquals("203bare", r)
    }

    /**
     * The other half of the same rule: a record matching NO alias is left alone.
     *
     * `{ name, age }` is not a Response and has no `IntoResponse` impl, so it
     * must be rejected — the argument-side promotion only fires when exactly
     * one in-scope impl of THIS ability targets a declared record with these
     * field names. Making a bare Response work must not make everything work.
     *
     * Turning a record into JSON and JSON into a response is a conversion the
     * user asks for, not one the compiler guesses.
     */
    @Test
    fun `a record matching no alias is still rejected`() {
        val outcome = runCatching {
            run(
                """
                let app = on_get {
                  path = "/who",
                  handler = { m } -> { name = "Shriyans", age = 31 },
                }.otherwise { fallback = fallback }
                app { req = get_request { url = "/who" } }.status
                """.trimIndent()
            )
        }
        val error = outcome.exceptionOrNull()
        assertTrue(
            error != null,
            "an arbitrary record is not a Response and must be rejected; got ${outcome.getOrNull()}",
        )
    }

    /** The control, and the documented workaround: annotate the literal. */
    @Test
    fun `an annotated response literal answers correctly`() {
        val r = run(
            """
            let app = on_get {
              path = "/lit",
              handler = { m } -> do
                let lit: Response = {
                  status = 203, headers = map::empty {},
                  body = bytes::from_str { s = "bare" }, url = "", chunks = None,
                }
                lit,
            }.otherwise { fallback = fallback }
            app { req = get_request { url = "/lit" } }.status
            """.trimIndent()
        )
        assertEquals(203L, r)
    }

    // ---- failure: Result and IntoErrorResponse --------------------------

    @Test
    fun `an Ok answers through IntoResponse`() {
        val r = run(
            """
            let good: Result Str Str = Ok { val = "fine" }
            good.into_response {}.status
            """.trimIndent()
        )
        assertEquals(200L, r)
    }

    /**
     * The reason `IntoErrorResponse` is a SECOND ability: `Str` means a 200
     * with that text when a handler succeeds with it, and a 500 when it fails
     * with it. One ability cannot hold both meanings.
     */
    @Test
    fun `the same Str is a 200 as a value and a 500 as an error`() {
        val ok = run(
            """
            let good: Result Str Str = Ok { val = "fine" }
            good.into_response {}.status
            """.trimIndent()
        )
        val err = run(
            """
            let bad: Result Str Str = Err { error = "broke" }
            bad.into_response {}.status
            """.trimIndent()
        )
        assertEquals(200L, ok)
        assertEquals(500L, err)
    }

    @Test
    fun `an Err carries its message as the body`() {
        val r = run(
            """
            let bad: Result Str Str = Err { error = "broke" }
            body_of { resp = bad.into_response {} }
            """.trimIndent()
        )
        assertEquals("broke", r)
    }

    @Test
    fun `an HttpError picks a gateway status, not the upstream's`() {
        val r = run(
            """
            let statuses = [
              Timeout { url = "http://u/x", ms = 50 }.into_error_response {}.status,
              ConnectionFailed { url = "http://u/x", reason = "refused" }.into_error_response {}.status,
              TlsError { url = "http://u/x", reason = "bad cert" }.into_error_response {}.status,
              RequestFailed { url = "http://u/x", status = 404, body = "nope" }.into_error_response {}.status,
              DecodeError { url = "http://u/x", message = "bad json" }.into_error_response {}.status,
              InvalidUrl { url = "::", reason = "malformed" }.into_error_response {}.status,
              EncodeError { url = "http://u/x", message = "bad body" }.into_error_response {}.status,
              Cancelled.into_error_response {}.status,
            ]
            seq::join { xs = seq::map { xs = statuses, f = { x } -> int::to_str { n = x } }, sep = "," }
            """.trimIndent()
        )
        assertEquals(
            "504,502,502,502,502,500,500,503", r,
            "an upstream 404 must NOT pass through as this service's 404",
        )
    }

    @Test
    fun `an error response leaks neither the url nor the upstream body`() {
        val r = run(
            """
            body_of {
              resp = RequestFailed { url = "http://internal.svc/secret", status = 404,
                                     body = "row 7 missing" }.into_error_response {},
            }
            """.trimIndent()
        )
        assertEquals("upstream returned an error", r)
    }

    // ---- failure: the Exception sugar at a route -------------------------

    /**
     * The whole point: a handler writes straight-line code, each `unwrap`
     * early-returns like `?`, and a throw picks its own status.
     */
    @Test
    fun `a thrown Str becomes a 500 carrying its message`() {
        val r = run(
            """
            let load: { id: Str } -> Result Str Str = { id } ->
              if id == "7" then Ok { val = "seven" } else Err { error = "no such id" }
            let app = on_get {
              path = "/u/:id",
              handler = { m } -> do
                let outcome = perform ExceptionToResult in do
                  let name = unwrap { result = load { id = m.param_or { name = "id", default = "?" } } }
                  Ok { val = name }
                outcome.into_response {},
            }.otherwise { fallback = fallback }
            let resp = app { req = get_request { url = "/u/9" } }
            str::concat { l = int::to_str { n = resp.status }, r = body_of { resp = resp } }
            """.trimIndent()
        )
        assertEquals("500no such id", r)
    }

    @Test
    fun `the same handler answers normally when nothing throws`() {
        val r = run(
            """
            let load: { id: Str } -> Result Str Str = { id } ->
              if id == "7" then Ok { val = "seven" } else Err { error = "no such id" }
            let app = on_get {
              path = "/u/:id",
              handler = { m } -> do
                let outcome = perform ExceptionToResult in do
                  let name = unwrap { result = load { id = m.param_or { name = "id", default = "?" } } }
                  Ok { val = name }
                outcome.into_response {},
            }.otherwise { fallback = fallback }
            body_of { resp = app { req = get_request { url = "/u/7" } } }
            """.trimIndent()
        )
        assertEquals("seven", r)
    }

    @Test
    fun `a thrown HttpError reaches the route as a gateway status`() {
        val r = run(
            """
            let app = on_get {
              path = "/gw",
              handler = { m } -> do
                let outcome = perform ExceptionToResult in do
                  let v = unwrap { result = Err { error = Timeout { url = "http://u/x", ms = 50 } } }
                  Ok { val = v }
                outcome.into_response {},
            }.otherwise { fallback = fallback }
            app { req = get_request { url = "/gw" } }.status
            """.trimIndent()
        )
        assertEquals(504L, r)
    }

    /**
     * The one Result shape left: one whose ERROR TYPE IS NEVER DETERMINED.
     *
     * `Ok { val = "seven" }` with no `Err` anywhere is a `Result Str 'e` with
     * `'e` free, so there is no type for `IntoErrorResponse` to resolve
     * against and no dictionary can be supplied. That is a property of the
     * PROGRAM, not of the route: nothing in it says what an error would be.
     *
     * I had this test named for the `perform` region and that was wrong — the
     * region is fine, as [a perform region's Result answers when its types are
     * known] shows. What the two cases differ in is whether anything pins the
     * error type.
     *
     * It fails two different ways depending on the route, and BOTH are wrong
     * because neither is a compile error:
     *   - through the free verbs (here): silently runs
     *     `impl IntoResponse for Str` and dies in a native,
     *     `Expected String for field 's'`;
     *   - through a group's `.get`: loudly, naming the operation —
     *     "no dictionary was supplied for ability operation `into_response`".
     *
     * The right answer is to reject it while compiling, and the diagnostic for
     * exactly this already exists ("takes no receiver, so the type it produces
     * is what chooses the implementation"); it is simply not routed here.
     */
    @Test
    fun `a Result with an undetermined error type has no dictionary`() {
        val outcome = runCatching {
            run(
                """
                let app = on_get {
                  path = "/u",
                  handler = { m } -> perform ExceptionToResult in do
                    let name = unwrap { result = Ok { val = "seven" } }
                    Ok { val = name },
                }.otherwise { fallback = fallback }
                app { req = get_request { url = "/u" } }.status
                """.trimIndent()
            )
        }
        val err = outcome.exceptionOrNull()
        assertTrue(
            err != null,
            "expected an under-determined error type to fail; got ${outcome.getOrNull()}. " +
                "If this now answers 200, or fails at COMPILE time naming the undetermined " +
                "error type, update this test — the latter is the wanted outcome.",
        )
    }

    // ---- failure: throwing the response -------------------------------

    private val throwingApp = """
        let app = group { prefix = "" }.get {
          path = "/u/:id",
          handler = catching { handler = { m } -> do
            let id = m.param_or { name = "id", default = "?" }
            if id == "0" then throw { error = bad_request { body = "id must not be 0" } }
            else if id == "7" then "seven"
            else throw { error = not_found { body = "no such id" } } },
        }.otherwise { fallback = fallback }
    """.trimIndent()

    /**
     * What a `try_` route is for: the handler THROWS the response it wants
     * sent, with no perform of its own, no Ok wrap and no conversion call.
     */
    @Test
    fun `a thrown Response is what gets sent`() {
        val r = run(
            """
            $throwingApp
            let resp = app { req = get_request { url = "/u/9" } }
            str::concat { l = int::to_str { n = resp.status }, r = body_of { resp = resp } }
            """.trimIndent()
        )
        assertEquals("404no such id", r)
    }

    @Test
    fun `a different throw sends a different status`() {
        val r = run(
            """
            $throwingApp
            app { req = get_request { url = "/u/0" } }.status
            """.trimIndent()
        )
        assertEquals(400L, r, "the thrown response decides, not one blanket error status")
    }

    /** The success side still goes through IntoResponse. */
    @Test
    fun `the same throwing handler answers 200 when it does not throw`() {
        val r = run(
            """
            $throwingApp
            let resp = app { req = get_request { url = "/u/7" } }
            str::concat { l = int::to_str { n = resp.status }, r = body_of { resp = resp } }
            """.trimIndent()
        )
        assertEquals("200seven", r)
    }

    /** A throw short-circuits: nothing after it in the handler runs. */
    @Test
    fun `a throw skips the rest of the handler`() {
        val r = run(
            """
            let app = group { prefix = "" }.get {
              path = "/x",
              handler = catching { handler = { m } -> do
                let stopped = throw { error = bad_request { body = "stop" } }
                "reached-the-end" },
            }.otherwise { fallback = fallback }
            body_of { resp = app { req = get_request { url = "/x" } } }
            """.trimIndent()
        )
        assertEquals("stop", r)
    }

    /**
     * The documented escape hatch for a DOMAIN error: convert at the throw
     * site, where the error's meaning is known. This is what IntoErrorResponse
     * is for now that the thrown channel is a concrete Response.
     */
    @Test
    fun `a domain error converted at the throw site keeps its status`() {
        val r = run(
            """
            let app = group { prefix = "" }.get {
              path = "/gw",
              handler = catching { handler = { m } -> do
                let upstream = Timeout { url = "http://u/x", ms = 50 }
                throw { error = upstream.into_error_response {} } },
            }.otherwise { fallback = fallback }
            app { req = get_request { url = "/gw" } }.status
            """.trimIndent()
        )
        assertEquals(504L, r)
    }

    /** try_ and on_ routes compose in one table. */
    @Test
    fun `a throwing route and a plain route live in the same table`() {
        val r = run(
            """
            let app = group { prefix = "" }
              .get {
                path = "/u/:id",
                handler = catching { handler = { m } -> do
                  let id = m.param_or { name = "id", default = "?" }
                  if id == "7" then "seven" else throw { error = not_found { body = "no" } } },
              }
              .post { path = "/u", handler = { m } -> created { body = "made" } }
              .otherwise { fallback = fallback }
            app { req = { method = Post, url = "/u", headers = map::empty {}, body = None,
                          timeout_ms = None, chunks = None } }.status
            """.trimIndent()
        )
        assertEquals(201L, r)
    }

    /**
     * A pure handler on a route that also accepts throwing ones.
     *
     * This used to be REJECTED, and its old note said so: "folding the two
     * together would reject every pure handler, and this is the measurement
     * that says so". It was right about the notation available at the time —
     * `<...>` absorbs a thrown type without checking it, and `<Exception, ...>`
     * is a lower bound that forces every handler to throw.
     *
     * `<Exception? having 'error = Response, ...>` is the upper bound that was
     * missing: MAY perform Exception, and if so with a Response. The verbs
     * carry that row and wrap in `catching` themselves, so one `.get` serves
     * both kinds of handler — and `throwing a non-Response is a compile error`
     * in HttpTypedInputTest still holds, which is what the fold cost last time.
     */
    @Test
    fun `a pure handler is accepted by a route that allows throwing`() {
        assertEquals(
            200L,
            run(
                """
                let app = group { prefix = "" }
                  .get { path = "/p", handler = { m } -> "plain" }
                  .otherwise { fallback = fallback }
                app { req = get_request { url = "/p" } }.status
                """.trimIndent()
            ),
        )
    }

    /** And a THROWING handler on the same verb, with no `catching` in sight. */
    @Test
    fun `a throwing handler needs no wrapper`() {
        assertEquals(
            404L,
            run(
                """
                let app = group { prefix = "" }
                  .get { path = "/u/:id", handler = { m } -> do
                    let id = m.param_or { name = "id", default = "?" }
                    if id == "7" then "seven" else throw { error = not_found { body = "no" } } }
                  .otherwise { fallback = fallback }
                app { req = get_request { url = "/u/9" } }.status
                """.trimIndent()
            ),
        )
    }

    /** The pure and throwing forms in ONE table, on the same verb. */
    @Test
    fun `pure and throwing handlers share a table`() {
        assertEquals(
            "200:404",
            run(
                """
                let app = group { prefix = "" }
                  .get { path = "/p", handler = { m } -> "plain" }
                  .get { path = "/u/:id", handler = { m } -> do
                    let id = m.param_or { name = "id", default = "?" }
                    if id == "7" then "seven" else throw { error = not_found { body = "no" } } }
                  .otherwise { fallback = fallback }
                let a = app { req = get_request { url = "/p" } }.status
                let b = app { req = get_request { url = "/u/9" } }.status
                "$(int::to_str { n = a }):$(int::to_str { n = b })"
                """.trimIndent()
            ).toString(),
        )
    }

    // ---- failure: a Result inside a throwing route ----------------------

    private val orThrowApp = """
        let load: { id: Str } -> Result Str Str = { id } ->
          if id == "7" then Ok { val = "seven" } else Err { error = "no such id" }
        let upstream: { id: Str } -> Result Str HttpError = { id } ->
          Err { error = Timeout { url = "http://u/x", ms = 50 } }
        let app = group { prefix = "" }.get {
          path = "/u/:id",
          handler = catching { handler = { m } -> do
            let id = m.param_or { name = "id", default = "?" }
            if id == "gw" then or_throw { result = upstream { id = id } }
            else or_throw { result = load { id = id } } },
        }.otherwise { fallback = fallback }
    """.trimIndent()

    /**
     * `or_throw` is the bridge from Mezze's failure model to a route: failure
     * is a VALUE, so a fallible call answers a Result, and this unwraps the Ok
     * or throws the Err's own response. Reads like `?`.
     */
    @Test
    fun `or_throw passes an Ok straight through`() {
        val r = run(
            """
            $orThrowApp
            body_of { resp = app { req = get_request { url = "/u/7" } } }
            """.trimIndent()
        )
        assertEquals("seven", r)
    }

    @Test
    fun `or_throw turns a Str error into its 500`() {
        val r = run(
            """
            $orThrowApp
            let resp = app { req = get_request { url = "/u/9" } }
            str::concat { l = int::to_str { n = resp.status }, r = body_of { resp = resp } }
            """.trimIndent()
        )
        assertEquals("500no such id", r)
    }

    /**
     * The error's own type picks the status — the SAME or_throw call site
     * yields 500 for a Str and 504 for a Timeout, because IntoErrorResponse
     * decides, not the route.
     */
    @Test
    fun `or_throw gives an HttpError its gateway status`() {
        val r = run(
            """
            $orThrowApp
            app { req = get_request { url = "/u/gw" } }.status
            """.trimIndent()
        )
        assertEquals(504L, r)
    }

    @Test
    fun `or_throw outside a route is just a Result unwrap`() {
        val r = run(
            """
            let run_it = {} -> do
              let outcome = perform ExceptionToResult in do
                let v = or_throw { result = Err { error = "plain" } }
                Ok { val = v }
              match outcome is
                Ok { val = _ } -> 0
                Err { error = resp } -> resp.status
            run_it {}
            """.trimIndent()
        )
        assertEquals(
            500L, r,
            "the thrown value is the Response, so a handler outside a route sees it too",
        )
    }

    // ---- the table as a list -------------------------------------------

    private val listTable = """
        let load: { id: Str } -> Result Str Str = { id } ->
          if id == "7" then Ok { val = "seven" } else Err { error = "no such id" }
        let app = routes { of = [
          on_get { path = "/ping", handler = { m } -> "pong" },
          on_post { path = "/users", handler = { m } -> created { body = "made" } },
          on_get {
            path = "/u/:id",
            handler = catching { handler = { m } -> or_throw {
              result = load { id = m.param_or { name = "id", default = "?" } },
            } },
          },
        ] }.otherwise { fallback = fallback }
    """.trimIndent()

    /**
     * A route table is a LIST, so `routes { of = [ … ] }` replaces the first
     * route bare plus every later one wrapped in `.or_else { b = … }`.
     */
    @Test
    fun `a list table routes its first entry`() {
        val r = run(
            """
            $listTable
            body_of { resp = app { req = get_request { url = "/ping" } } }
            """.trimIndent()
        )
        assertEquals("pong", r)
    }

    @Test
    fun `a list table routes a later entry, by method too`() {
        val r = run(
            """
            $listTable
            app { req = { method = Post, url = "/users", headers = map::empty {}, body = None,
                          timeout_ms = None, chunks = None } }.status
            """.trimIndent()
        )
        assertEquals(201L, r)
    }

    /** on_* and try_* entries sit in the same list. */
    @Test
    fun `a throwing entry works inside a list table`() {
        val r = run(
            """
            $listTable
            let resp = app { req = get_request { url = "/u/9" } }
            str::concat { l = int::to_str { n = resp.status }, r = body_of { resp = resp } }
            """.trimIndent()
        )
        assertEquals("500no such id", r)
    }

    @Test
    fun `a list table falls through to the fallback`() {
        val r = run(
            """
            $listTable
            app { req = get_request { url = "/zzz" } }.status
            """.trimIndent()
        )
        assertEquals(404L, r)
    }

    /** First match wins, so list order is the priority — not specificity. */
    @Test
    fun `an earlier entry shadows a later one`() {
        val r = run(
            """
            let app = routes { of = [
              on_get { path = "/u/:id", handler = { m } -> "wildcard" },
              on_get { path = "/u/me", handler = { m } -> "specific" },
            ] }.otherwise { fallback = fallback }
            body_of { resp = app { req = get_request { url = "/u/me" } } }
            """.trimIndent()
        )
        assertEquals(
            "wildcard", r,
            "declaration order decides; put the specific route first",
        )
    }

    @Test
    fun `an empty list matches nothing and falls through`() {
        val r = run(
            """
            let app = routes { of = [] }.otherwise { fallback = fallback }
            app { req = get_request { url = "/anything" } }.status
            """.trimIndent()
        )
        assertEquals(404L, r)
    }

    // ---- Result as the ordinary error path ------------------------------

    /**
     * A Result out of a `perform` REGION, returned straight out of the
     * handler, with its types known. This is the case I had wrongly pinned as
     * broken; the region was never the problem.
     */
    @Test
    fun `a perform region's Result answers when its types are known`() {
        val r = run(
            """
            let load: { id: Str } -> Result Str Str = { id } ->
              if id == "7" then Ok { val = "seven" } else Err { error = "no such id" }
            let app = group { prefix = "" }
              .get {
                path = "/u/:id",
                handler = { m } -> perform ExceptionToResult in do
                  let name = unwrap { result = load { id = m.param_or { name = "id", default = "?" } } }
                  Ok { val = name },
              }
              .otherwise { fallback = fallback }
            let ok = app { req = get_request { url = "/u/7" } }
            let err = app { req = get_request { url = "/u/9" } }
            str::concat {
              l = body_of { resp = ok },
              r = str::concat { l = int::to_str { n = err.status }, r = body_of { resp = err } },
            }
            """.trimIndent()
        )
        assertEquals("seven500no such id", r)
    }


    private val resultRoutes = """
        let load: { id: Str } -> Result Str Str = { id } ->
          if id == "7" then Ok { val = "seven" } else Err { error = "no such id" }
        let upstream: { id: Str } -> Result Str HttpError = { id } ->
          Err { error = Timeout { url = "http://u/x", ms = 50 } }
    """.trimIndent()

    /**
     * The ordinary error path: failure is a VALUE, the handler answers a
     * Result, and the route converts it — `Ok` through IntoResponse, `Err`
     * through IntoErrorResponse. No Exception, and no adapter.
     */
    @Test
    fun `a Result handler answers both arms`() {
        val r = run(
            """
            $resultRoutes
            let app = group { prefix = "" }
              .get { path = "/u/:id",
                     handler = { m } -> load { id = m.param_or { name = "id", default = "?" } } }
              .otherwise { fallback = fallback }
            let ok = app { req = get_request { url = "/u/7" } }
            let err = app { req = get_request { url = "/u/9" } }
            str::concat {
              l = str::concat { l = int::to_str { n = ok.status }, r = body_of { resp = ok } },
              r = str::concat { l = int::to_str { n = err.status }, r = body_of { resp = err } },
            }
            """.trimIndent()
        )
        assertEquals("200seven500no such id", r)
    }

    /**
     * The error's OWN type picks the status, with the Ok and Err types
     * DIFFERING — which is exactly what used to die in a native, one dictionary
     * slot being filled from the sibling constraint's type. The minimal shape
     * of that bug is DictConstraintCrossingTest.
     */
    @Test
    fun `a distinct error type picks its own status`() {
        val r = run(
            """
            $resultRoutes
            let app = group { prefix = "" }
              .get { path = "/gw", handler = { m } -> upstream { id = "x" } }
              .otherwise { fallback = fallback }
            app { req = get_request { url = "/gw" } }.status
            """.trimIndent()
        )
        assertEquals(504L, r)
    }

    /**
     * A `Result Str Str` through the free verbs. This USED to be the only
     * Result that worked, and only by coincidence: both slots holding one type
     * hid the crossing, so the wrong dictionary was indistinguishable from the
     * right one. Kept beside the distinct-error case below so the pair states
     * what is now true of both.
     */
    @Test
    fun `a same-typed Result answers without an adapter`() {
        val r = run(
            """
            $resultRoutes
            let app = on_get {
              path = "/u/:id",
              handler = { m } -> load { id = m.param_or { name = "id", default = "?" } },
            }.otherwise { fallback = fallback }
            app { req = get_request { url = "/u/9" } }.status
            """.trimIndent()
        )
        assertEquals(500L, r)
    }

    /**
     * This test pinned the bug, asserting that a distinct-error Result mis-
     * dispatched. It is now its regression test, asserting the opposite.
     */
    @Test
    fun `a distinct-error Result survives the free verbs too`() {
        val r = run(
            """
            $resultRoutes
            let app = on_get { path = "/gw", handler = { m } -> upstream { id = "x" } }
              .otherwise { fallback = fallback }
            app { req = get_request { url = "/gw" } }.status
            """.trimIndent()
        )
        assertEquals(504L, r)
    }

    // ---- wrapper-free handlers ------------------------------------------

    /**
     * THE point of the group surface: the verb takes the handler directly and
     * converts whatever it answers, so four routes in ONE chain can each
     * answer a different type with nothing wrapping anything.
     *
     * This needs the verb methods to carry `with IntoResponse 'r` AND to be
     * re-instantiated per call. Both hold for an IMPORTED impl; a locally
     * declared one collapses `'r` to the first use's type, which is what made
     * me report mixing as impossible.
     */
    @Test
    fun `one chain mixes four handler return types`() {
        val r = run(
            """
            let load: { id: Str } -> Result Str Str = { id } ->
              if id == "7" then Ok { val = "seven" } else Err { error = "no such id" }
            let app = group { prefix = "/api" }
              .get { path = "/health", handler = { m } -> "ok" }
              .get { path = "/made", handler = { m } -> created { body = "made" } }
              .get { path = "/j", handler = { m } -> JString { val = "jay" } }
              .get { path = "/u/:id",
                     handler = { m } -> load { id = m.param_or { name = "id", default = "?" } } }
              .otherwise { fallback = fallback }
            let parts = [
              body_of { resp = app { req = get_request { url = "/api/health" } } },
              int::to_str { n = app { req = get_request { url = "/api/made" } }.status },
              body_of { resp = app { req = get_request { url = "/api/j" } } },
              body_of { resp = app { req = get_request { url = "/api/u/7" } } },
            ]
            seq::join { xs = parts, sep = "|" }
            """.trimIndent()
        )
        assertEquals("ok|201|\"jay\"|seven", r)
    }

    /**
     * The chain's regression test for the crossing: a `Result Str HttpError`
     * route sitting beside one answering a Str, nothing wrapping anything.
     * This shape needed an adapter until the dictionary fix.
     */
    @Test
    fun `a distinct-error Result works in a mixed chain`() {
        val r = run(
            """
            let upstream: { id: Str } -> Result Str HttpError = { id } ->
              Err { error = Timeout { url = "http://u/x", ms = 50 } }
            let app = group { prefix = "" }
              .get { path = "/ok", handler = { m } -> "fine" }
              .get { path = "/gw", handler = { m } -> upstream { id = "x" } }
              .otherwise { fallback = fallback }
            str::concat {
              l = body_of { resp = app { req = get_request { url = "/ok" } } },
              r = int::to_str { n = app { req = get_request { url = "/gw" } }.status },
            }
            """.trimIndent()
        )
        assertEquals("fine504", r)
    }

    // ---- route groups ---------------------------------------------------

    private val nestedGroups = """
        let users = group { prefix = "/users" }
          .get { path = "/:id", handler = { m } -> m.param_or { name = "id", default = "?" } }
          .post { path = "", handler = { m } -> created { body = "made" } }
        let app = group { prefix = "/api" }
          .nest { group = users }
          .get { path = "/health", handler = { m } -> "ok" }
          .otherwise { fallback = fallback }
    """.trimIndent()

    /**
     * `.get` on a group has to reach THIS impl past the dozen other inherent
     * `get`s in scope (Seq, List, Map, Deque, SortedMap, NonEmptyList, Bytes,
     * Client, MockRule, …). It does because RouteGroup is a NEWTYPE: nominal by
     * construction, so the receiver carries an identity the matcher can use. As
     * a record alias the same code compiled to a field read and died at runtime
     * with `Field not found: get`.
     */
    @Test
    fun `a group route answers under its own prefix`() {
        val r = run(
            """
            $nestedGroups
            body_of { resp = app { req = get_request { url = "/api/health" } } }
            """.trimIndent()
        )
        assertEquals("ok", r)
    }

    /** A nested group's prefix composes with its parent's. */
    @Test
    fun `a nested group answers under both prefixes`() {
        val r = run(
            """
            $nestedGroups
            body_of { resp = app { req = get_request { url = "/api/users/7" } } }
            """.trimIndent()
        )
        assertEquals("7", r)
    }

    /** An empty path means the prefix itself — /api + /users + "" = /api/users. */
    @Test
    fun `an empty path routes the prefix itself`() {
        val r = run(
            """
            $nestedGroups
            app { req = { method = Post, url = "/api/users", headers = map::empty {},
                          body = None, timeout_ms = None, chunks = None } }.status
            """.trimIndent()
        )
        assertEquals(201L, r)
    }

    /** The un-prefixed path must NOT match once the group is nested. */
    @Test
    fun `a nested route is not reachable without the parent prefix`() {
        val r = run(
            """
            $nestedGroups
            app { req = get_request { url = "/users/7" } }.status
            """.trimIndent()
        )
        assertEquals(404L, r)
    }

    /** Groups nest to any depth, for the same reason one level works. */
    @Test
    fun `groups nest three deep`() {
        val r = run(
            """
            let inner = group { prefix = "/c" }
              .get { path = "/leaf", handler = { m } -> "deep" }
            let mid = group { prefix = "/b" }.nest { group = inner }
            let app = group { prefix = "/a" }.nest { group = mid }
              .otherwise { fallback = fallback }
            body_of { resp = app { req = get_request { url = "/a/b/c/leaf" } } }
            """.trimIndent()
        )
        assertEquals("deep", r)
    }

    /** One slash between prefix and path, however each is written. */
    @Test
    fun `prefix joining does not double or drop slashes`() {
        val r = run(
            """
            let app = group { prefix = "/a/" }
              .get { path = "/x", handler = { m } -> "slashy" }
              .otherwise { fallback = fallback }
            body_of { resp = app { req = get_request { url = "/a/x" } } }
            """.trimIndent()
        )
        assertEquals("slashy", r)
    }

    /** A prefixless group is the plain case, and still works. */
    @Test
    fun `an empty prefix leaves paths alone`() {
        val r = run(
            """
            let app = group { prefix = "" }
              .get { path = "/bare", handler = { m } -> "bare" }
              .otherwise { fallback = fallback }
            body_of { resp = app { req = get_request { url = "/bare" } } }
            """.trimIndent()
        )
        assertEquals("bare", r)
    }

    /** `table {}` hands back a Routes, so a group composes with or_else. */
    @Test
    fun `a group converts to a table that composes with or_else`() {
        val r = run(
            """
            let g = group { prefix = "/g" }
              .get { path = "/x", handler = { m } -> "from-group" }
            let app = g.table {}
              .or_else { b = on_get { path = "/plain", handler = { m } -> "from-route" } }
              .otherwise { fallback = fallback }
            str::concat {
              l = body_of { resp = app { req = get_request { url = "/g/x" } } },
              r = body_of { resp = app { req = get_request { url = "/plain" } } },
            }
            """.trimIndent()
        )
        assertEquals("from-groupfrom-route", r)
    }

    /** First added wins inside a group, as in a list. */
    @Test
    fun `an earlier group route shadows a later one`() {
        val r = run(
            """
            let app = group { prefix = "/u" }
              .get { path = "/:id", handler = { m } -> "wildcard" }
              .get { path = "/me", handler = { m } -> "specific" }
              .otherwise { fallback = fallback }
            body_of { resp = app { req = get_request { url = "/u/me" } } }
            """.trimIndent()
        )
        assertEquals("wildcard", r, "declaration order decides; put the specific route first")
    }

    /** respond and catching mix freely in one group — the handler side varies. */
    @Test
    fun `a group mixes answering and throwing handlers`() {
        val r = run(
            """
            let app = group { prefix = "/m" }
              .get { path = "/plain", handler = { m } -> "plain" }
              .get { path = "/boom", handler = catching { handler = { m } ->
                throw { error = bad_request { body = "boom" } } } }
              .otherwise { fallback = fallback }
            let a = app { req = get_request { url = "/m/plain" } }
            let b = app { req = get_request { url = "/m/boom" } }
            str::concat { l = int::to_str { n = a.status }, r = int::to_str { n = b.status } }
            """.trimIndent()
        )
        assertEquals("200400", r)
    }

    // ---- Response builders ----------------------------------------------

    @Test
    fun `the status builders answer their own codes`() {
        val r = run(
            """
            let codes = [
              created { body = "a" }.status,
              no_content {}.status,
              bad_request { body = "b" }.status,
              not_found { body = "c" }.status,
              server_error { body = "d" }.status,
              redirect { to = "/x" }.status,
            ]
            seq::join { xs = seq::map { xs = codes, f = { x } -> int::to_str { n = x } }, sep = "," }
            """.trimIndent()
        )
        assertEquals("201,204,400,404,500,302", r)
    }

    @Test
    fun `a redirect carries a readable Location`() {
        val r = run("""header_of { resp = redirect { to = "/login" }, name = "location" }""")
        assertEquals("/login", r)
    }

    @Test
    fun `204 carries no body`() {
        val r = run("""bytes::size { b = no_content {}.body }""")
        assertEquals(0L, r)
    }

    /** Each setter preserves the others, so a chain reads in any order. */
    @Test
    fun `status then text keeps the status`() {
        val r = run(
            """
            let resp = response_ok_text { body = "x" }
              .with_status { code = 201 }
              .with_text { val = "hi" }
            int::to_str { n = resp.status }
            """.trimIndent()
        )
        assertEquals("201", r)
    }

    @Test
    fun `text then status keeps the body`() {
        val r = run(
            """
            let resp = response_ok_text { body = "x" }
              .with_text { val = "hi" }
              .with_status { code = 201 }
            body_of { resp = resp }
            """.trimIndent()
        )
        assertEquals("hi", r)
    }

    @Test
    fun `a chained header is readable`() {
        val r = run(
            """
            let resp = response_ok_text { body = "x" }.with_header { k = "X-Trace", v = "abc" }
            header_of { resp = resp, name = "x-trace" }
            """.trimIndent()
        )
        assertEquals("abc", r, "the setter lowercases the key, which is what get_header assumes")
    }

    @Test
    fun `merged headers are readable whatever case they were given in`() {
        val r = run(
            """
            let extra = map::of { of = [{ k = "X-Rate-Limit", v = "60" }] }
            let resp = response_ok_text { body = "x" }.with_headers { headers = extra }
            header_of { resp = resp, name = "x-rate-limit" }
            """.trimIndent()
        )
        assertEquals("60", r)
    }

    @Test
    fun `with_json sets the body and the content type`() {
        val r = run(
            """
            let resp = response_ok_text { body = "x" }.with_json { val = JString { val = "j" } }
            str::concat {
              l = body_of { resp = resp },
              r = header_of { resp = resp, name = "content-type" },
            }
            """.trimIndent()
        )
        assertEquals("\"j\"application/json", r)
    }

    /**
     * THE pre-existing bug this surfaced: `response_json` set `Content-Type`
     * capitalised while `get_header` lowercases only the NEEDLE, so the header
     * it set could not be read by anything — including its own
     * `.content_type {}`, which answered None.
     */
    @Test
    fun `response_json's own content type is readable`() {
        val r = run(
            """
            match response_json { body = JString { val = "j" } }.content_type {} is
              Some { val } -> val
              None -> "MISSING"
            """.trimIndent()
        )
        assertEquals("application/json", r)
    }

    @Test
    fun `setting a body clears a streamed one`() {
        val r = run(
            """
            let streamed = response_stream {
              status = 200,
              headers = map::empty {},
              chunks = iter::from_seq { xs = [bytes::from_str { s = "stream" }] },
            }
            match streamed.with_text { val = "plain" }.chunks is
              Some { val = _ } -> "still-streaming"
              None -> body_of { resp = streamed.with_text { val = "plain" } }
            """.trimIndent()
        )
        assertEquals("plain", r, "chunks would otherwise win and the new body never ship")
    }
}
