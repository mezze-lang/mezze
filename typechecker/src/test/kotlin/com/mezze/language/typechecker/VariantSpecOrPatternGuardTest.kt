package com.mezze.language.typechecker

import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Test

/**
 * Variant specification, §5.18: how `|` in a pattern interacts with a guard.
 *
 * `|` makes ONE arm with several alternatives, and a guard belongs to the ARM,
 * not to an alternative. So there is exactly one guard and it applies to every
 * alternative:
 *
 *     Red | Pink if n > 0 -> 1        # one guard, covering BOTH
 *     Red if n > 0 | Pink if n < 0    # not expressible
 *
 * That is the only coherent reading, and the or-pattern BINDING rule is why. An
 * or-pattern's alternatives must bind the same variables (V0605, pinned in
 * OrPatternBindingTest), so a guard reading `n` finds the same `n` whichever
 * alternative matched. A per-alternative guard would need per-alternative
 * bindings to be worth having, and those are forbidden — two arms express it.
 *
 * Coverage then follows 5.10 unchanged: the guard can fail, so a guarded
 * or-pattern covers NONE of its alternatives, not some of them.
 *
 * Bindings themselves are OrPatternBindingTest's subject (13 cases) and are not
 * re-pinned here.
 */
class VariantSpecOrPatternGuardTest : VariantSpecBase() {

    /**
     * A parse failure, which carries no [TypeCheckError] code to assert on —
     * the grammar refuses the shape before any rule is consulted.
     */
    private fun rejectsAtParse(src: String, needle: String, why: String) {
        val r = check(src.trimIndent())
        if (r.isSuccess) fail("$why\n\nexpected: a parse error\n     got: accepted")
        val msg = r.exceptionOrNull()?.message ?: ""
        assertTrue(
            msg.contains("Parse error") && msg.contains(needle),
            "$why\n\nexpected a parse error mentioning `$needle`, got: $msg",
        )
    }

    // ===================================== 5.18 `|` is one arm, many alternatives

    /** 5.18 — an UNGUARDED or-pattern covers every alternative it names. */
    @Test fun `an unguarded or-pattern covers all its alternatives`() {
        accepts(
            """
            use std::int
            pub let f = { c: | Red, Pink | } -> match c is
              Red | Pink -> 1
            $MAIN
            """,
            "No wildcard and no second arm, so the match is exhaustive only if the " +
                "or-pattern covers both tags — which is what makes `|` worth having over " +
                "two arms with the same body.",
        )
    }

    /** 5.18 — and with three, since nothing about the rule is pairwise. */
    @Test fun `an or-pattern takes any number of alternatives`() {
        accepts(
            """
            use std::int
            pub let f = { c: | Red, Pink, Blue | } -> match c is
              Red | Pink | Blue -> 1
            $MAIN
            """,
            "`|` is n-ary, not a binary operator applied twice with a different meaning.",
        )
    }

    // ===================================== 5.18 one guard, over the whole arm

    /** 5.18 — ONE guard covers EVERY alternative. */
    @Test fun `a guard applies to the whole or-pattern`() {
        accepts(
            """
            use std::int
            pub let f = { c: | Red, Pink |, n: Int } -> match c is
              Red | Pink if n > 0 -> 1
              _ -> 0
            $MAIN
            """,
            "The guard attaches to the arm, so it runs whichever alternative matched. " +
                "The wildcard is what makes this exhaustive — see the coverage test below.",
        )
    }

    /**
     * 5.18 — a guard PER ALTERNATIVE is not expressible, and deliberately.
     *
     * It would only be useful alongside per-alternative BINDINGS, which V0605
     * forbids: the alternatives must bind the same variables. Two arms say it
     * already, with each guard next to the pattern it tests.
     */
    @Test fun `an alternative may not carry its own guard`() {
        rejectsAtParse(
            """
            use std::int
            pub let f = { c: | Red, Pink |, n: Int } -> match c is
              Red if n > 0 | Pink if n < 0 -> 1
              _ -> 0
            $MAIN
            """,
            "'->'",
            "The grammar takes the guard as the end of the arm, so the second `|` has " +
                "nowhere to attach. Refused at the arrow rather than silently reading the " +
                "first guard as covering both.",
        )
    }

    /** 5.18 — the guard reads a binding the alternatives share. */
    @Test fun `the guard reads a binding shared by every alternative`() {
        accepts(
            """
            use std::int
            pub let f = { c: | A { v: Int }, B { v: Int } |, n: Int } -> match c is
              A { v = m } | B { v = m } if m > 0 -> m
              _ -> 0
            $MAIN
            """,
            "This is the case that justifies one shared guard: `m` is bound by both " +
                "alternatives (V0605 requires it), so the guard means the same thing " +
                "whichever one matched. Without the same-bindings rule the guard would " +
                "read a name that might not exist.",
        )
    }

    // ===================================== 5.18 coverage, by 5.10

    /**
     * 5.18 — a guarded or-pattern covers NOTHING, not some of its alternatives.
     *
     * 5.10 applied to every alternative at once. The diagnostic names them all,
     * which is the right report: the guard can fail, so after this arm both tags
     * are still unhandled.
     */
    @Test fun `a guarded or-pattern contributes no coverage`() {
        rejects(
            """
            use std::int
            pub let f = { c: | Red, Pink |, n: Int } -> match c is
              Red | Pink if n > 0 -> 1
            $MAIN
            """,
            "V0601",
            "With `n = 0` the guard fails and no arm matches. Partial credit would be " +
                "worse than none — covering 'some' of an or-pattern's alternatives has no " +
                "meaning, since the guard does not know which one matched.",
        )
    }

    /** 5.18, the sound form — and it is expressible, so nothing is lost. */
    @Test fun `a guarded or-pattern plus an unguarded fallback`() {
        accepts(
            """
            use std::int
            pub let f = { c: | Red, Pink |, n: Int } -> match c is
              Red | Pink if n > 0 -> 1
              Red | Pink -> 0
            $MAIN
            """,
            "The same shape 5.12 licenses for a single pattern: a guarded arm followed " +
                "by an unguarded one for the same tags. So the coverage rule never makes " +
                "a program unwritable, only more explicit.",
        )
    }
}
