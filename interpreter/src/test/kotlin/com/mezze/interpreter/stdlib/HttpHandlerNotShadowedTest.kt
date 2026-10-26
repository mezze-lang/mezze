package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A top-level binding does not replace a handler or fallback a route was given.
 *
 * The stdlib half of `CapturedParamNotShadowedTest`, kept beside the HTTP
 * surface because this is where the bug was MET rather than where it lived.
 * `route` reads its `handler` inside its own `run = { req } -> … }`, and
 * `otherwise` reads its `fallback` inside `{ req } -> … }`, so both are
 * CAPTURES — and a captured function called from a nested lambda used to
 * resolve to no frame slot, after which `compileCall` fell into its by-name
 * routes and answered for whatever declaration shared the name.
 *
 * `handler` and `fallback` are the two most natural names for exactly these
 * values, so a user met this by writing the obvious thing and had their own
 * function called in place of the one they passed — silently, with a fitting
 * type and no diagnostic.
 *
 * Fixed by having `compileCall` stop short of the by-name declaration-index
 * lookup when the frame captures that name; `CapturedParamNotShadowedTest`
 * holds the four-line reproduction, the boundaries, and the note on why the
 * tidier-looking fix in `resolveLocalSlot` breaks 37 recursive tests.
 */
class HttpHandlerNotShadowedTest {
    private val head = """
        use std::effects::http::{ Request, Response, Get, not_found, response_status, response_ok_text }
        use std::effects::http_server::{ get_request }
        use std::effects::http_router::{ on_get, otherwise, route, Matched }
        use std::collections::bytes
        use std::int
    """.trimIndent()

    private fun run(body: String): Any = MezzeTestHelper.run("$head\n${body.trimIndent()}")

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
