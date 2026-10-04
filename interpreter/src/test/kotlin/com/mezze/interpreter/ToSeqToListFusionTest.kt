package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for ToSeq and ToList fused materializing sinks.
 * Verifies that iter::to_seq and iter::to_list produce correct
 * results through the fused BDSL loop with various stage combos.
 */
class ToSeqToListFusionTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ── ToSeq ──

    @Test
    fun `range to_seq — identity`() {
        val src = """
            use std::collections::iter
            use std::collections::seq

            let main = {} -> do
              let s = iter::to_seq { it = iter::range { start = 0, end = 5 } }
              seq::fold { xs = s, init = 0, f = {acc, x} -> acc + x }
            main {}
        """.trimIndent()
        // 0+1+2+3+4 = 10
        assertEquals(10L, eval(src))
    }

    @Test
    fun `filter then to_seq — only evens`() {
        val src = """
            use std::collections::iter
            use std::collections::seq

            let main = {} -> do
              let s = iter::to_seq {
                it = iter::filter { it = iter::range { start = 0, end = 10 }, p = {x} -> x % 2 == 0 }
              }
              seq::fold { xs = s, init = 0, f = {acc, x} -> acc + x }
            main {}
        """.trimIndent()
        // 0+2+4+6+8 = 20
        assertEquals(20L, eval(src))
    }

    @Test
    fun `map then to_seq — doubled`() {
        val src = """
            use std::collections::iter
            use std::collections::seq

            let main = {} -> do
              let s = iter::to_seq {
                it = iter::map { it = iter::range { start = 1, end = 4 }, f = {x} -> x * 10 }
              }
              seq::fold { xs = s, init = 0, f = {acc, x} -> acc + x }
            main {}
        """.trimIndent()
        // 10+20+30 = 60
        assertEquals(60L, eval(src))
    }

    @Test
    fun `filter + map then to_seq — multi-stage`() {
        val src = """
            use std::collections::iter
            use std::collections::seq

            let main = {} -> do
              let filtered = iter::filter { it = iter::range { start = 0, end = 10 }, p = {x} -> x % 3 == 0 }
              let mapped = iter::map { it = filtered, f = {x} -> x * 2 }
              let s = iter::to_seq { it = mapped }
              seq::fold { xs = s, init = 0, f = {acc, x} -> acc + x }
            main {}
        """.trimIndent()
        // multiples of 3 in 0..10: 0,3,6,9 → doubled: 0,6,12,18 → sum=36
        assertEquals(36L, eval(src))
    }

    @Test
    fun `empty range to_seq — returns empty seq`() {
        val src = """
            use std::collections::iter
            use std::collections::seq

            let main = {} -> do
              let s = iter::to_seq { it = iter::range { start = 5, end = 5 } }
              seq::size { xs = s }
            main {}
        """.trimIndent()
        assertEquals(0L, eval(src))
    }

    // ── ToList ──

    @Test
    fun `range to_list — identity`() {
        val src = """
            use std::collections::iter
            use std::collections::list

            let main = {} -> do
              let l = iter::to_list { it = iter::range { start = 1, end = 4 } }
              list::fold_left { xs = l, init = 0, f = {acc, x} -> acc + x }
            main {}
        """.trimIndent()
        // 1+2+3 = 6
        assertEquals(6L, eval(src))
    }

    @Test
    fun `filter then to_list — only odds`() {
        val src = """
            use std::collections::iter
            use std::collections::list

            let main = {} -> do
              let l = iter::to_list {
                it = iter::filter { it = iter::range { start = 0, end = 10 }, p = {x} -> x % 2 == 1 }
              }
              list::fold_left { xs = l, init = 0, f = {acc, x} -> acc + x }
            main {}
        """.trimIndent()
        // 1+3+5+7+9 = 25
        assertEquals(25L, eval(src))
    }

    @Test
    fun `map then to_list — squared`() {
        val src = """
            use std::collections::iter
            use std::collections::list

            let main = {} -> do
              let l = iter::to_list {
                it = iter::map { it = iter::range { start = 1, end = 5 }, f = {x} -> x * x }
              }
              list::fold_left { xs = l, init = 0, f = {acc, x} -> acc + x }
            main {}
        """.trimIndent()
        // 1+4+9+16 = 30
        assertEquals(30L, eval(src))
    }

    // ── S-12: a LIST source is not evidence the elements are numbers ──────────

    /**
     * `list::to_seq` over each of `MezzeList`'s four backings.
     *
     * A bare list source admitted FusedLongPipeline, whose loop reads every element
     * as `((Number) raw).longValue()`. Only the long backing survived that: a
     * `MezzeCons` (boxed) and a `MezzeDoubleCons` both died with
     * `UnsupportedSpecializationException: ListToLongSeq_Node`. All four are listed
     * so the next backing added has somewhere to fail.
     */
    @Test
    fun `list to_seq works on every list backing`() {
        for ((label, literal, expected) in listOf(
            Triple("MezzeCons (boxed)", """["a", "b"]""", 2L),
            Triple("MezzeDoubleCons", "[1.5, 2.5]", 2L),
            Triple("MezzeLongCons", "[1, 2, 3]", 3L),
            Triple("MezzeNil", "[]", 0L),
        )) {
            val src = """
                use std::collections::list
                use std::collections::seq

                let main = {} -> do
                  let l = list::from_seq { xs = $literal }
                  seq::size { xs = list::to_seq { xs = l } }
                main {}
            """.trimIndent()
            assertEquals(expected, eval(src), "list::to_seq over $label")
        }
    }

    /**
     * A List fold's `init` is not dropped — this one returned a WRONG ANSWER rather
     * than failing.
     *
     * `BuiltinSumLongSeq` starts from a hard-coded `long acc = 0` and never reads the
     * fold's init, so the branch emitting it carries a `longZeroInit` guard. The
     * RawSeq branch had that guard; the RawList branch beside it did not, so the same
     * fold over a List silently lost the init:
     *
     *     list::fold_left { xs = [1,2,3], init = 10, f = acc + x }  =>  6
     *     seq::fold       { xs = [1,2,3], init = 10, f = acc + x }  => 16
     *
     * Both spellings are asserted, because the bug was precisely that the two
     * disagreed while reading identically in source.
     */
    @Test
    fun `a List fold keeps a non-zero init, like the Seq fold`() {
        val src = """
            use std::collections::list
            use std::collections::seq

            let main = {} -> do
              let l = list::from_seq { xs = [1, 2, 3] }
              let viaList = list::fold_left { xs = l, init = 10, f = {acc, x} -> acc + x }
              let viaSeq = seq::fold { xs = [1, 2, 3], init = 10, f = {acc, x} -> acc + x }
              { list = viaList, seq = viaSeq }
            main {}
        """.trimIndent()
        val rec = MezzeTestHelper.asRecord(eval(src))
        val viaList = rec.getField(rec.getFieldIndex("list")).toString().toLong()
        val viaSeq = rec.getField(rec.getFieldIndex("seq")).toString().toLong()
        assertEquals(16L, viaList, "the List fold dropped its init")
        assertEquals(16L, viaSeq, "the Seq fold dropped its init")
    }

    /** The zero-init sum still takes the fast path and is still correct — the guard
     *  narrows what fuses, it does not change an answer that was already right. */
    @Test
    fun `a zero-init List sum is unchanged`() {
        val src = """
            use std::collections::list

            let main = {} -> do
              let l = list::from_seq { xs = [1, 2, 3] }
              list::fold_left { xs = l, init = 0, f = {acc, x} -> acc + x }
            main {}
        """.trimIndent()
        assertEquals(6L, eval(src))
    }

    /** A Float fold over a List: the long sink "silently drops Float/double
     *  elements" per its own guard comment, and the List route had no guard. */
    @Test
    fun `a Float fold over a List sums the elements`() {
        val src = """
            use std::collections::list

            let main = {} -> do
              let l = list::from_seq { xs = [1.5, 2.5] }
              list::fold_left { xs = l, init = 0.0, f = {acc, x} -> acc + x }
            main {}
        """.trimIndent()
        assertEquals(4.0, eval(src))
    }
}
