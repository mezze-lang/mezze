package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * GAP #30, re-investigated — and it is not what was filed.
 *
 * The report said reading a record back out of a sorted container "fails to
 * type". It type-checks fine and then goes wrong at RUNTIME, and the real defect
 * is far broader than sorted containers: **a hand-written `impl Ord` is not used
 * by any generic `with Ord 'a` consumer reached through an inherent method.**
 *
 * What this file establishes, case by case:
 *
 *   PASSES  the impl itself, called directly (`a < b` on two records)
 *   PASSES  a primitive element — the primitive comparison node is correct for it
 *   PASSES  a newtype element — but ONLY by accident: a newtype wraps an Int at
 *           runtime, so the primitive fallback agrees. The REVERSED-Ord case
 *           proves it, returning the smallest where it must return the largest.
 *   PASSES  a user ability with a hand-written impl through a generic consumer
 *   PASSES  the Ord METHOD (`a.lt { r = b }`) through a generic consumer
 *   PASSES  `seq::min { xs = … }` called as a FUNCTION
 *   FAILS   the same thing called as a METHOD (`xs.min {}`)
 *
 * So the dict resolves correctly everywhere except across the inherent-method
 * boundary, and the OPERATOR is what breaks while the method call does not.
 * `impl Seq 'a { min = { it } -> min { xs = it } }` carries no `with Ord 'a` of
 * its own, so nothing is forwarded to the constrained `seq::min`, and `x < acc`
 * inside it falls through to the primitive comparison node: a crash on a record
 * or variant, a silently wrong answer for a newtype.
 *
 * Severity: silent wrongness, not just a crash. See known-bugs.md "BUG 7".
 *
 * FIXED, in two halves.
 *
 * The `seq` operator paths — `seq::min`, `seq::max`, `seq::sort` over records —
 * are monomorphized, and a spec now resolves the instance from its own recorded
 * type arguments instead of falling through to the primitive node.
 *
 * The sorted-container paths had a different cause, and the long detour through
 * "it depends on what else is in the program" was a red herring: the element's
 * Ord reached the container correctly all along. `ChainCanonicalizer` was fusing
 * `__native_sorted_set_min { s }` into `to_seq { s } |> Min`, and the fused Min
 * sink compares with DirectLessThan — which only specializes long/double/String/
 * Frac. So a sorted set BUILT with the element's own `cmp` closure, and holding
 * correctly ordered elements, had that comparator swapped for a primitive `<` at
 * the moment it was read back. Adding a `min` call to a program is what
 * introduced the fusable native, which is why the same construction passed alone.
 *
 * Min/Max no longer fuse over a container that owns its ordering. Nothing is
 * lost: `__native_sorted_set_{min,max}` are the only bare min/max element natives
 * that exist (every other collection's `min` is a Mezze `reduce`, which never
 * reaches the canonicalizer), and the native reads `elements[0]` in O(1) where
 * the fused form rescanned in O(n).
 *
 * All 19 cases pass, including the REVERSED-Ord newtype — the case that detects
 * silent wrongness rather than a crash.
 */
class SortedContainerReadBackTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val prelude = """
        use std::abilities::compare::{ Eq, Ord, Hash }
        use std::collections::seq
        use std::collections::sorted_set
        type P = { k: Int, tag: Int }
        # No `impl Eq` — a record derives it structurally, and a hand-written one
        # is a duplicate (the coherence rule). Ord is NOT derived for composites,
        # so it must be written.
        impl Ord for P where {
          lt = { it, r } -> it.k < r.k,
          le = { it, r } -> it.k <= r.k,
          gt = { it, r } -> it.k > r.k,
          ge = { it, r } -> it.k >= r.k
        }
    """.trimIndent()

    @Test
    fun `min of a sorted set of records, field read off the result`() {
        assertEquals(
            10L,
            MezzeTestHelper.run(
                """
                $prelude
                let s = sorted_set::from_seq { xs = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }] }
                match s.min {} is
                  Some { val = p } -> p.tag
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `max of a sorted set of records`() {
        assertEquals(
            30L,
            MezzeTestHelper.run(
                """
                $prelude
                let s = sorted_set::from_seq { xs = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }] }
                match s.max {} is
                  Some { val = p } -> p.tag
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `to_seq then first, field read off the result`() {
        assertEquals(
            10L,
            MezzeTestHelper.run(
                """
                $prelude
                let s = sorted_set::from_seq { xs = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }] }
                match s.to_seq {}.first {} is
                  Some { val = p } -> p.tag
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the ordering actually used is the element's own Ord`() {
        // P orders by `k` only. If the sorted set fell back to a structural or
        // insertion order, min would be the first-inserted element (tag 30).
        assertEquals(
            10L,
            MezzeTestHelper.run(
                """
                $prelude
                let s = sorted_set::from_seq { xs = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }, { k = 2, tag = 20 }] }
                match s.min {} is
                  Some { val = p } -> p.tag
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    // ── narrowing: which step stops using P's Ord ────────────────────────────

    @Test
    fun `step 1 - the hand-written Ord impl works on its own`() {
        assertEquals(
            true,
            MezzeTestHelper.run("$prelude\nlet a: P = { k = 1, tag = 10 }\nlet b: P = { k = 3, tag = 30 }\na < b"),
        )
    }

    @Test
    fun `step 2 - constructing the sorted set`() {
        assertEquals(
            2L,
            MezzeTestHelper.run(
                """
                $prelude
                let s = sorted_set::from_seq { xs = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }] }
                s.size {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `step 3 - to_seq then size (no element read)`() {
        assertEquals(
            2L,
            MezzeTestHelper.run(
                """
                $prelude
                let s = sorted_set::from_seq { xs = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }] }
                s.to_seq {}.size {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `contrast - the same operations on a primitive element`() {
        // If these pass while the record cases fail, the break is the ELEMENT's
        // Ord not reaching the comparator closure, not the sorted-set ops.
        assertEquals(
            1L,
            MezzeTestHelper.run(
                """
                use std::collections::seq
                use std::collections::sorted_set
                let s = sorted_set::from_seq { xs = [3, 1, 2] }
                match s.min {} is
                  Some { val = v } -> v
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `contrast - a newtype element with its own Ord`() {
        assertEquals(
            1L,
            MezzeTestHelper.run(
                """
                use std::abilities::compare::{ Eq, Ord, Hash }
                use std::collections::seq
                use std::collections::sorted_set
                pub newtype N = Int
                impl Hash for N where {
                  hash = { it } -> match it is N.(a) -> a
                }
                impl Eq for N where {
                  eq = { it, r } -> match it is
                    N.(a) -> match r is
                      N.(b) -> a == b,
                  neq = { it, r } -> match it is
                    N.(a) -> match r is
                      N.(b) -> a != b
                }
                impl Ord for N where {
                  lt = { it, r } -> match it is
                    N.(a) -> match r is
                      N.(b) -> a < b,
                  le = { it, r } -> match it is
                    N.(a) -> match r is
                      N.(b) -> a <= b,
                  gt = { it, r } -> match it is
                    N.(a) -> match r is
                      N.(b) -> a > b,
                  ge = { it, r } -> match it is
                    N.(a) -> match r is
                      N.(b) -> a >= b
                }
                let s = sorted_set::from_seq { xs = [N.(3), N.(1), N.(2)] }
                match s.min {} is
                  Some { val = v } -> match v is
                    N.(a) -> a
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `contrast - a VARIANT element with its own Ord`() {
        assertEquals(
            1L,
            MezzeTestHelper.run(
                """
                use std::abilities::compare::{ Eq, Ord, Hash }
                use std::collections::seq
                use std::collections::sorted_set
                type V = | Wrap { k: Int } |
                impl Ord for V where {
                  lt = { it, r } -> match it is
                    Wrap { k = a } -> match r is
                      Wrap { k = b } -> a < b,
                  le = { it, r } -> match it is
                    Wrap { k = a } -> match r is
                      Wrap { k = b } -> a <= b,
                  gt = { it, r } -> match it is
                    Wrap { k = a } -> match r is
                      Wrap { k = b } -> a > b,
                  ge = { it, r } -> match it is
                    Wrap { k = a } -> match r is
                      Wrap { k = b } -> a >= b
                }
                let s = sorted_set::from_seq { xs = [Wrap { k = 3 }, Wrap { k = 1 }] }
                match s.min {} is
                  Some { val = v } -> match v is
                    Wrap { k } -> k
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `contrast - a record element sorted via sort_by_cmp instead of a sorted set`() {
        // Same record, same Ord, but through a seq operation that takes the
        // comparator explicitly. Tells apart "P's Ord never resolves" from
        // "sorted_set's from_seq does not get the dict".
        assertEquals(
            10L,
            MezzeTestHelper.run(
                """
                $prelude
                let xs: [P] = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }]
                match xs.sort {}.first {} is
                  Some { val = p } -> p.tag
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `contrast - a newtype whose Ord is REVERSED`() {
        // The plain newtype case passes, but a newtype wraps an Int at runtime, so
        // a primitive fallback would give the same answer and the test could not
        // tell. Reverse the ordering: now `min` must return the LARGEST wrapped
        // value, which only happens if N's own `lt` is the one being used.
        assertEquals(
            3L,
            MezzeTestHelper.run(
                """
                use std::abilities::compare::{ Eq, Ord, Hash }
                use std::collections::seq
                use std::collections::sorted_set
                pub newtype N = Int
                impl Hash for N where {
                  hash = { it } -> match it is N.(a) -> a
                }
                impl Eq for N where {
                  eq = { it, r } -> match it is
                    N.(a) -> match r is
                      N.(b) -> a == b,
                  neq = { it, r } -> match it is
                    N.(a) -> match r is
                      N.(b) -> a != b
                }
                impl Ord for N where {
                  lt = { it, r } -> match it is
                    N.(a) -> match r is
                      N.(b) -> a > b,
                  le = { it, r } -> match it is
                    N.(a) -> match r is
                      N.(b) -> a >= b,
                  gt = { it, r } -> match it is
                    N.(a) -> match r is
                      N.(b) -> a < b,
                  ge = { it, r } -> match it is
                    N.(a) -> match r is
                      N.(b) -> a <= b
                }
                let s = sorted_set::from_seq { xs = [N.(3), N.(1), N.(2)] }
                match s.min {} is
                  Some { val = v } -> match v is
                    N.(a) -> a
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `contrast - a USER ability with a hand-written impl through a generic consumer`() {
        // Same shape as `sort`: a generic `with <Ability> 'a` function whose body
        // uses the ability on a value of type 'a. If this works, the break is
        // specific to how the OPERATOR `<` is lowered, not to hand-written impls
        // reaching generic consumers in general.
        assertEquals(
            4L,
            MezzeTestHelper.run(
                """
                use std::collections::seq
                ability Sz for 'a where {
                  sz: { it: 'a } -> Int
                }
                type P = { k: Int }
                impl Sz for P where {
                  sz = { it } -> it.k
                }
                let total: { xs: ['a] } with Sz 'a -> Int = { xs } ->
                  xs.fold { init = 0, f = { acc, x } -> acc + x.sz {} }
                total { xs = [{ k = 1 }, { k = 3 }] }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `contrast - the Ord METHOD rather than the operator, through a generic consumer`() {
        // `a < b` desugars to Ord's `lt`. Calling `lt` by name through the same
        // generic shape separates "the dict never arrives" from "the operator is
        // lowered to a primitive node before the dict is consulted".
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                $prelude
                let firstIsSmaller: { xs: ['a] } with Ord 'a -> Bool = { xs } -> match xs is
                  [a, b, ...rest] -> a.lt { r = b }
                  [_, ...rest] -> True
                  [] -> True
                firstIsSmaller { xs = [{ k = 1, tag = 10 }, { k = 3, tag = 30 }] }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `contrast - seq min on records, the shape that was fixed for issue 18`() {
        // `seq::min` is written exactly like `sort`: `x < acc` inside an inline
        // lambda handed to `reduce`. If this works and `sort` does not, the
        // difference is not the nested-lambda shape.
        assertEquals(
            10L,
            MezzeTestHelper.run(
                """
                $prelude
                let xs: [P] = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }]
                match xs.min {} is
                  Some { val = p } -> p.tag
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `contrast - seq max on records`() {
        assertEquals(
            30L,
            MezzeTestHelper.run(
                """
                $prelude
                let xs: [P] = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }]
                match xs.max {} is
                  Some { val = p } -> p.tag
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `contrast - seq min called as a FUNCTION rather than a method`() {
        // `xs.min {}` goes through the inherent `impl Seq 'a { min = … }`, whose
        // own signature carries no `with Ord 'a`. Calling the constrained function
        // directly skips that hop. If this passes while the method form fails, the
        // dict is being lost at the inherent-op boundary.
        assertEquals(
            10L,
            MezzeTestHelper.run(
                """
                $prelude
                let xs: [P] = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }]
                match seq::min { xs = xs } is
                  Some { val = p } -> p.tag
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `sorted_set min called as a FUNCTION rather than a method`() {
        // `min()` on the data structure is just `elements[0]` — no comparison — and
        // construction succeeds (step 2 passes). So a crash in `s.min {}` means the
        // method form is not reaching the sorted-set native at all.
        assertEquals(
            10L,
            MezzeTestHelper.run(
                """
                $prelude
                let s = sorted_set::from_seq { xs = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }] }
                match sorted_set::min { s = s } is
                  Some { val = p } -> p.tag
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `construct and size then min, in ONE program`() {
        // Step 2 (construct + size) passes and `min()` on the data structure is
        // just elements[0]. Doing both in one program removes any possibility that
        // the two test methods compiled the construction differently.
        assertEquals(
            10L,
            MezzeTestHelper.run(
                """
                $prelude
                let s = sorted_set::from_seq { xs = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }] }
                let n = s.size {}
                match sorted_set::min { s = s } is
                  Some { val = p } -> p.tag + n - n
                  None -> 0
                """.trimIndent(),
            ),
        )
    }
}
