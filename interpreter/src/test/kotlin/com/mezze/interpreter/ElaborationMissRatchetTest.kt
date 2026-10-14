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
 * IT IS NOW ZERO, and this is therefore an INVARIANT rather than a ratchet.
 *
 * The history, because the numbers in it were wrong in a way worth recording.
 * This file used to say:
 *
 *   - 125 distinct sites, all in the stdlib, each a match-case binding.
 *   - Ungating the structural read recovers 101 of them and breaks exactly FOUR
 *     codec tests, because "codec decode bodies depend on these bindings being
 *     fresh variables".
 *   - So the rest are blocked on A.3: generate codec impls into the resolved
 *     module, like derived impls.
 *
 * Three corrections. It was FIVE tests, not four — DecodeConstraintAtCallSiteTest
 * postdated the note. Nothing ever depended on the bindings being fresh
 * variables: the dict prescan decided a call needed evidence by asking whether
 * its RECEIVER was a variable, and `decode` dispatches on the decode TARGET, so a
 * typed binder made the receiver concrete and the call was skipped — the
 * enclosing `from_json` compiled with no dictionary at all. And A.3 was not
 * needed: codec generation never had to move phase. The two decoders already
 * existed as separate generated impls and each call site already injected the
 * right one; one impl simply never got its slot.
 *
 * Fixed one layer down, in `DictEvidence.scanExprForVarTypeMethods`. The gate and
 * the `matchCaseBindings` exclusion both came out with it, and the count went to
 * zero — not to 24.
 *
 * The lesson for the next person is the one that cost three attempts here: the
 * comment named the binding site, so every fix tried to preserve freshness
 * instead of asking why freshness mattered. It mattered only by accident — a
 * variable receiver happened to satisfy a check that was never about the
 * receiver.
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
            "The builder guessed a binding type $total time(s); the bound is " +
                "$BASELINE. Each guess mints a fresh type variable that downstream " +
                "cannot distinguish from real polymorphism — see known-bugs.md BUG 11 " +
                "for what that costs. This reached ZERO once, so a new miss is a " +
                "regression with a cause, not a population to re-baseline: find what " +
                "stopped supplying the type. Raising the bound is the last resort and " +
                "belongs in the same commit as the reason.\n\n" +
                ElaborationMissCensus.report(),
        )
    }

    private companion object {
        /**
         * ZERO, measured after the dict-prescan fix. Was 19 for this program
         * (125 across a full stdlib compile) while the structural read was gated
         * to impl-op bodies.
         *
         * At zero the bound stops being a ratchet and becomes the property A.2
         * names as its endpoint: elaboration finishes knowing every type. Keep it
         * at zero — a non-zero count now has a specific cause to find.
         */
        const val BASELINE = 0
    }
}
