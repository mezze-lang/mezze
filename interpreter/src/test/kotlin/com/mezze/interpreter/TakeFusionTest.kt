package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for Take stage fusion in FLP.
 * Verifies that seq::take + sink chains produce correct results
 * through the fused long pipeline.
 */
class TakeFusionTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `take + sum — takes first N elements`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              let s = seq::range { start = 1, end = 11 }
              let t = seq::take { xs = s, n = 5 }
              seq::sum { xs = t }
            main {}
        """.trimIndent()
        // 1+2+3+4+5 = 15
        assertEquals(15L, eval(src))
    }

    @Test
    fun `take more than available — returns full sum`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              let s = seq::range { start = 1, end = 6 }
              let t = seq::take { xs = s, n = 100 }
              seq::sum { xs = t }
            main {}
        """.trimIndent()
        // 1+2+3+4+5 = 15
        assertEquals(15L, eval(src))
    }

    @Test
    fun `take zero — returns zero sum`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              let s = seq::range { start = 1, end = 11 }
              let t = seq::take { xs = s, n = 0 }
              seq::sum { xs = t }
            main {}
        """.trimIndent()
        assertEquals(0L, eval(src))
    }

    @Test
    fun `take + count — counts taken elements`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              let s = seq::range { start = 1, end = 101 }
              let t = seq::take { xs = s, n = 25 }
              seq::count { xs = t, p = {x} -> x % 2 == 0 }
            main {}
        """.trimIndent()
        // evens in 1..25: 2,4,6,8,10,12,14,16,18,20,22,24 = 12
        assertEquals(12L, eval(src))
    }

    @Test
    fun `take + fold — fold over first N`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              let s = seq::range { start = 1, end = 11 }
              let t = seq::take { xs = s, n = 3 }
              seq::fold { xs = t, init = 0, f = { acc, x } -> acc + x * x }
            main {}
        """.trimIndent()
        // 1*1 + 2*2 + 3*3 = 1+4+9 = 14
        assertEquals(14L, eval(src))
    }

    @Test
    fun `take on empty seq — returns zero sum`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              let s = seq::range { start = 5, end = 5 }
              let t = seq::take { xs = s, n = 10 }
              seq::sum { xs = t }
            main {}
        """.trimIndent()
        assertEquals(0L, eval(src))
    }

    @Test
    fun `take large from large seq — correct partial sum`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              let s = seq::range { start = 0, end = 1000000 }
              let t = seq::take { xs = s, n = 100000 }
              seq::sum { xs = t }
            main {}
        """.trimIndent()
        // sum 0..99999 = 99999*100000/2 = 4999950000
        assertEquals(4999950000L, eval(src))
    }
}
