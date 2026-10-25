package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading typed input off a request, and failing usefully when it is not there.
 *
 * A path parameter is a Str, so a handler wanting `/users/:id` as an Int parsed
 * it by hand. No new ability was needed for that: QueryC already decodes a
 * SCALAR, and its one rule says a scalar is a pair whose key is relative to
 * nothing — `k = ""`. So one value is a one-pair list and the existing
 * `impl FromQuery 'a for [{ k: Str, v: Str }]` does the rest, which is why this
 * gets Int, Bool, a newtype and anything else with a QueryC decoder for free.
 *
 * The target type is never written at the decode site. It comes from whatever
 * the use site asks for, which is the same way `query_as` already worked.
 *
 * Failing usefully is the other half. `or_throw` already converts any
 * `Result 'a 'e` with an `IntoErrorResponse 'e` into a thrown Response, so the
 * only thing missing was an error that implements it. `BadInput` is the
 * router's own, and deliberately not the codec's `DecodeError`: that type
 * reaches this module under a `use … as` alias, and an impl whose target
 * arrived aliased registers under the LOCAL spelling and never matches the
 * declaration. Owning the error is better anyway — it knows the parameter's
 * NAME, which a scalar decode's empty path does not.
 */
class HttpTypedInputTest {

    private val head = """
        use std::effects::http::{ Request, Response, IntoResponse, IntoErrorResponse, or_throw, not_found, body_str }
        use std::effects::exception::{ throw }
        use std::effects::http_server::{ get_request }
        use std::effects::http_router::{ group, catching, BadInput }
        use std::str
        use std::int

        ## The consumer says what it wants; nothing at the decode site does.
        let show = { id: Int } -> str::concat { l = "user #", r = int::to_str { n = id } }

        let text_of: { resp: Response } -> Str = { resp } -> match body_str { resp = resp } is
          Ok { val } -> val
          Err { error = _ } -> "could not decode"
    """.trimIndent()

    private fun run(body: String): Any = MezzeTestHelper.run("$head\n$body")

    /**
     * Join source blocks at column 0.
     *
     * A multi-line `trimIndent` block interpolated at an INDENTED position keeps
     * its continuation lines where they were, so a `do` block inside it no
     * longer has its statements in one column and the parser says so. Joining
     * instead of interpolating keeps every block's own indentation intact.
     */
    private fun parts(vararg blocks: String): String = blocks.joinToString("\n")

    /** Answer as "<status> <body>", which is what every test here checks. */
    private val answer = """
        let answer = { url: Str, app: { req: Request } -> Response <...> } -> do
          let r = get_request { url = url }.to { app = app }
          str::concat { l = int::to_str { n = r.status }, r = str::concat { l = " ", r = text_of { resp = r } } }
    """.trimIndent()

    /**
     * The app under test: one route, no `match` and no `Result` in the handler.
     *
     * `catching` is what lets a handler throw. The verbs applied it themselves
     * for a while, so this read `handler = { m } -> …` even when it threw —
     * which was nicer and unsound: the verb's own handler row must be a bare
     * `<...>` for a pure handler to pass, and a bare row absorbs the THROWN
     * type without checking it. A handler throwing a non-Response then compiled
     * and died at runtime, where this form rejects it while compiling.
     */
    private val throwingApp = """
        let api = group { prefix = "" }
          .get { path = "/users/:id", handler = catching { handler = { m } -> do
            let id = or_throw { result = m.param_as { name = "id" } }
            show { id = id } } }
          .handler {}
    """.trimIndent()

    /** A well-formed param decodes to the type the consumer asked for. */
    @Test
    fun `a path parameter decodes to the type the use site asks for`() {
        val r = run(parts(answer, throwingApp, """answer { url = "/users/7", app = api }"""))
        assertEquals("200 user #7", r)
    }

    /**
     * And a malformed one is a 400 that says where, what was wanted, and what
     * came — not a crash, and not a 400 whose body is only "bad request".
     */
    @Test
    fun `a malformed path parameter is a 400 naming the problem`() {
        val r = run(parts(answer, throwingApp, """answer { url = "/users/abc", app = api }"""))
        assertEquals("400 id: expected Int, found abc", r)
    }

    /** A name the route never bound is reported against that name. */
    @Test
    fun `an unbound parameter name is a 400 naming it`() {
        val app = """
            let api = group { prefix = "" }
              .get { path = "/users/:id", handler = catching { handler = { m } -> do
                let other = or_throw { result = m.param_as { name = "nope" } }
                show { id = other } } }
              .handler {}
        """.trimIndent()
        val r = run(parts(answer, app, """answer { url = "/users/7", app = api }"""))
        assertEquals("400 nope: expected a path parameter the pattern declares", r)
    }

    /**
     * The Result form, for a handler that wants to answer the failure itself
     * rather than throw. Both shapes read the same decode.
     */
    @Test
    fun `the same decode can be matched instead of thrown`() {
        val app = """
            let api = group { prefix = "" }
              .get { path = "/users/:id", handler = { m } -> match m.param_as { name = "id" } is
                Ok { val } -> show { id = val }
                Err { error = _ } -> "not a number" }
              .handler {}
        """.trimIndent()
        val r = run(
            parts(
                answer, app,
                """
                str::concat {
                  l = answer { url = "/users/7", app = api },
                  r = str::concat { l = " | ", r = answer { url = "/users/abc", app = api } },
                }
                """.trimIndent(),
            )
        )
        assertEquals("200 user #7 | 200 not a number", r)
    }

    /**
     * Throwing something that is NOT a Response is refused while compiling.
     *
     * This is why the verbs do not wrap the handler themselves. `catching`
     * declares `<Exception having 'error = Response, ...>`, so the handler's own
     * thrown type meets `Response` here and a mismatch is a type error. Routed
     * through a verb whose row is a bare `<...>`, that row absorbs the thrown
     * type unchecked and the same program died at runtime with
     * `Field not found: status`.
     *
     * The wrapper can go away again when a row can say "MAY perform Exception,
     * and if so with `'error = Response`" — an upper bound that `<E, ...>`
     * cannot express, because it means AT LEAST.
     */
    @Test
    fun `throwing a non-Response is a compile error`() {
        val outcome = runCatching {
            run(
                """
                let api = group { prefix = "" }
                  .get { path = "/x", handler = catching { handler = { m } ->
                    throw { error = { why = "not a response" } } } }
                  .handler {}
                get_request { url = "/x" }.to { app = api }.status
                """.trimIndent()
            )
        }
        val err = outcome.exceptionOrNull()
        assertTrue(
            err != null,
            "throwing a non-Response must be refused; got ${outcome.getOrNull()}",
        )
    }

    /**
     * A query that does not fit answers through the same error and the same 400.
     *
     * NOTE the asymmetry in the expected string: the PARAMETER case names `id`
     * and the QUERY case names no field. `param_as` knows the name it was asked
     * for and puts it in `at`, but a whole-record decode has to learn the field
     * from the codec, and `decode_record_field` only extracts the sub-input — no
     * one attaches the failing field's name to the error path on the way out. So
     * `e.path` arrives empty and the 400 can only say what it expected.
     *
     * When that is fixed, this expects `400 page: expected Int, found xx` and
     * the fix is to stop dropping the field name, not to change this test.
     */
    @Test
    fun `a query that does not fit is a 400 through the same path`() {
        val app = """
            let page_of = { p: { page: Int } } -> int::to_str { n = p.page }
            let api = group { prefix = "" }
              .get { path = "/list", handler = catching { handler = { m } -> do
                let q = or_throw { result = m.query_as {} }
                page_of { p = q } } }
              .handler {}
        """.trimIndent()
        val r = run(
            parts(
                answer, app,
                """
                str::concat {
                  l = answer { url = "/list?page=2", app = api },
                  r = str::concat { l = " | ", r = answer { url = "/list?page=xx", app = api } },
                }
                """.trimIndent(),
            )
        )
        assertEquals("200 2 | 400 expected Int, found xx", r)
    }
}
