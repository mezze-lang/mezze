package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for Find and Reduce fused sink emit.
 * Verifies that iter::find and iter::reduce produce correct
 * Option results through the fused BDSL loop.
 */
class FindReduceFusionTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ── Find ──

    @Test
    fun `find first even — returns Some`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              let r = iter::find { it = iter::range { start = 1, end = 10 }, p = {x} -> x % 2 == 0 }
              match r is
                Some { val } -> val
                None -> -1
            main {}
        """.trimIndent()
        assertEquals(2L, eval(src))
    }

    @Test
    fun `find no match — returns None`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              let r = iter::find { it = iter::range { start = 0, end = 5 }, p = {x} -> x > 100 }
              match r is
                Some { val } -> val
                None -> -1
            main {}
        """.trimIndent()
        assertEquals(-1L, eval(src))
    }

    @Test
    fun `find with filter — multi-stage`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              let r = iter::find {
                it = iter::filter { it = iter::range { start = 0, end = 100 }, p = {x} -> x % 3 == 0 },
                p = {x} -> x > 20
              }
              match r is
                Some { val } -> val
                None -> -1
            main {}
        """.trimIndent()
        // first multiple of 3 > 20 is 21
        assertEquals(21L, eval(src))
    }

    @Test
    fun `find with map — returns mapped value`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              let r = iter::find {
                it = iter::map { it = iter::range { start = 0, end = 10 }, f = {x} -> x * 10 },
                p = {x} -> x > 50
              }
              match r is
                Some { val } -> val
                None -> -1
            main {}
        """.trimIndent()
        // 0,10,20,30,40,50,60 — first > 50 is 60
        assertEquals(60L, eval(src))
    }

    @Test
    fun `find on empty range — returns None`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              let r = iter::find { it = iter::range { start = 5, end = 5 }, p = {x} -> x > 0 }
              match r is
                Some { val } -> val
                None -> -1
            main {}
        """.trimIndent()
        assertEquals(-1L, eval(src))
    }

    // ── Reduce ──

    @Test
    fun `reduce sum — returns Some`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              let r = iter::reduce { it = iter::range { start = 1, end = 6 }, f = {acc, x} -> acc + x }
              match r is
                Some { val } -> val
                None -> -1
            main {}
        """.trimIndent()
        // 1+2+3+4+5 = 15
        assertEquals(15L, eval(src))
    }

    @Test
    fun `reduce single element — returns Some of that element`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              let r = iter::reduce { it = iter::range { start = 42, end = 43 }, f = {acc, x} -> acc + x }
              match r is
                Some { val } -> val
                None -> -1
            main {}
        """.trimIndent()
        assertEquals(42L, eval(src))
    }

    @Test
    fun `reduce empty — returns None`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              let r = iter::reduce { it = iter::range { start = 5, end = 5 }, f = {acc, x} -> acc + x }
              match r is
                Some { val } -> val
                None -> -1
            main {}
        """.trimIndent()
        assertEquals(-1L, eval(src))
    }

    @Test
    fun `reduce with filter — only filtered elements`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              let r = iter::reduce {
                it = iter::filter { it = iter::range { start = 1, end = 11 }, p = {x} -> x % 2 == 0 },
                f = {acc, x} -> acc + x
              }
              match r is
                Some { val } -> val
                None -> -1
            main {}
        """.trimIndent()
        // evens: 2+4+6+8+10 = 30
        assertEquals(30L, eval(src))
    }

    @Test
    fun `reduce with map then reduce — sum of squares`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              let r = iter::reduce {
                it = iter::map { it = iter::range { start = 1, end = 6 }, f = {x} -> x * x },
                f = {acc, x} -> acc + x
              }
              match r is
                Some { val } -> val
                None -> -1
            main {}
        """.trimIndent()
        // 1+4+9+16+25 = 55
        assertEquals(55L, eval(src))
    }
}
