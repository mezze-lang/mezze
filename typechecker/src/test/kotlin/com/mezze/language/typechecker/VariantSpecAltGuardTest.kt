package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Variant specification, §5.18: `|` SCOPES a guard.
 *
 * A guard belongs to the ALTERNATIVE it is written beside, not to the arm:
 *
 *     Red { n } if n > 0 | Pink { n } if n < 5 -> …
 *
 * Two alternatives, two guards, and two separate `n` bindings -- each guard
 * reads the `n` of the pattern next to it. The arm matches when any alternative
 * matches AND that alternative's own guard holds.
 *
 * REPLACES an arm-level reading, which was a footgun: `Red | Pink if n > 0`
 * attaches `if` to `Pink` by proximity while gating both, so a reader would take
 * `Red` for unconditional. It now is.
 *
 * Coverage follows per alternative. An unguarded alternative still covers its
 * pattern and a guarded one covers nothing (5.10), so `Red | Pink if n > 0`
 * covers `Red` alone -- a sharper answer than the old "covers neither".
 *
 * Alternatives must still bind the same NAMES and TYPES (V0605, and the
 * cross-alternative equalities): the BODY is shared, so it has to see one
 * environment whichever alternative matched. What `|` scopes is the guards.
 */
class VariantSpecAltGuardTest : VariantSpecBase() {

    /** 5.18 — a guard per alternative parses and checks. */
    @Test fun `each alternative may carry its own guard`() {
        accepts(
            """
            use std::int
            pub let f = { c: | Red, Pink |, n: Int } -> match c is
              Red if n > 0 | Pink if n < 5 -> 1
              _ -> 0
            $MAIN
            """,
            "This was a parse error before -- `Expected '->' but got '|'` -- so the " +
                "only way to guard two tags differently was two arms with duplicated " +
                "bodies.",
        )
    }

    /** 5.18 — each guard reads ITS OWN alternative's binding. */
    @Test fun `each alternative's guard reads its own binding`() {
        accepts(
            """
            use std::int
            pub let f = { c: | Red { n: Int }, Pink { n: Int } | } -> match c is
              Red { n } if n > 0 | Pink { n } if n < 5 -> n
              _ -> 0
            $MAIN
            """,
            "Two separate `n`s, each scoped to the pattern beside its guard. The BODY " +
                "sees whichever alternative matched, which is why both must bind the " +
                "name (V0605).",
        )
    }

    /** 5.18 — a guard on only the second alternative leaves the first unguarded. */
    @Test fun `an unguarded alternative still covers its pattern`() {
        accepts(
            """
            use std::int
            pub let f = { c: | Red, Pink |, n: Int } -> match c is
              Red | Pink if n > 0 -> 1
              Pink -> 2
            $MAIN
            """,
            "EXHAUSTIVE, and it is the test that forced coverage to be per-alternative " +
                "rather than per-arm: `Red` is covered by the unguarded first " +
                "alternative and `Pink` by the second arm. Treating the whole arm as " +
                "guarded would report `Red` missing.",
        )
    }

    /** 5.18 — and a guarded alternative covers nothing. */
    @Test fun `a guarded alternative contributes no coverage`() {
        rejects(
            """
            use std::int
            pub let f = { c: | Red, Pink |, n: Int } -> match c is
              Red | Pink if n > 0 -> 1
            $MAIN
            """,
            "V0601",
            "`Red` is covered by the unguarded alternative; `Pink` is not, because its " +
                "guard can fail. So this is still non-exhaustive -- but for ONE tag now, " +
                "not two.",
        )
    }

    /** 5.18 — a single pattern's guard is unchanged, and must stay so. */
    @Test fun `a single pattern with a guard is unaffected`() {
        rejects(
            """
            use std::int
            pub let f = { c: | Red, Pink |, n: Int } -> match c is
              Red if n > 0 -> 1
              Pink -> 2
            $MAIN
            """,
            "V0601",
            "One alternative means the alternative's guard IS the arm's, so this is " +
                "represented exactly as before and every existing reader stays right. " +
                "Still non-exhaustive because the guarded `Red` covers nothing.",
        )
    }

    /** 5.18 — a guard is still required to be Bool, per alternative. */
    @Test fun `an alternative's guard must be Bool`() {
        rejects(
            """
            use std::int
            pub let f = { c: | Red, Pink |, n: Int } -> match c is
              Red if n | Pink if n < 5 -> 1
              _ -> 0
            $MAIN
            """,
            "T0201",
            "Each alternative's guard is checked on its own, so the Bool requirement " +
                "has to be imposed per alternative too -- not inherited from an arm-level " +
                "check that no longer runs.",
        )
    }

    /** 5.18 — three alternatives, mixed. */
    @Test fun `three alternatives with mixed guards`() {
        accepts(
            """
            use std::int
            pub let f = { c: | Red, Pink, Blue |, n: Int } -> match c is
              Red if n > 0 | Pink | Blue if n < 5 -> 1
              _ -> 0
            $MAIN
            """,
            "Guards attach to the first and third alternatives and not the second. " +
                "Nothing about the rule is pairwise.",
        )
    }

    /** 5.18 — a guard may not read a binding from a SIBLING alternative. */
    @Test fun `a guard cannot read a sibling alternative's binding`() {
        rejects(
            """
            use std::int
            pub let f = { c: | Red { n: Int }, Pink { m: Int } | } -> match c is
              Red { n } if n > 0 | Pink { m } if m < 5 -> 1
              _ -> 0
            $MAIN
            """,
            "V0605",
            "The alternatives bind different NAMES, which is refused for the body's " +
                "sake before scoping is even consulted — the body cannot see an `n` that " +
                "only one alternative provides. Scoping the guards does not relax that.",
        )
    }
}
