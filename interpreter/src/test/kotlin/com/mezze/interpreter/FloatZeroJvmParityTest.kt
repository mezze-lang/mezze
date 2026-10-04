package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * `Float` follows the JVM, including where the JVM is two-faced about zero.
 *
 *     0.0 == (0.0 - 1.0) * 0.0              -- True   (IEEE, like `double ==`)
 *     set::from_seq { xs = [0.0, that] }    -- size 2 (like HashSet<Double>)
 *     set::contains { s = {-0.0}, x = 0.0 } -- False  (like Double.equals)
 *
 * ## This is parity, not a defect
 *
 * A Mezze `Float` IS a JVM double, so it inherits the JVM's own split, which these
 * three lines mirror exactly:
 *
 *     0.0 == -0.0                                        // true   — primitive ==
 *     Double.valueOf(0.0).equals(Double.valueOf(-0.0))   // false  — boxed equals
 *     new HashSet<>(List.of(0.0, -0.0)).size()           // 2
 *
 * Mezze's `==` on `Float` is the IEEE native, and a hash container buckets by the
 * runtime representation — `HashSetStorage` uses `elem.hashCode()` and
 * `Objects.equals`. So equality says one thing and container identity says another,
 * for the same reason and in the same direction Java does.
 *
 * Recorded because it LOOKS like the bug the coherence rule exists to prevent — "two
 * values that ARE equal hash apart, never meet, and are both kept" — and it was
 * filed as one twice, first as "Float's generated Eq and Hash disagree" and then as
 * "hash containers ignore the element's Eq". Both framings were wrong, and the
 * second was wrong in a useful way: containers DO honour a hand-written `Eq`,
 * because every set constructor installs the element's hashing on the set (see the
 * `set::` cases in `ContainerElementInstanceTest`). `Float` is not that case. Its
 * `Eq` is generated, so the evidence rewrite does not fire, and the structural path
 * is the intended one.
 *
 * ## What would change these numbers
 *
 * Only a decision that `-0.0` and `0.0` are the SAME Mezze value — which would mean
 * normalising at the container boundary and accepting that `float::to_str` can no
 * longer show a negative zero. That is a language choice, and the choice taken is
 * JVM parity. If someone revisits it, these assertions are the ones to flip.
 *
 * NaN is the JVM's other zero-like oddity and deliberately unasserted here:
 * `nan != nan` under IEEE makes a NaN key unfindable whatever a container does.
 */
class FloatZeroJvmParityTest {

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
    fun `positive and negative zero compare EQUAL, as primitive == does`() {
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
    fun `but a Set keeps BOTH, as HashSet of Double does`() {
        val size = run(
            """
            do
              let neg = (0.0 - 1.0) * 0.0
              set::size { s = set::from_seq { xs = [0.0, neg] } }
            """.trimIndent(),
        )
        assertEquals(
            2L, size,
            "the Set now holds $size — if that is 1, Mezze stopped following the JVM here, " +
                "which is a language change and wants more than a test edit",
        )
    }

    /** The consequence a user meets: a lookup by an equal key misses. */
    @Test
    fun `a membership test by an equal value misses, as Double equals does`() {
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
            "containment by an equal key now succeeds — JVM parity on Float zero was dropped",
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
