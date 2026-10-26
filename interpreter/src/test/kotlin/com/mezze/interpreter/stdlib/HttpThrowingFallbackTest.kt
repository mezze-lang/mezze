package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A FALLBACK may throw its Response, like a handler may.
 *
 * Same hole the verbs had: written bare, the slot absorbed the thrown type
 * unchecked and the mistake reached the wire instead of the compiler. The
 * fallback slot now reads `<Exception? having 'error = Response, ...>` and
 * `otherwise` catches. `catching` could not be reused — its handler takes a
 * `Matched`, a fallback takes the unmatched `Request` — so the catch is inline
 * there, and there is no IntoResponse constraint to carry because a fallback
 * already answers a Response.
 *
 * These live in their own file, and that is deliberate. Every other HTTP test
 * file binds `let fallback` at top level, and a top-level binding of that name
 * CAPTURES the `fallback` a route was given — see
 * [a top-level binding captures a stdlib parameter of the same name], which
 * pins that separately. A test for a throwing fallback cannot share a header
 * with a global named `fallback` without measuring the other bug instead.
 */
class HttpThrowingFallbackTest {
    private val head = """
        use std::effects::http::{ Request, Response, Get, not_found, response_status, response_ok_text }
        use std::effects::exception::{ throw }
        use std::effects::http_server::{ get_request }
        use std::effects::http_router::{ on_get, group, otherwise, route, Matched }
        use std::collections::bytes
        use std::int
    """.trimIndent()

    private fun run(body: String): Any = MezzeTestHelper.run("$head\n${body.trimIndent()}")

    @Test fun `a throwing fallback answers what it threw`() {
        assertEquals(
            418L,
            run(
                """
                let app = on_get { path = "/known", handler = { m } -> "here" }
                  .otherwise { fallback = { req } ->
                    throw { error = response_status { code = 418, body = "teapot" } } }
                app { req = get_request { url = "/elsewhere" } }.status
                """
            ),
        )
    }

    @Test fun `a pure fallback still answers`() {
        assertEquals(
            499L,
            run(
                """
                let fb: { req: Request } -> Response = { req } -> response_status { code = 499, body = "pure" }
                let app = on_get { path = "/known", handler = { m } -> "here" }
                  .otherwise { fallback = fb }
                app { req = get_request { url = "/elsewhere" } }.status
                """
            ),
        )
    }

    @Test fun `a matched route is unaffected by a throwing fallback`() {
        assertEquals(
            200L,
            run(
                """
                let app = on_get { path = "/known", handler = { m } -> "here" }
                  .otherwise { fallback = { req } ->
                    throw { error = response_status { code = 418, body = "teapot" } } }
                app { req = get_request { url = "/known" } }.status
                """
            ),
        )
    }

    @Test fun `the free otherwise catches too, not just the method`() {
        assertEquals(
            418L,
            run(
                """
                let app = otherwise {
                  routes = on_get { path = "/known", handler = { m } -> "here" },
                  fallback = { req } -> throw { error = response_status { code = 418, body = "teapot" } },
                }
                app { req = get_request { url = "/elsewhere" } }.status
                """
            ),
        )
    }

    /**
     * A top-level binding does NOT replace a handler a route was given.
     *
     * This was open when the verb fold landed, and asserted at its wrong answer
     * here. `route` reads its `handler` inside its own `run = { req } -> … }`,
     * so the handler is a CAPTURE — and a captured function called from a
     * nested lambda resolved to no frame slot, after which `compileCall` fell
     * into its by-name routes and one of them answered for whatever
     * declaration shared the name. With `let handler` in scope the route
     * answered that global's 555 instead of the handler it was handed.
     *
     * `resolveLocalSlot` now consults the captures, which `compileVar` always
     * did — the two are the READ and the CALL of the same name, and only one of
     * them knew about captures. CapturedParamNotShadowedTest holds the
     * four-line reproduction and the boundaries; this is the case that found
     * it, so it stays here pointed at the stdlib path that bit.
     *
     * `handler` and `fallback` are the two most natural names for exactly these
     * values, which is why it mattered: a user met it by writing the obvious
     * thing.
     */
    @Test fun `a top-level binding does not replace the handler a route was given`() {
        assertEquals(
            200L,
            run(
                """
                let handler: { m: Matched } -> Response = { m } ->
                  response_status { code = 555, body = "GLOBAL" }
                let app = route { method = Get, path = "/p",
                                  handler = { m } -> response_ok_text { body = "written" } }
                  .otherwise { fallback = { req } -> not_found { body = "nope" } }
                app { req = get_request { url = "/p" } }.status
                """
            ),
            "the handler the route was given must answer, not the global of that name",
        )
    }

    /** And the same for `fallback`, the other name a user naturally picks. */
    @Test fun `a top-level binding does not replace a given fallback`() {
        assertEquals(
            499L,
            run(
                """
                let fallback: { req: Request } -> Response = { req } -> not_found { body = "nope" }
                let app = on_get { path = "/known", handler = { m } -> "here" }
                  .otherwise { fallback = { req } -> response_status { code = 499, body = "inline" } }
                app { req = get_request { url = "/elsewhere" } }.status
                """
            ),
            "the fallback written at the call site must answer, not the global of that name",
        )
    }
}
