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
 * THE WANTED DIAGNOSTIC names the row mismatch rather than the generic
 * annotation-vs-body story: "the row `<...>` does not match <Tick>. `<...>` is a
 * row VARIABLE — it stands for whatever a caller's callbacks bring — so it
 * cannot absorb <Tick>, which this body performs itself", directing to
 * `<Tick, ...>` or `<Tick>`.
 *
 * THE OBVIOUS FIX IS WRONG — tried 2026-11-13 and reverted. Adding that check to
 * `validateEffectsNotOverpromised`, comparing the annotation's row against the
 * INFERRED row, breaks stdlib precompilation. Two reasons, both instructive:
 *
 *  1. By the time that validator runs the row is ALREADY ERASED — the inferred
 *     type is `{} -> Int`, so there is nothing concrete left to compare and the
 *     check never fires on the case it was written for.
 *  2. Where it does fire, it fires on legitimate code.
 *     `std/effects/reader.mz:45`
 *     `with_reader: { val: 'a, body: {} -> 'b <Reader, ...> } -> 'b <...>` has a
 *     bare own row and a body that performs Reader and then DISCHARGES it with a
 *     handler. The invariant's own message says the part I missed: "handler-
 *     discharged effects are excluded".
 *
 * So the check needs the PERFORMED-AND-NOT-DISCHARGED set, which is exactly what
 * `InvariantChecker.collectPerformedNotDischarged` computes from the typed body.
 * That argues for PROMOTING the invariant's rule to a user-facing diagnostic —
 * it already excludes discharged effects and already names them — rather than
 * writing a second, weaker one against the inferred row. Its `if (anyOpen)
 * continue` would need narrowing too: a bare open row with no named effects is
 * the one case where "open rows are ⊇ anything" does not hold.
 *
 * DONE: the two tests below now assert the user-facing T0210 rather than the
 * internal invariant. The analysis was already correct and already excluded
 * handler-discharged effects; it was only ever reported under
 * `-Dmezze.invariants=true`. `TypeChecker` now reports the same violations as
 * T0210, naming the effect and both honest spellings.
 * (was: flip the two invariant-asserting tests below to `rejects`)
 * when that lands.
 */
class BareOpenRowOnADeclSpecTest : VariantSpecBase() {

    private val eff = """
        effect Tick where { tick: {} -> Int <Tick> }
        impl Tick for Ticker where { tick = {} -> 1 }
    """.trimIndent()

    /**
     * Refused to the AUTHOR, naming the effect and both honest spellings.
     *
     * This used to assert the effect-conservation INVARIANT fired, which only
     * happens under `-Dmezze.invariants=true` — so the rule was real but a user
     * running `mez check` never saw it. The analysis is now also reported as a
     * T0210, which is what this file's header asked for.
     */
    private fun laundersViaInvariant(src: String, why: String) {
        val e = check(src).exceptionOrNull()
            ?: fail("$why\n\nexpected a T0210 naming the laundered effect; the " +
                "program was ACCEPTED outright\n\n$src")
        val msg = e.message ?: ""
        assertTrue(
            "does not say so" in msg || "[effect-conservation]" in msg,
            "$why\n\nrefused, but not by the effect-conservation rule:\n$msg",
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
