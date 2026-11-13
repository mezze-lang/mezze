package com.mezze.language.typechecker

import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Test

/**
 * A bare `<...>` on a DECLARATION whose body performs a concrete effect launders
 * it — and the only thing that notices is an invariant users never run.
 *
 * `<...>` is a row VARIABLE. On a callback PARAMETER it is exactly right, because
 * the row belongs to whoever supplies the callback, and every stdlib HOF is
 * written that way. On a declaration whose own body performs Tick it is a
 * different claim — "for any row r, I perform r" — which that body does not
 * satisfy.
 *
 * MEASURED through `mez check` (where invariants are OFF):
 *
 *     pub let f: {} -> Int <...> = {} -> Tick.tick {}
 *     pub let caller: {} -> Int = {} -> f {}
 *
 *     pub f : {} -> Int          <- the row vanished, not even left OPEN
 *     pub caller : {} -> Int     <- a PURE function that performs Tick
 *
 * An open row and NO row are different claims, and the emitted type made the
 * stronger one. A pure caller then cannot be stopped.
 *
 * WHAT THIS TEST PINS, and why it is not a `rejects`: the laundering IS detected,
 * by `InvariantChecker`'s `[effect-conservation]` rule — but it arrives as an
 * `InvariantViolation`, which is a COMPILER-BUG signal rather than a diagnostic,
 * and the checker is ON only in the test gates (`-Dmezze.invariants=true`) and
 * deliberately OFF in the CLI: "a violation is a COMPILER bug, which CI already
 * catches."
 *
 * Here it is not a compiler bug — it is a program a user can write. So the gap
 * is that the wrong channel reports it, and in the channel users actually run,
 * nothing does.
 *
 * THE FIX, when it is taken: reject at the annotation with T0210, the way
 * `validateEffectsNotOverpromised` handles the opposite direction.
 * `AnnotationValidator.hasBareOpenRow` already identifies the shape and is
 * currently "used only to improve a diagnostic". Evidence that rejecting is
 * safe: this same invariant runs over the ENTIRE stdlib in the interpreter gate
 * and passes, so nothing legitimate depends on the laundering. Flip the two
 * tests below to `rejects(..., "T0210", ...)` at that point.
 */
class BareOpenRowOnADeclSpecTest : VariantSpecBase() {

    private val eff = """
        effect Tick where { tick: {} -> Int <Tick> }
        impl Tick for Ticker where { tick = {} -> 1 }
    """.trimIndent()

    /** The effect-conservation invariant fired, naming the laundered effects. */
    private fun laundersViaInvariant(src: String, why: String) {
        val e = check(src).exceptionOrNull()
            ?: fail("$why\n\nexpected the effect-conservation invariant to fire; the " +
                "program was ACCEPTED outright\n\n$src")
        val msg = e.message ?: ""
        assertTrue(
            "[effect-conservation]" in msg && "launders" in msg,
            "$why\n\nrefused, but not by the effect-conservation invariant:\n$msg",
        )
    }

    @Test fun `a bare open row on a decl launders - caught only by the invariant`() {
        laundersViaInvariant(
            """
            use std::int
            $eff
            pub let f: {} -> Int <...> = {} -> Tick.tick {}
            $MAIN
            """.trimIndent(),
            "A body performing Tick concretely does not satisfy \"for any row r\". " +
                "The emitted row is NOTHING — `pub f : {} -> Int` — so this should " +
                "be a T0210 at the annotation, not an internal invariant.",
        )
    }

    @Test fun `a pure caller of such a function launders too`() {
        laundersViaInvariant(
            """
            use std::int
            $eff
            pub let f: {} -> Int <...> = {} -> Tick.tick {}
            pub let caller: {} -> Int = {} -> f {}
            $MAIN
            """.trimIndent(),
            "THE CONSEQUENCE, and why the first case matters. With the row erased at " +
                "the definition a pure caller cannot be stopped: `caller` performs " +
                "Tick with nothing in its type saying so. The launder family's shape, " +
                "reached through an annotation rather than an intermediary value.",
        )
    }

    @Test fun `a bare open row on a CALLBACK PARAMETER stays correct`() {
        accepts(
            """
            use std::int
            $eff
            pub let apply: { f: {} -> Int <...> } -> Int <...> = { f } -> f {}
            pub let run: {} -> Int <*> = {} ->
              perform Ticker in apply { f = {} -> Tick.tick {} }
            $MAIN
            """,
            "THE MIRROR CASE. On a parameter `<...>` is exactly right — the row is " +
                "the caller's to decide, and every stdlib HOF is written this way. " +
                "Any rule for the declaration case must leave this one alone.",
        )
    }

    @Test fun `naming the effect alongside the row var is accepted`() {
        accepts(
            """
            use std::int
            $eff
            pub let f: {} -> Int <Tick, ...> = {} -> Tick.tick {}
            $MAIN
            """,
            "The honest spelling: \"Tick, which I perform, plus whatever flows " +
                "through\". Refusing the bare form is only half a rule without this " +
                "staying writeable.",
        )
    }
}
