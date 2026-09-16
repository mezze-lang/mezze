package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * D.2 — the systematic audit of fused sinks against the element's own instance.
 *
 * Three bugs in this effort were the same defect: a fused fast path substituting
 * a primitive operation for one that needed an instance. BUG 8 (the fused LONG
 * pipeline firing on a seq of records), BUG 7's sorted-container half
 * (`Min`/`Max` fusing over a container that owns its ordering), and BUG 10 (the
 * `_by` sinks comparing the KEY with `DirectLessThan`). Three is a pattern, so
 * this sweeps the rest rather than waiting for a fourth.
 *
 * RESULT: no further instances. Every op below honours the element's instance.
 * That is a negative result, and it is kept as a gate rather than discarded —
 * the fixes it covers are recent, and nothing else pins them across the whole
 * matrix.
 *
 * The method matters more than the verdict. Each element type has an instance
 * that DISAGREES with the structural/primitive default, so a fused path that
 * ignores it produces a different answer rather than the same one:
 *
 *  - `N` compares MOD 10, so `N.(1) == N.(11)` by its instance and not
 *    structurally — every Eq-flavoured op (unique, dedup, contains, index_of,
 *    find, find_index, any, all) is visible.
 *  - `R` orders DESCENDING, so its minimum is the LARGEST wrapped value — every
 *    Ord-flavoured op (min, max, sort, is_sorted) is visible, across four
 *    containers.
 *
 * A matrix built from normally-ordered types would pass whatever the compiler
 * did, which is exactly the blind spot that hid BUG 7 in
 * `ElementInstanceMatrixTest`.
 *
 * The other half of D.2 — that the primitive fast path must still FIRE — is
 * pinned by `KeyedFusionOrdTest`'s primitive-key cases, which fail if a guard
 * added here ever over-fires and disables fusion for Int.
 *
 * `sum`/`product` are deliberately absent: they are declared `{ xs: [Int] }`, so
 * no user instance can reach them.
 */
class FusedSinkInstanceAuditTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    /** Eq compares mod 10: `N.(1) == N.(11)`, but they are not equal structurally. */
    private val eqDeviant = """
        use std::collections::seq
        use std::abilities::compare::{ Eq }
        newtype N = Int
        impl Eq for N where {
          eq = { it, r } -> match it is
            N.(x) -> match r is
              N.(y) -> x % 10 == y % 10,
          neq = { it, r } -> match it is
            N.(x) -> match r is
              N.(y) -> x % 10 != y % 10
        }
        let ns: [N] = [N.(1), N.(11), N.(2)]
    """.trimIndent()

    /** Ord orders DESCENDING, so the minimum is the largest wrapped value. */
    private val ordReversed = """
        use std::collections::seq
        use std::abilities::compare::{ Ord }
        newtype R = Int
        impl Ord for R where {
          lt = { it, r } -> match it is
            R.(x) -> match r is
              R.(y) -> x > y,
          le = { it, r } -> match it is
            R.(x) -> match r is
              R.(y) -> x >= y,
          gt = { it, r } -> match it is
            R.(x) -> match r is
              R.(y) -> x < y,
          ge = { it, r } -> match it is
            R.(x) -> match r is
              R.(y) -> x <= y
        }
        let rs: [R] = [R.(1), R.(3), R.(2)]
    """.trimIndent()

    private fun eq(tail: String) = MezzeTestHelper.run("$eqDeviant\n$tail")
    private fun ord(tail: String) = MezzeTestHelper.run("$ordReversed\n$tail")

    // ── Eq-flavoured sinks ────────────────────────────────────────────────

    @Test
    fun `unique collapses values equal by the element's Eq`() {
        // 1 and 11 are equal mod 10, so three elements become two.
        assertEquals(2L, eq("ns.unique {}.size {}"))
    }

    @Test
    fun `dedup collapses values equal by the element's Eq`() {
        assertEquals(2L, eq("ns.dedup {}.size {}"))
    }

    @Test
    fun `contains uses the element's Eq`() {
        // 21 matches 1 mod 10.
        assertEquals(true, eq("ns.contains { x = N.(21) }"))
    }

    @Test
    fun `index_of uses the element's Eq`() {
        assertEquals(0L, eq("match ns.index_of { x = N.(21) } is\n  Some { val = i } -> i\n  None -> 0 - 1"))
    }

    @Test
    fun `find and find_index use the element's Eq`() {
        assertEquals(
            1L,
            eq("match ns.find { p = { x } -> x == N.(21) } is\n  Some { val = v } -> match v is\n    N.(z) -> z\n  None -> 0"),
        )
        assertEquals(
            0L,
            eq("match ns.find_index { p = { x } -> x == N.(21) } is\n  Some { val = i } -> i\n  None -> 0 - 1"),
        )
    }

    @Test
    fun `any and all use the element's Eq`() {
        assertEquals(true, eq("ns.any { p = { x } -> x == N.(21) }"))
        assertEquals(false, eq("ns.all { p = { x } -> x == N.(1) }"))
    }

    // ── Ord-flavoured sinks ───────────────────────────────────────────────

    @Test
    fun `min and max use the element's reversed Ord`() {
        assertEquals(3L, ord("match rs.min {} is\n  Some { val = v } -> match v is\n    R.(z) -> z\n  None -> 0"))
        assertEquals(1L, ord("match rs.max {} is\n  Some { val = v } -> match v is\n    R.(z) -> z\n  None -> 0"))
    }

    @Test
    fun `sort uses the element's reversed Ord`() {
        // Descending by the wrapped value, so the head is 3.
        assertEquals(3L, ord("match rs.sort {} is\n  [h, ...t] -> match h is\n    R.(z) -> z\n  [] -> 0"))
    }

    @Test
    fun `is_sorted uses the element's reversed Ord`() {
        assertEquals(false, ord("rs.is_sorted {}"))
        assertEquals(true, ord("let ds: [R] = [R.(3), R.(2), R.(1)]\nds.is_sorted {}"))
    }

    // ── The container dimension, where BUG 7 hid ──────────────────────────

    @Test
    fun `every container's min and max use the element's reversed Ord`() {
        // BUG 7's sorted-container half was invisible on seq alone: the defect
        // lived in how a CONTAINER's min was rewritten, not in the ordering.
        for (container in listOf("list", "set", "deque", "sorted_set")) {
            val prelude = ordReversed.replace(
                "use std::collections::seq",
                "use std::collections::seq\nuse std::collections::$container",
            ) + "\nlet c = $container::from_seq { xs = rs }"
            assertEquals(
                3L,
                MezzeTestHelper.run(
                    "$prelude\nmatch c.min {} is\n  Some { val = v } -> match v is\n    R.(z) -> z\n  None -> 0",
                ),
                "$container::min ignored the element's Ord",
            )
            assertEquals(
                1L,
                MezzeTestHelper.run(
                    "$prelude\nmatch c.max {} is\n  Some { val = v } -> match v is\n    R.(z) -> z\n  None -> 0",
                ),
                "$container::max ignored the element's Ord",
            )
        }
    }
}
