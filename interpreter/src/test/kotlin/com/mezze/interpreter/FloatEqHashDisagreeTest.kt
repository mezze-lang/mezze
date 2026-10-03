package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `Float`'s generated `Eq` and `Hash` DISAGREE, and a hash container keeps two
 * values that compare equal.
 *
 * IEEE says `0.0 == -0.0`; their bit patterns differ. `Eq for Float` is the native
 * comparison, which follows IEEE. `Hash for Float` is `__native_structural_hash`
 * on the runtime representation, which sees the sign bit. So:
 *
 *     0.0 == (0.0 - 1.0) * 0.0        -- True
 *     set::from_seq { xs = [0.0, that] }  -- size 2
 *
 * which is verbatim the failure the coherence rule warns about when it refuses a
 * custom `Eq` paired with a derived hash:
 *
 *     a hash container buckets by `hash` and compares by `eq`, so a custom
 *     equality left with the derived structural hash means two values that ARE
 *     equal hash apart, never meet, and are both kept.
 *
 * The rule exists to stop exactly this, and it does not catch this case, because
 * both halves are COMPILER-GENERATED. Generating them together is what the rule
 * accepts as evidence that they agree — and for `Float` they do not. The pairing
 * is enforced by provenance, and provenance is not agreement.
 *
 * ## How this was found
 *
 * Not by looking for it. Moving the eight hand-written container `Hash` impls into
 * the deriver (they are byte-identical `structural_hash` one-liners, which IS the
 * derived hash) was refused by that coherence rule, because container `Eq` is
 * hand-written and element-dispatched. Checking whether the rule's premise holds
 * where it is already satisfied produced this.
 *
 * ## Scope
 *
 * Any type whose `Eq` identifies two representations while its hash is structural.
 * `Float` is the one the stdlib ships: `0.0`/`-0.0` today, and NaN is the related
 * question (`nan != nan` under IEEE, so a NaN key can never be looked up at all —
 * not asserted here because it is a different decision, not a disagreement).
 *
 * ## This test asserts the BUG
 *
 * It pins current behaviour so a fix is visible rather than silent. When the pair
 * is made to agree — a float hash that normalises `-0.0` to `0.0`, or an `Eq` that
 * distinguishes them — the size assertion flips to 1 and this test fails, which is
 * the signal to delete it. The equality assertion must NOT change: `0.0 == -0.0`
 * is correct.
 */
class FloatEqHashDisagreeTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun run(body: String): Long =
        MezzeTestHelper.asLong(
            MezzeTestHelper.run(
                """
                use std::collections::set
                use std::float

                $body
                """.trimIndent(),
            ),
        ) ?: error("expected an Int")

    /** `(0.0 - 1.0) * 0.0` is how a negative zero is written without a literal for it. */
    @Test
    fun `positive and negative zero compare EQUAL`() {
        assertEquals(
            1L,
            run(
                """
                do
                  let neg = (0.0 - 1.0) * 0.0
                  if 0.0 == neg then 1 else 0
                """.trimIndent(),
            ),
            "IEEE equality regressed — `0.0 == -0.0` must stay True",
        )
    }

    /**
     * KNOWN WRONG, pinned: the Set keeps both. Two values the language calls equal
     * occupy two buckets, so neither can be found by the other and a deduplicating
     * container does not deduplicate.
     */
    @Test
    fun `but a Set of them keeps BOTH — the disagreement`() {
        val size = run(
            """
            do
              let neg = (0.0 - 1.0) * 0.0
              set::size { s = set::from_seq { xs = [0.0, neg] } }
            """.trimIndent(),
        )
        assertEquals(
            2L, size,
            "the Set now holds $size — if that is 1, Eq and Hash were made to agree " +
                "and this test has served its purpose; delete it",
        )
    }

    /** The consequence a user meets: a lookup by an equal key misses. */
    @Test
    fun `a membership test by an equal value misses`() {
        assertEquals(
            0L,
            run(
                """
                do
                  let neg = (0.0 - 1.0) * 0.0
                  let s = set::from_seq { xs = [neg] }
                  if set::contains { s = s, x = 0.0 } then 1 else 0
                """.trimIndent(),
            ),
            "containment by an equal key now succeeds — the pair agrees; delete this test",
        )
    }

    /** Sanity: an ordinary float round-trips, so the container itself is fine. */
    @Test
    fun `an ordinary float is found by an equal value`() {
        assertTrue(
            run(
                """
                do
                  let s = set::from_seq { xs = [1.5] }
                  if set::contains { s = s, x = 1.5 } then 1 else 0
                """.trimIndent(),
            ) == 1L,
            "a plain float could not be found in a Set — something broader is wrong",
        )
    }
}
