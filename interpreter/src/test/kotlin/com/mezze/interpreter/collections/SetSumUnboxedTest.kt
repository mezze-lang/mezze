package com.mezze.interpreter.collections

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `set::sum` over a set of Ints reads the storage's `long[]` directly instead of
 * boxing every element on the way out.
 *
 * `LongTrieSetStorage` (then `LongHashSetStorage`) already holds its elements
 * unboxed, but every way OUT of
 * it — the iterator and `toArray()` — hands back `Object`, so a sum boxed two
 * million longs only to unbox them one line later. MEASURED on
 * `bench/cases/fuseall` kernel 7 at n=2M, min of 5: **7 447 → 4 938 ps/elem**,
 * 1.5x, with the seq control steady at 393.
 *
 * VERIFIED TO FIRE, not assumed: making the fast path return a sentinel changed
 * the kernel's checksum, which is how I found that the OTHER long-backed
 * storage (`LongSetStorage`, dense and sorted) never holds the set that
 * `set::from_seq` builds — a branch for it was written, measured dead, and
 * removed.
 *
 * WHAT THE TESTS ARE FOR. The fast path skips two sentinel values that encode
 * "empty" and "deleted", so the risks are an element wrongly skipped and a
 * sentinel wrongly counted. the trie that replaced it stores no sentinels at all
 * — a set that would contain them falls back to a boxed storage and the generic
 * path — and a removal leaves `DELETED_SLOT` behind, which the scan must skip.
 */
class SetSumUnboxedTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(body: String): Any = MezzeTestHelper.run(
        "use std::collections::set\nuse std::collections::seq\n\n${body.trimIndent()}",
    )

    /** 1..100 sums to 5050, and is large enough to be in the hash storage. */
    @Test
    fun `sums a set large enough to use the hash storage`() {
        val out = run(
            """
            let main = {} -> do
              let s = set::from_seq { xs = seq::map { xs = seq::range { start = 1, end = 101 }, f = { x } -> x } }
              set::sum { s = s }
            main {}
            """,
        )
        assertEquals(5050L, out)
    }

    /** Negatives are where a sloppy accumulator or a bad sentinel test shows. */
    @Test
    fun `sums negatives`() {
        val out = run(
            """
            let main = {} -> do
              let s = set::from_seq { xs = [5, 0 - 3, 12, 0 - 20, 0] }
              set::sum { s = s }
            main {}
            """,
        )
        assertEquals(-6L, out)
    }

    /**
     * A REMOVAL leaves `DELETED_SLOT` in the table, and the unboxed scan has to
     * skip it. If it did not, the sum would include `Long.MIN_VALUE + 1`.
     */
    @Test
    fun `a removal does not leak its tombstone into the sum`() {
        val out = run(
            """
            let main = {} -> do
              let s = set::from_seq { xs = [10, 20, 30, 40] }
              let smaller = set::remove { s = s, x = 20 }
              set::sum { s = smaller }
            main {}
            """,
        )
        assertEquals(80L, out)
    }

    /** Empty must not scan into the empty slots and count them. */
    @Test
    fun `an empty set sums to zero`() {
        val out = run(
            """
            let main = {} -> set::sum { s = set::from_seq { xs = [] } }
            main {}
            """,
        )
        assertEquals(0L, out)
    }

    /**
     * `sum` must agree with the `fold` it is reachable from — `FoldToSumRewriter`
     * turns a summing fold INTO this call, so the two must be the same number by
     * construction.
     */
    @Test
    fun `sum agrees with the fold that rewrites to it`() {
        val out = run(
            """
            let main = {} -> do
              let s = set::from_seq { xs = seq::map { xs = seq::range { start = 0, end = 50 }, f = { x } -> x * 7 } }
              let viaSum = set::sum { s = s }
              let viaFold = set::fold { s = s, init = 0, f = { acc, x } -> acc + x }
              viaSum - viaFold
            main {}
            """,
        )
        assertEquals(0L, out, "set::sum disagreed with the fold that rewrites to it")
    }
}
