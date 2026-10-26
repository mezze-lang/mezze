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
     * KNOWN OPEN, and asserted at its WRONG answer so the day it changes is
     * visible. Nothing here is about throwing or about the `?` row.
     *
     * A FUNCTION-valued parameter that a NESTED lambda captures, whose name
     * matches a top-level declaration, is replaced by that declaration. So
     * `route` — whose `handler` is read inside its own `run = { req } -> … }` —
     * calls a user's `let handler` instead of the handler it was handed, and
     * answers the global's 555.
     *
     * Measured boundaries, each by changing one thing:
     *  - it needs the reference to be CAPTURED by a nested lambda. The same
     *    collision with the parameter used directly in the body is correct;
     *  - it needs the captured value to be a FUNCTION. The same collision on an
     *    `Int` parameter is correct;
     *  - the types must fit: a `let fallback: Int` does not capture a
     *    `{ req: Request } -> Response` slot, and the written value is used;
     *  - it does NOT depend on inline vs named arguments, free function vs
     *    chained method, or whether the global is declared before or after;
     *  - it is NOT about crossing a module boundary. It reproduces inside one
     *    module in four lines:
     *
     *        let f: { n: Int } -> Int = { n } -> 111
     *        let mk: { f: { n: Int } -> Int } -> { } -> Int = { f } -> { } -> f { n = 1 }
     *        let g = mk { f = { n } -> 222 }
     *        g { }        # 111, and it should be 222
     *
     *    The stdlib is merely where this bites hardest, because a caller cannot
     *    see the parameter names they have to avoid;
     *  - `route` is the raw layer, untouched by the verb fold, and the behaviour
     *    is identical with the fallback slot's row written bare, so this
     *    predates both.
     *
     * Where: TypedASTCompiler's `BindingRef.Local` fall-through checks scopes,
     * params and locals, then searches the declaration index BY NAME — and
     * never consults the captures, which the `BindingRef.Param` branch
     * immediately below it does consult. A hit there emits a global read, and
     * `isLambdaHash` is why only function-valued names are taken.
     *
     * Consequences worth stating plainly: `handler` and `fallback` are the two
     * most natural names for exactly these values, so a user meets this by
     * writing the obvious thing, and gets their own function silently called in
     * place of the one they passed.
     */
    @Test fun `a top-level binding captures a stdlib parameter of the same name`() {
        assertEquals(
            555L,
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
            "the global named `handler` is used instead of the written one — when this " +
                "answers 200, the capture is fixed and this test should assert that",
        )
    }
}
