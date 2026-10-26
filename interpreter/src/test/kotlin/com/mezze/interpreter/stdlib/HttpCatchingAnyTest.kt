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
 * An INLINE handler needed a third thing, and it is the one that made this
 * usable. Emit stamps a lambda's type with the row from the pushed CONTEXT,
 * which for an argument is the CALLEE's declared row — so the associated type
 * was the callee's VARIABLE, and the call site had nothing concrete to resolve
 * against. A NAMED handler always worked, because a binding carries its own
 * solved row. The body knew all along: the generator builds a lambda's arrow
 * with `effectInstances = mergedEffectInstances`, which for
 * `{ m } -> throw { error = "gone" }` says `'error = Str`. Those are now
 * recorded per Loc (`lambdaRowInstances`, beside the older `lambdaParamTypes`
 * that exists for the same reason) and fill any associated type the context
 * left open.
 */
class HttpCatchingAnyTest {
    private val head = """
        use std::effects::http::{ Response, IntoResponse, IntoErrorResponse, response_status, response_ok_text }
        use std::effects::exception::{ throw }
        use std::effects::http_server::{ get_request }
        use std::effects::http_router::{ group, catching_any, BadInput }
        use std::collections::map
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

    /** An INLINE handler, which is how anyone would actually write it. */
    @Test fun `an inline handler's thrown Str becomes its 500`() {
        assertEquals(
            500L,
            run(
                """
                let api = group { prefix = "" }
                  .get { path = "/u", handler = catching_any { handler = { m } ->
                    throw { error = "gone" } } }
                  .handler {}
                get_request { url = "/u" }.to { app = api }.status
                """
            ),
        )
    }

    /**
     * STILL OPEN, and a different family: a RECORD-typed thrown error does not
     * dispatch, and fails at run time.
     *
     * The MESSAGE is not the property. It has been seen two ways —
     * `Field not found: into_error_response`, the operation read as a field off
     * the record, and `Expected String for field 's'`, the Str impl handed a
     * record — and it moved between them without this test's program changing.
     * Measured NOT to be the cause: folding `catching` into the verbs (that
     * work is reverted and `http_router.mz` is byte-identical to before it), and
     * the captured-function fix in `compileCall` (turning its guard off leaves
     * the message unchanged). What did move it is unisolated; the remaining
     * suspect is the set of declarations in `http.mz`, which grew, since D-9 is
     * about picking the wrong impl among competing ones.
     *
     * So this asserts only that it fails LOUDLY, which is the property worth
     * keeping while the bug is open. A narrow assertion on one message has
     * already cost two re-investigations.
     *
     * Not about records as such: the identical shape in ONE module works (see
     * `ThrownErrorTypeRunsTest.a record thrown error is converted in the Err
     * arm`). What differs here is the stdlib path with THREE impls of
     * `into_error_response` competing (Str, HttpError, BadInput) and a receiver
     * whose record type is anonymous at the dispatch point — the shape recorded
     * as D-9, whose workaround there was to make the receiver a newtype.
     *
     * Giving `Response` an identity impl so this would subsume `catching` was
     * tried and hits the same wall, so the two combinators stay distinct.
     *
     * When this starts answering 400, invert it.
     */
    @Test fun `a record thrown error does not dispatch yet`() {
        val outcome = runCatching {
            run(
                """
                let api = group { prefix = "" }
                  .get { path = "/u/:id", handler = catching_any { handler = { m } -> do
                    let id = m.params.get_or { k = "id", def = "" }
                    if id == "ok"
                      then response_ok_text { body = "found" }
                      else throw { error = { at = ["id"], message = "no such id",
                                             expected = "a known id", found = Some { val = id } } }
                  } }
                  .handler {}
                get_request { url = "/u/nope" }.to { app = api }.status
                """
            )
        }
        val err = outcome.exceptionOrNull()
        val message = err?.message ?: ""
        assertTrue(
            err != null &&
                (message.contains("into_error_response") || message.contains("Expected String")),
            "a record thrown error must still fail loudly, in one of the two shapes seen so " +
                "far; got ${outcome.getOrNull() ?: message}",
        )
    }
}
