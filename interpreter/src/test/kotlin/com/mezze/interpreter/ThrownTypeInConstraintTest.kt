package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A constraint variable that lives ONLY in the effect row still gets its
 * dictionary (BUG 26).
 *
 * `catching_any`'s `'e` is the thrown error type:
 *
 *     { handler: { m: Matched } -> 'r <Exception having 'error = 'e, ...> }
 *     with IntoResponse 'r, IntoErrorResponse 'e -> …
 *
 * `'e` appears in no parameter, no return and no record — only in
 * `having 'error = 'e`. Monomorphization's phantom check walked parameters,
 * returns, records and applications and STOPPED at the effect row, so `'e` read
 * as a phantom type param: declared but unreferenced, and therefore safe to fill
 * with a stable `Int` placeholder on the grounds that "substitution over an
 * absent name is a no-op, so any placeholder yields the same specialized body".
 *
 * That is false for a name a `with` clause mentions. The placeholder reached
 * constraint resolution as `IntoErrorResponse Int`, which no impl claims, so the
 * evidence was dropped — and the specialization was emitted anyway, with
 * `error.into_error_response {}` still a raw FIELD READ. The call site then took
 * the mono rewrite and returned before injecting any dictionary, and the program
 * died at run time with `Field not found: into_error_response`.
 *
 * This is the same effect-row blind spot the class header of
 * `HttpCatchingAnyTest` records for the constraint BINDER, in a second walker.
 *
 * Written against a user-defined ability rather than the router, so it pins the
 * compiler rule and not the stdlib's spelling of it.
 *
 * WHICH CASE FAILS WITHOUT THE FIX, measured by removing each half: the thrown
 * `Str` one. At this size mono's own structural recovery
 * (`anonymousRecordMatchesDeclaredRecord`, added for #333) rescues the two
 * RECORD cases — an anonymous record is matched against the impls' declared
 * record targets — but no such recovery exists for a primitive, so `Shape Int`
 * found nothing and the op stayed a field read: `Expected record for field
 * 'shape', got: String`. The record half of the bug needs the router's shape,
 * where `catching_any` carries two constraints; `HttpCatchingAnyTest`'s
 * `a record thrown error dispatches to its alias's impl` is that case and fails
 * without either half. Both are kept: they break on different halves.
 *
 * There is deliberately NO "callback that never throws" case.
 * `<Exception having 'error = 'e, ...>` is a LOWER bound — a named effect before
 * `...` is mandatory — so a pure callback does not satisfy it and the checker
 * says so:
 *
 *     expected { f: { x: Int } -> Str <Exception having ''error = 'p56, ...> }
 *     but got { f: { x: Int } -> Str }
 *
 * That is the rule working, not a gap. Expressing "may throw, and if it does,
 * this type" would need a row form the language does not have, and the decision
 * on record is that it is not getting one.
 */
class ThrownTypeInConstraintTest {
    /** The prelude is already trimmed; only the body needs it. Interpolating a
     *  trimmed block into another raw string and trimming the WHOLE thing
     *  leaves the body indented relative to the prelude, which a `do` block's
     *  column rule rejects. */
    private fun run(body: String): Any = MezzeTestHelper.run("$prelude\n${body.trimIndent()}")

    private val prelude = """
        use std::effects::exception::{ throw, ExceptionToResult }

        ability Shape for 'e where { shape: { it: 'e } -> Str }
        type Bad = { why: Str }
        impl Shape for Bad where { shape = { it } -> "BAD" }
        impl Shape for Str where { shape = { it } -> "STR" }

        let describe
          : { f: { x: Int } -> Str <Exception having 'error = 'e, ...> }
          with Shape 'e
          -> Str <...>
          =
          { f } -> do
            let outcome = perform ExceptionToResult in do
              Ok { val = f { x = 1 } }
            match outcome is
              Ok { val } -> val
              Err { error } -> error.shape {}
    """.trimIndent()

    /**
     * A BARE record literal: its alias must be found with no annotation.
     *
     * Passes without the fix too, via mono's #333 structural recovery — kept
     * because it is the case the language promises (nothing needs annotating)
     * and the router's version of it, in `HttpCatchingAnyTest`, does break.
     */
    @Test fun `a thrown bare record reaches its alias's impl`() {
        assertEquals(
            "BAD",
            run(
                """
                describe { f = { x } -> throw { error = { why = "no" } } }
                """
            ).toString(),
        )
    }

    /**
     * The same value ANNOTATED. This always worked, and that is what said the
     * identity was the missing half: the annotation supplied what the literal
     * had to earn. Kept so the fix cannot trade one for the other.
     */
    @Test fun `a thrown annotated record reaches it too`() {
        assertEquals(
            "BAD",
            run(
                """
                describe { f = { x } -> do
                  let b: Bad = { why = "no" }
                  throw { error = b } }
                """
            ).toString(),
        )
    }

    /**
     * A thrown Str reaches the Str impl — THE case that breaks without the fix.
     *
     * `Shape Int` (the phantom placeholder) claims no impl, and unlike a record
     * a primitive has no structural recovery to fall back on, so the evidence
     * was dropped, the specialization went out with `error.shape {}` as a raw
     * field read, and it died on the value it was handed:
     * `Expected record for field 'shape', got: String`.
     */
    @Test fun `a thrown Str reaches the Str impl`() {
        assertEquals(
            "STR",
            run(
                """
                describe { f = { x } -> throw { error = "plain" } }
                """
            ).toString(),
        )
    }
}
