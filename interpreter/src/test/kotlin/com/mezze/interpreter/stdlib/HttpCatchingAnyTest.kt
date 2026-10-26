package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `catching_any` — a route whose handler throws its OWN error type, converted
 * on the way out by `IntoErrorResponse`.
 *
 * `catching` takes a handler that throws a `Response`: it has already decided
 * the status and the body, so no dictionary is needed and it always worked.
 * This one lets a route speak in its domain's terms and decide the wire shape
 * in one place. It needs two things that were both missing:
 *
 *  1. the thrown type must reach the `Err` arm's binding — what
 *     `having 'error = 'e` says, fixed in the checker (ThrownErrorTypeThreadsTest);
 *  2. the CALL SITE must resolve `IntoErrorResponse` at that type, which means
 *     binding the callee's constraint variable from the actual argument. The
 *     binder walked parameters, results, records and applications but never the
 *     EFFECT ROW, and a thrown type lives only there — so it stayed a variable,
 *     dict injection fell back to forwarding from a caller with nothing to
 *     forward, and the slot reached `DictFallbackRootNode`.
 *
 * THE HANDLER MUST BE NAMED, which the second test pins. An argument lambda's
 * type is stamped with the row from the pushed CONTEXT — the callee's declared
 * `<Exception having 'error = 'e, ...>`, carrying the CALLEE's variable — while
 * a named binding carries its own solved row with the real thrown type. So an
 * inline handler leaves the call site nothing to resolve against. It fails
 * loudly rather than wrongly.
 */
class HttpCatchingAnyTest {
    private val head = """
        use std::effects::http::{ Response, IntoResponse, IntoErrorResponse, response_status, response_ok_text }
        use std::effects::exception::{ throw }
        use std::effects::http_server::{ get_request }
        use std::effects::http_router::{ group, catching_any }
    """.trimIndent()

    private fun run(body: String): Any = MezzeTestHelper.run("$head\n${body.trimIndent()}")

    @Test fun `a named handler's thrown Str becomes its 500`() {
        assertEquals(
            500L,
            run(
                """
                let h = { m } -> throw { error = "gone" }
                let api = group { prefix = "" }
                  .get { path = "/u", handler = catching_any { handler = h } }
                  .handler {}
                get_request { url = "/u" }.to { app = api }.status
                """
            ),
        )
    }

    /**
     * The restriction, pinned. When this starts PASSING with an inline lambda,
     * the stamped-row cause above is fixed — invert it and drop the note from
     * `catching_any`'s doc.
     */
    @Test fun `an inline handler does not resolve its dictionary yet`() {
        val outcome = runCatching {
            run(
                """
                let api = group { prefix = "" }
                  .get { path = "/u", handler = catching_any { handler = { m } ->
                    throw { error = "gone" } } }
                  .handler {}
                get_request { url = "/u" }.to { app = api }.status
                """
            )
        }
        val err = outcome.exceptionOrNull()
        assertTrue(
            err != null && err.message!!.contains("no dictionary was supplied"),
            "expected the loud dictionary failure; got ${outcome.getOrNull() ?: err?.message}",
        )
    }
}
