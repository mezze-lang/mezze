package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * BUG 10 — the `_by` fusions compare the KEY with a primitive `<`.
 *
 * Found 2026-09-16 immediately after BUG 7's sorted-container half, by asking
 * whether that fix's siblings had the same hazard. They do.
 *
 * `min_by`/`max_by`/`sort_by_key` are `{ xs: ['a], f: { x: 'a } -> 'b } with
 * Ord 'b`. The stdlib implements them by handing `min_by_with_cmp` an explicit
 * comparator built from the key's Ord — which is CORRECT, and is what the
 * non-fused path runs. `ChainCanonicalizer` then fuses the call into a
 * `MinBy`/`MaxBy` sink, and `BytecodeCompiler` emits the key comparison as
 * `beginDirectLessThan()`, which only specializes long/double/String/Frac. The
 * supplied comparator is discarded.
 *
 * So a key type with a hand-written Ord crashes, and — the serious case — a
 * NEWTYPE key whose Ord disagrees with the wrapped type's returns the WRONG
 * ANSWER silently, exactly as BUG 7 did.
 *
 * FIXED in two layers. (1) The key's orderability is now DECIDED where the
 * solved type is in hand and carried as `FunctionIR.returnOrd`, and the fused
 * sink declines unless it is `DIRECT` — so it can no longer discard a correct
 * comparator on a guess. (2) The non-fused fallback was equally wrong, because
 * the specialization carried no evidence to dispatch through: `min_by`
 * specialized with concrete `typeArgs = [P, K]` and `abilityImpls = []`. A
 * transitive specialization now resolves its OWN constraints rather than
 * inheriting the caller's (C.1).
 *
 * WHY LAYER 1 ALONE WAS NOT ENOUGH. BUG 7's sorted-container half could be fixed by
 * refusing to fuse, because `__native_sorted_set_{min,max}` are the only bare
 * min/max element natives and the native is O(1) anyway — nothing profitable was
 * lost. That argument does NOT transfer: `min_by` over an Int key is a genuine,
 * perf-critical fast path, so the fusion has to stay and the decision has to be
 * made on the KEY'S TYPE.
 *
 * That type WAS not available: the key lambda for an Int key and for a record key
 * compiled to STRUCTURALLY IDENTICAL IR —
 * `ReadFieldIR(ReadLocalIR(slot=0, OBJECT), fieldName=…, fieldIndex=-1,
 * typeTag=null)` — differing only in the field NAME, with `returnType` null for
 * both. Hence `returnOrd`: the compiler decides orderability once, where the type
 * is known, and carries the decision.
 *
 * The primitive-key cases below guard the fast path that must keep fusing.
 */
class KeyedFusionOrdTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    /** A record key with its own Ord, ordering by `n`. */
    private val recordKey = """
        use std::collections::seq
        use std::abilities::compare::{ Ord }
        type K = { n: Int }
        impl Ord for K where {
          lt = { it, r } -> it.n < r.n,
          le = { it, r } -> it.n <= r.n,
          gt = { it, r } -> it.n > r.n,
          ge = { it, r } -> it.n >= r.n
        }
        type P = { k: K, tag: Int }
        let xs: [P] = [{ k = { n = 3 }, tag = 30 }, { k = { n = 1 }, tag = 10 }]
    """.trimIndent()

    /** A newtype key whose Ord is REVERSED — the silent-wrongness detector. */
    private val reversedNewtypeKey = """
        use std::collections::seq
        use std::abilities::compare::{ Ord }
        newtype R = Int
        impl Ord for R where {
          lt = { it, r } -> match it is
            R.(a) -> match r is
              R.(b) -> a > b,
          le = { it, r } -> match it is
            R.(a) -> match r is
              R.(b) -> a >= b,
          gt = { it, r } -> match it is
            R.(a) -> match r is
              R.(b) -> a < b,
          ge = { it, r } -> match it is
            R.(a) -> match r is
              R.(b) -> a <= b
        }
        type Q = { r: R, tag: Int }
        let qs: [Q] = [{ r = R.(3), tag = 30 }, { r = R.(1), tag = 10 }]
    """.trimIndent()

    // ── The fast path that must keep working ──────────────────────────────

    @Test
    fun `min_by with a primitive key`() {
        assertEquals(
            10L,
            MezzeTestHelper.run(
                """
                $recordKey
                match xs.min_by { f = { x } -> x.tag } is
                  Some { val = p } -> p.tag
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `max_by with a primitive key`() {
        assertEquals(
            30L,
            MezzeTestHelper.run(
                """
                $recordKey
                match xs.max_by { f = { x } -> x.tag } is
                  Some { val = p } -> p.tag
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `sort on records uses the element's own Ord - BUG 7's seq half, still fixed`() {
        assertEquals(
            1L,
            MezzeTestHelper.run(
                """
                $recordKey
                let ks: [K] = [{ n = 3 }, { n = 1 }]
                match ks.sort {} is
                  [h, ...r] -> h.n
                  [] -> 0
                """.trimIndent(),
            ),
        )
    }

    // ── BUG 10 ────────────────────────────────────────────────────────────

    @Test
    fun `min_by with a record key uses the key's own Ord`() {
        assertEquals(
            10L,
            MezzeTestHelper.run(
                """
                $recordKey
                match xs.min_by { f = { x } -> x.k } is
                  Some { val = p } -> p.tag
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `max_by with a record key uses the key's own Ord`() {
        assertEquals(
            30L,
            MezzeTestHelper.run(
                """
                $recordKey
                match xs.max_by { f = { x } -> x.k } is
                  Some { val = p } -> p.tag
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `sort_by_key with a record key uses the key's own Ord`() {
        assertEquals(
            10L,
            MezzeTestHelper.run(
                """
                $recordKey
                match xs.sort_by_key { f = { x } -> x.k } is
                  [h, ...r] -> h.tag
                  [] -> 0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `min_by with a REVERSED newtype key returns the largest`() {
        // R's Ord is reversed, so the "minimum" by that ordering is R.(3) — the
        // element tagged 30. A primitive comparison on the wrapped Int gives 10.
        assertEquals(
            30L,
            MezzeTestHelper.run(
                """
                $reversedNewtypeKey
                match qs.min_by { f = { x } -> x.r } is
                  Some { val = q } -> q.tag
                  None -> 0
                """.trimIndent(),
            ),
        )
    }
}
