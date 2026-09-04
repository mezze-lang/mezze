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
}
