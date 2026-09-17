package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * T1.4, runtime half: a hash container CAN bucket by the element's own instance.
 *
 * Every storage tier buckets by the runtime representation — `Objects.hashCode` on a
 * Long, a String, a record. That is exactly right when the element's `Eq` is the
 * compiler's derived structural one, which is the common case and keeps the
 * specialised tiers and their iteration order. It is wrong when the element wrote its
 * own: `P` below compares mod 10, so `P.(1) == P.(11)`, and a newtype is erased — the
 * structural tiers see the payloads `1` and `11`, bucket them apart, and keep both.
 *
 * `EvidenceSetStorage` is the tier that asks the element instead, and the
 * `*_with_evidence` entry points install it. Installed at the first operation that
 * HAS an element: an empty set has nothing to mis-bucket, so there is nothing to
 * convert until then. It is never promoted away from — re-tiering to `LongSetStorage`
 * because the payloads happen to be longs would silently restore the bucketing this
 * replaces.
 *
 * These tests call the evidence form DIRECTLY. Choosing it from the element type is
 * the compiler's half of T1.4 and is separate; this fixes the mechanism in place so
 * that half has something correct to select.
 */
class EvidenceSetStorageTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    /** `P` compares and hashes MOD 10, so `P.(1)` and `P.(11)` are one element by its
     *  instance and two by their representation. That makes "did the container ask?"
     *  observable rather than a coin flip. */
    private val head = """
        use std::collections::set
        use std::abilities::compare::{ Eq, Hash }
        newtype P = Int
        impl Hash for P where {
          hash = { it } -> match it is P.(a) -> a % 10
        }
        impl Eq for P where {
          eq = { it, r } -> match it is
            P.(a) -> match r is
              P.(b) -> a % 10 == b % 10,
          neq = { it, r } -> match it is
            P.(a) -> match r is
              P.(b) -> a % 10 != b % 10
        }
        let h: { it: P } -> Int = { it } -> it.hash {}
        let e: { a: P, b: P } -> Bool = { a, b } -> a == b
    """.trimIndent()

    private fun run(body: String) = MezzeTestHelper.run("$head\n$body")

    @Test
    fun `from_seq collapses elements equal by the element's Eq`() {
        assertEquals(1L, run("set::from_seq_with_evidence { xs = [P.(1), P.(11)], hash = h, eq = e }.size {}"))
    }

    @Test
    fun `contains finds an element equal by the element's Eq`() {
        assertEquals(
            true,
            run(
                "set::contains_with_evidence { " +
                    "s = set::from_seq_with_evidence { xs = [P.(1)], hash = h, eq = e }, " +
                    "x = P.(11), hash = h, eq = e }",
            ),
        )
    }

    @Test
    fun `insert installs the hashing on a set that had none`() {
        // The empty set has nothing to mis-bucket, so the first insert is where the
        // element's instance arrives — no construction-time capture needed.
        assertEquals(
            1L,
            run(
                """
                let s0 = set::insert_with_evidence { s = set::empty {}, x = P.(1), hash = h, eq = e }
                let s1 = set::insert_with_evidence { s = s0, x = P.(11), hash = h, eq = e }
                s1.size {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `remove removes the element equal by the instance, not the representation`() {
        assertEquals(
            0L,
            run(
                "set::remove_with_evidence { " +
                    "s = set::from_seq_with_evidence { xs = [P.(1)], hash = h, eq = e }, " +
                    "x = P.(21), hash = h, eq = e }.size {}",
            ),
        )
    }

    @Test
    fun `elements that genuinely differ are still kept apart`() {
        // The instance must be ASKED, not assumed to say yes.
        assertEquals(2L, run("set::from_seq_with_evidence { xs = [P.(1), P.(2)], hash = h, eq = e }.size {}"))
        assertEquals(
            false,
            run(
                "set::contains_with_evidence { " +
                    "s = set::from_seq_with_evidence { xs = [P.(1)], hash = h, eq = e }, " +
                    "x = P.(2), hash = h, eq = e }",
            ),
        )
    }

    @Test
    fun `the structural path is untouched`() {
        // Elements whose `Eq` is derived keep the specialised tiers. This is the
        // reason the evidence form is a separate entry point rather than the default:
        // passing closures unconditionally would put every set on the generic tier.
        assertEquals(2L, MezzeTestHelper.run("use std::collections::set\nset::from_seq { xs = [1, 2] }.size {}"))
        assertEquals(3L, MezzeTestHelper.run("use std::collections::set\nset::from_seq { xs = [1, 2, 2, 3] }.size {}"))
    }
}
