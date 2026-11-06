package com.mezze.language.typechecker

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * Variant specification, §9.6: the arms define the group AT ANY DEPTH.
 *
 * 5.9 makes the arms the evidence for an unannotated scrutinee, because there is
 * no group yet. That held at the top level and stopped one level down: a group
 * inside a constructor's PAYLOAD took the last arm's value and reported the
 * first arm's tag missing from it.
 *
 *     Some { val = Red } -> 1
 *     Some { val = Pink } -> 2     # T0201: 'Red' not found in | Pink |
 *
 * Depth was never a reason for the rule to stop. A payload is a value inside a
 * value, and `Option | Red, Pink |` is what the arms jointly say the scrutinee
 * can be.
 *
 * WHY IT STOPPED THERE. The union lives in `mergeVariantConstructors`, which the
 * solver consults on an `Equality` carrying `isMatchCaseContext` — the scrutinee's
 * own constraint. A payload field is reconciled by `solveHasField`, which unifies
 * the two field types directly, so the flag never reached it and two arms met as
 * a plain mismatch. The policy is now shared (`mergeVariantPair`) and a record
 * pattern's field constraints carry `isPatternContext`, so every refusal is
 * shared too: the identity veto that stops a foreign module's variant being
 * laundered into another's row still guards the nested path.
 *
 * NOT changed, and the distinction matters: a `let`-bound value is still fixed at
 * its binding (4.1), so an arm naming a tag its group does not hold is an EXTRA
 * arm rather than a widening (5.5). Depth does not relax that either — see the
 * last test.
 */
class VariantSpecArmDepthTest : VariantSpecBase() {

    /** 9.6 — the arms union inside a payload, for an inferred scrutinee. */
    @Test fun `arms union inside a constructor payload`() {
        accepts(
            """
            use std::int
            use std::option
            pub let f = { v } -> match v is
              Some { val = Red } -> 1
              Some { val = Pink } -> 2
              None -> 3
            $MAIN
            """,
            "Two arms name two tags at the same payload position, so the payload's " +
                "group is their union. Refusing this made the one-level-down case obey a " +
                "different rule from the top level, for no reason anybody chose.",
        )
    }

    /** 9.6 — and the inferred type says so. */
    @Test fun `the payload group is the union of the arms`() {
        assertEquals(
            "{ v: Option | Red, Pink | } -> Int",
            printedType(
                """
                use std::int
                use std::option
                pub let f = { v } -> match v is
                  Some { val = Red } -> 1
                  Some { val = Pink } -> 2
                  None -> 3
                $MAIN
                """.trimIndent(),
                "f",
            ),
            "Accepting the program is not enough — the PARAMETER has to carry both " +
                "tags, or a caller passing `Some { val = Pink }` would be refused by a " +
                "function that plainly handles it.",
        )
    }

    /** 9.6 — the top-level control, which always worked. */
    @Test fun `arms union at the top level`() {
        assertEquals(
            "{ c: | Red, Pink | } -> Int",
            printedType(
                """
                use std::int
                pub let g = { c } -> match c is
                  Red -> 1
                  Pink -> 2
                $MAIN
                """.trimIndent(),
                "g",
            ),
            "The contrast that made the depth case a defect rather than a design " +
                "choice: the same two arms, one level up.",
        )
    }

    /** 9.6 — deeper still, since nothing about the rule is about one level. */
    @Test fun `arms union two levels down`() {
        accepts(
            """
            use std::int
            use std::option
            pub let f = { v } -> match v is
              Some { val = Some { val = Red } } -> 1
              Some { val = Some { val = Pink } } -> 2
              Some { val = None } -> 3
              None -> 4
            $MAIN
            """,
            "If the union were special-cased to the first payload this would fail, " +
                "which is how a depth fix usually goes wrong.",
        )
    }

    /** 9.6 — three arms, so the union is not a pairwise accident. */
    @Test fun `three arms union inside a payload`() {
        accepts(
            """
            use std::int
            use std::option
            pub let f = { v } -> match v is
              Some { val = Red } -> 1
              Some { val = Pink } -> 2
              Some { val = Blue } -> 3
              None -> 4
            $MAIN
            """,
            "The merge runs pairwise as constraints are solved, so a third tag has to " +
                "join a group that is already a union.",
        )
    }

    /**
     * 9.6 and 5.5 together — a LET-BOUND value is still fixed at its binding,
     * at depth exactly as at the top level.
     *
     * This is the case the old wording of 9.6 used, which made it read as
     * contradicting 5.5. The refusal is correct: `v`'s group is decided at the
     * binding (4.1), so the second arm names a tag it does not hold.
     */
    @Test fun `a let-bound value is not widened by an arm at depth`() {
        rejects(
            """
            use std::int
            use std::option
            pub let a = {} -> do
              let v = Some { val = Red }
              match v is
                Some { val = Red } -> 1
                Some { val = Pink } -> 2
                None -> 3
            $MAIN
            """,
            "T0201",
            "A pattern READS a value; only a result produces one. Widening here would " +
                "contradict 4.1, and the fix for the inferred case must not reach this " +
                "one — which is why it is pinned beside it.",
        )
    }
}
