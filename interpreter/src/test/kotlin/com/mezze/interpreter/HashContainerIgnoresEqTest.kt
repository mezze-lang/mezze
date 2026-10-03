package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A hash container's notion of "same element" is REPRESENTATION IDENTITY, not the
 * element's `Eq` — and for `Float` those differ.
 *
 *     0.0 == (0.0 - 1.0) * 0.0              -- True   (IEEE: -0.0 == 0.0)
 *     set::from_seq { xs = [0.0, that] }    -- size 2
 *     set::contains { s = {-0.0}, x = 0.0 } -- False
 *
 * ## Where it actually comes from
 *
 * NOT from the `Eq`/`Hash` ability pair disagreeing — the container consults
 * neither. `HashSetStorage` buckets with `elem.hashCode()` and compares with
 * `Objects.equals`, so it is internally CONSISTENT: both halves use the Java
 * representation, and `Double` distinguishes `±0.0` in both. The disagreement is
 * between the container's notion of sameness and the LANGUAGE's `==`.
 *
 * That is deliberate for most types: representation identity IS structural
 * equality, so it agrees with every `Eq` the compiler derives, and
 * `set::from_seq` is fast because of it. It is wrong for any type whose `Eq` is
 * NOT representation equality — `Float`, whose `Eq` is the IEEE native, and any
 * newtype with a hand-written `Eq`. `from_seq_with_evidence` and
 * `contains_with_evidence` exist as the escape hatch, taking `hash` and `eq`
 * explicitly.
 *
 * So `set::from_seq`'s `with Eq 'a, Hash 'a` is the misleading part: it demands
 * evidence the native does not use.
 *
 * ## Why this does NOT block deriving container Hash in the compiler
 *
 * It was first filed as if it did. Deriving `Hash for Set 'a` changes nothing at
 * runtime, precisely because bucketing never calls the ability — so container
 * derivation cannot inherit this. Recorded because the opposite was asserted.
 *
 * ## These tests assert the CURRENT behaviour
 *
 * Pinned so a change is visible rather than silent. If the size assertion becomes
 * 1, hash containers were taught to use the element's `Eq`/`Hash` (or the float
 * hash was normalised) and these tests have served their purpose. The equality
 * assertion must NOT change: `0.0 == -0.0` is correct.
 *
 * NaN is adjacent and deliberately unasserted: `nan != nan` under IEEE makes a NaN
 * key unfindable whatever the container does — a different question, not this one.
 */
class HashContainerIgnoresEqTest {

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
    fun `but a Set of them keeps BOTH`() {
        val size = run(
            """
            do
              let neg = (0.0 - 1.0) * 0.0
              set::size { s = set::from_seq { xs = [0.0, neg] } }
            """.trimIndent(),
        )
        assertEquals(
            2L, size,
            "the Set now holds $size — if that is 1, containers now honour the element Eq " +
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
            "containment by an equal key now succeeds — containers honour Eq; delete this test",
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
