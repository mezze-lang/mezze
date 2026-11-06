package com.mezze.language.typechecker

import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Test

/**
 * Variant specification, §5.18: how `|` in a pattern interacts with a guard.
 *
 * `|` makes ONE arm with several alternatives, and a guard is SCOPED to the
 * alternative it is written beside:
 *
 *     Red | Pink if n > 0 -> 1        # gates Pink ONLY; Red is unconditional
 *     Red if n > 0 | Pink if n < 0    # two alternatives, two guards
 *
 * SUPERSEDED READING, recorded because this file used to pin it: the guard
 * belonged to the ARM and gated every alternative. That was a footgun — `if`
 * sits next to `Pink` and proximity argues for the narrow scope, so a reader
 * takes `Red` for unconditional. It now is.
 *
 * What survives unchanged is that the alternatives must bind the same variables
 * (V0605, pinned in OrPatternBindingTest, 13 cases): the BODY is shared, so it
 * must see one environment whichever alternative matched. `|` scopes the
 * GUARDS, not the body's bindings.
 *
 * Coverage is per alternative, by 5.10: a guarded alternative covers nothing
 * while an unguarded one still covers its pattern. The per-alternative rules
 * live in VariantSpecAltGuardTest; what stays here is the shape of `|` itself.
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

    /** 5.18 — a guard attaches to ITS OWN alternative. */
    @Test fun `a guard attaches to its own alternative`() {
        accepts(
            """
            use std::int
            pub let f = { c: | Red, Pink |, n: Int } -> match c is
              Red | Pink if n > 0 -> 1
              _ -> 0
            $MAIN
            """,
            "REVERSED from what this asserted before. The guard gates `Pink` alone; " +
                "`Red` is unconditional, which is what the layout already suggested. " +
                "The runtime half is AltGuardRuntimeTest.",
        )
    }

    /**
     * 5.18 — a guard PER ALTERNATIVE is now expressible.
     *
     * This test asserted a PARSE ERROR (`Expected '->' but got '|'`), on the
     * reasoning that a per-alternative guard would only be useful alongside
     * per-alternative BINDINGS, which V0605 forbids. That was wrong about the
     * usefulness: the bindings agree by name, and what differs is which
     * alternative's value the guard tests.
     */
    @Test fun `each alternative may carry its own guard`() {
        accepts(
            """
            use std::int
            pub let f = { c: | Red, Pink |, n: Int } -> match c is
              Red if n > 0 | Pink if n < 0 -> 1
              _ -> 0
            $MAIN
            """,
            "Two alternatives, two guards. Previously the only way to say this was " +
                "two arms with a duplicated body.",
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
     * 5.18 — coverage is PER ALTERNATIVE, so a guarded one covers nothing while
     * its unguarded sibling still covers its pattern.
     *
     * 5.10 asked of each alternative instead of the arm. This used to report
     * BOTH tags missing, because the arm counted as guarded throughout; now
     * `Red` is covered and only `Pink` is not — a sharper answer, and the one
     * that makes a following `Pink -> …` arm reachable rather than redundant.
     */
    @Test fun `a guarded alternative contributes no coverage`() {
        rejects(
            """
            use std::int
            pub let f = { c: | Red, Pink |, n: Int } -> match c is
              Red | Pink if n > 0 -> 1
            $MAIN
            """,
            "V0601",
            "With `n = 0` Pink's guard fails and nothing handles it. `Red` IS handled, " +
                "by the unguarded alternative.",
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
            "The same shape 5.12 licenses for a single pattern: a guarded alternative " +
                "followed by an unguarded arm for the same tags. So the coverage rule " +
                "never makes a program unwritable, only more explicit.",
        )
    }
}
