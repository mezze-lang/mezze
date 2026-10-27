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
     * A RECORD-typed thrown error dispatches — no annotation (BUG 26, fixed).
     *
     * This was open all through the streaming work and pinned at "fails loudly",
     * because the thrown literal reached `into_error_response` as a FIELD and
     * died at run time. It took TWO fixes, and it hid behind a third thing.
     *
     *  1. Monomorphization's phantom check stopped at the EFFECT ROW, and `'e`
     *     lives only in `having 'error = 'e` — so it read as a phantom type
     *     param and was filled with the stable `Int` placeholder. The
     *     constraint then resolved as `IntoErrorResponse Int`, no impl claimed
     *     it, the evidence was dropped, and the specialization was emitted
     *     ANYWAY with the op still a field read. The call site took that mono
     *     rewrite and returned before injecting any dictionary. Same blind spot
     *     as item 2 in this class's header, in a second walker.
     *  2. With a dictionary now being injected, it resolved to the WRONG impl:
     *     resolution descends into record FIELD types, and `message: Str` found
     *     `impl IntoErrorResponse for Str` — `Expected String for field 's'`.
     *     A bare literal has to earn its alias's identity by ADOPTION, and
     *     adoption compares field types exactly so it cannot run while they are
     *     still variables; it now runs where they are first known, in
     *     `resolveOpHashFromConcreteTypes`.
     *
     * Removing either half brings this test back. BUG 26 records both, and two
     * earlier attempts at (2) alone that could not have worked.
     *
     * What hid it: `MezzeTestHelper` holds a SHARED static `TypeChecker`, so a
     * program run after one that already compiled this route passed. The same
     * source failed alone and passed in a class whose earlier test warmed it —
     * which is why the first four probe shapes all answered 400 and none of them
     * was evidence.
     *
     * 400 is `impl IntoErrorResponse for BadInput`'s own status, and the body
     * names the field, so this also shows the right impl ran rather than some
     * other one that happens to answer.
     */
    @Test fun `a record thrown error dispatches to its alias's impl`() {
        assertEquals(
            400L,
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
            ),
        )
    }
}
