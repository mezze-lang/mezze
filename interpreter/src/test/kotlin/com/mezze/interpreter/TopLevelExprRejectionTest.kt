package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * A module's trailing expression is checked as strictly as a `let` binding.
 *
 * The constraint generator solves a top-level expression separately from the
 * bindings, and reports only some of what that solve finds. Twice before, the
 * set it reported was too small and the same asymmetry appeared: a program was
 * accepted as the module result and rejected once `let`-bound. Constructors
 * (`match p is Nonsense { x }`) were the first; ability obligations
 * (`sorted_set::singleton { x = Red }` with no `Ord Color`) the second.
 *
 * FIELD resolution was the third, and it was invisible for a specific reason:
 * the comment covering it said the global solver owns those failures, and the
 * global solver never sees them — `collectGlobalConstraints` strips `HasField`
 * before solving. "Deferred" meant "dropped by both", so `(1).zzz {}` as the
 * module result reached the runtime and died reading a field off a Long.
 *
 * These cases are the three together. Each asserts the SAME program is rejected
 * in both positions, which is the invariant; a future fourth kind of failure
 * that is reported for one and not the other belongs here too.
 */
class TopLevelExprRejectionTest {

    private fun rejected(source: String): String? =
        runCatching { MezzeTestHelper.run(source) }.exceptionOrNull()?.message

    private fun assertRejectedBothWays(label: String, expr: String, prelude: String = "") {
        val top = rejected("$prelude\n$expr")
        val bound = rejected("$prelude\nlet __probe = $expr\n__probe")
        assertTrue(
            bound != null,
            "$label: the `let`-bound form must be rejected — this test's premise is gone if it is not",
        )
        assertTrue(
            top != null,
            "$label: rejected when `let`-bound but ACCEPTED as the module result.\n" +
                "  bound form said: $bound\n" +
                "A trailing expression is not a place where checking stops.",
        )
        assertTrue(
            top.contains("TypeCheckError"),
            "$label: rejected as the module result, but at RUNTIME rather than by the " +
                "typechecker. Got: $top",
        )
    }

    @Test
    fun `a method that exists nowhere is rejected as the module result`() =
        assertRejectedBothWays("missing method on a scalar", "(1).zzz {}")

    @Test
    fun `a field read off a non-record is rejected as the module result`() =
        assertRejectedBothWays("field off a scalar", "n.zzz", prelude = "let n = 1")

    @Test
    fun `a method call through an opaque newtype is rejected as the module result`() =
        assertRejectedBothWays(
            "method through a newtype",
            "UserId.(1).zzz {}",
            prelude = "newtype UserId = Int",
        )

    @Test
    fun `an unknown constructor is rejected as the module result`() =
        assertRejectedBothWays(
            "unknown constructor",
            "match p is\n  Nonsense { x } -> x",
            prelude = "type P = | A { n: Int } |\nlet p = A { n = 1 }",
        )
}
