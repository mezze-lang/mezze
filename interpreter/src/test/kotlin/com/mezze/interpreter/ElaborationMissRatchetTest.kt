package com.mezze.interpreter

import com.mezze.language.typechecker.emit.ElaborationMissCensus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * A.2's ratchet — how often the builder mints a fresh type variable because it
 * does not know a binding's type.
 *
 * A fresh variable is not a neutral placeholder: it is INDISTINGUISHABLE from a
 * legitimately polymorphic binding, so a consumer asking "is this polymorphic?"
 * gets yes either way and a binding the compiler simply failed to type takes the
 * same path as one that is genuinely generic. That is how BUG 11 returns a wrong
 * answer instead of an error.
 *
 * A.2's endpoint is that a miss is an `ElaborationError`. Turning it into one
 * today would fail every existing miss at once, so this measures first and holds
 * the line — the 0.2 ratchet's shape: measure, ratchet, then enforce.
 *
 * MEASURED, so the next person does not have to rediscover it:
 *
 *  - 125 distinct sites, all in the stdlib (async.mz, json.mz, jvm.mz, stm.mz,
 *    http.mz, channel.mz), each a match-case binding.
 *  - Ungating the structural read (dropping the `typingImplOpBody` condition in
 *    `TypedAstBuilder`) recovers 101 of them — and breaks exactly four codec
 *    tests: JsonDecodeExhaustiveTest x3 and UserDecodeAbilityTest. Those are the
 *    same four A.1 hit, because codec decode bodies depend on these bindings
 *    being fresh variables. Preferring ground types, preferring the declared
 *    shape, and a strictly-additive merge were all tried during A.1 and all
 *    broke them.
 *
 * So the remaining 101 are blocked on **A.3** (generate codec impls into the
 * resolved module, like derived impls), which removes the dependency — not on a
 * cleverer heuristic at the binding site.
 *
 * This test asserts only that the count does not GROW. It runs the census over a
 * program that exercises the stdlib; the absolute number depends on what a run
 * compiles, so the bound is deliberately loose and the value is the direction.
 */
class ElaborationMissRatchetTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        ElaborationMissCensus.reset()
    }

    @Test
    fun `the builder's fresh-var guesses do not grow`() {
        if (!ElaborationMissCensus.isEnabled) {
            // The census is off by default so it costs nothing in normal runs.
            // Without it there is nothing to ratchet, and a silent pass would be
            // misleading — say so instead.
            println(
                "elaboration census disabled (set MEZZE_ELAB_CENSUS=1 to ratchet); " +
                    "measured baseline: 19 sites for this program, 125 across a full stdlib compile",
            )
            return
        }

        // Touch enough of the stdlib for the count to be meaningful.
        MezzeTestHelper.run(
            """
            use std::collections::seq
            use std::codec::json
            let xs: [Int] = [3, 1, 2]
            xs.sort {}.size {}
            """.trimIndent(),
        )

        val total = ElaborationMissCensus.total()
        println(ElaborationMissCensus.report())
        assertTrue(
            total <= BASELINE,
            "The builder is guessing binding types MORE often than the recorded " +
                "baseline ($total > $BASELINE). Each of these mints a fresh type " +
                "variable that downstream cannot distinguish from real polymorphism " +
                "— see known-bugs.md BUG 11 for what that costs. Either supply the " +
                "type where the new site needs it, or, if the growth is genuinely " +
                "justified, re-measure and move the baseline in the same commit.\n\n" +
                ElaborationMissCensus.report(),
        )
    }

    private companion object {
        /**
         * Measured 2026-09-16 for THIS test's program — a tight bound is a real
         * ratchet, where the full-stdlib figure (125 sites) would leave room for
         * six-fold growth to pass unnoticed. Lower is better; never raise it
         * silently.
         */
        const val BASELINE = 19
    }
}
