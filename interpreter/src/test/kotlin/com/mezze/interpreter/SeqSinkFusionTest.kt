package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Regression tests for seq-side count/any/all/fold fusion.
 * Verifies that __native_seq_count, __native_seq_any, __native_seq_all,
 * and __native_seq_fold entry points are recognized by the ChainCanonicalizer
 * and produce correct fused loops through the BDSL emitter.
 */
class SeqSinkFusionTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `seq count with predicate — fused`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              seq::count { xs = seq::range { start = 0, end = 100 }, p = {x} -> x % 2 == 0 }
            main {}
        """.trimIndent()
        assertEquals(50L, eval(src))
    }

    @Test
    fun `seq any — fused`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              seq::any { xs = seq::range { start = 0, end = 100 }, p = {x} -> x > 50 }
            main {}
        """.trimIndent()
        assertEquals(true, eval(src))
    }

    @Test
    fun `seq all — fused`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              seq::all { xs = seq::range { start = 0, end = 100 }, p = {x} -> x < 200 }
            main {}
        """.trimIndent()
        assertEquals(true, eval(src))
    }

    @Test
    fun `seq all false — short circuit`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              seq::all { xs = seq::range { start = 0, end = 100 }, p = {x} -> x < 50 }
            main {}
        """.trimIndent()
        assertEquals(false, eval(src))
    }

    @Test
    fun `seq count via dot-chaining — fused`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              seq::range { start = 0, end = 1000 }
                .filter { p = {x} -> x % 3 == 0 }
                .count { p = {x} -> x % 2 == 0 }
            main {}
        """.trimIndent()
        // multiples of 3 in [0,1000): 0,3,6,...,999 → 334 items
        // of those, even ones (multiples of 6): 0,6,12,...,996 → 167
        assertEquals(167L, eval(src))
    }

    @Test
    fun `seq any with filter — fused`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              seq::range { start = 0, end = 100 }
                .filter { p = {x} -> x % 2 == 0 }
                .any { p = {x} -> x > 90 }
            main {}
        """.trimIndent()
        // even numbers: 0,2,...,98. Any > 90? 92,94,96,98 → True
        assertEquals(true, eval(src))
    }

    @Test
    fun `seq fold via native call — fused`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              seq::fold { xs = seq::range { start = 0, end = 10 }, init = 0, f = {acc, x} -> acc + x }
            main {}
        """.trimIndent()
        assertEquals(45L, eval(src))
    }

    @Test
    fun `seq map then fold — multi-stage`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              seq::range { start = 1, end = 6 }
                .map { f = {x} -> x * x }
                .fold { init = 0, f = {acc, x} -> acc + x }
            main {}
        """.trimIndent()
        // 1+4+9+16+25 = 55
        assertEquals(55L, eval(src))
    }
}
