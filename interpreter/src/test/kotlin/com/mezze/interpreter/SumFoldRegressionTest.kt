package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Regression for the sum_fold bench workload (bench/cases/sum_fold).
 * Pre-fix: `mez build` produced a jar/native binary that crashed at
 * first invocation with `[BDSL] compile failed for sum_to reason=unsupported`
 * because supports() rejected FusedChainIR (whitelist gap since #446
 * Phase 4b at 03b924a4a). This test locks in that the same shape
 * runs correctly through the interpreter test harness.
 */
class SumFoldRegressionTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `sum_to via seq range fold — the sum_fold bench workload`() {
        val src = """
            use std::collections::seq

            let sum_to = {n: Int} -> seq::range { start = 0, end = n }
                .fold {init = 0, f = {acc, x} -> acc + x}

            let main = {} -> do
              sum_to {n = 1000}
            main {}
        """.trimIndent()
        // sum 0..999 = 999*1000/2 = 499500
        assertEquals(499500L, eval(src))
    }

    @Test
    fun `sum 0 to 1M via seq range fold`() {
        val src = """
            use std::collections::seq

            let sum_to = {n: Int} -> seq::range { start = 0, end = n }
                .fold {init = 0, f = {acc, x} -> acc + x}

            let main = {} -> do
              sum_to {n = 1000000}
            main {}
        """.trimIndent()
        // sum 0..999999 = 499999500000
        assertEquals(499999500000L, eval(src))
    }

    @Test
    fun `filter then fold — multi-stage pipeline`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              seq::range { start = 0, end = 100 }
                .filter {p = {x} -> x % 2 == 0}
                .fold {init = 0, f = {acc, x} -> acc + x}
            main {}
        """.trimIndent()
        // sum of even numbers 0..98 = 0+2+4+...+98 = 2*(0+1+...+49) = 2*1225 = 2450
        assertEquals(2450L, eval(src))
    }

    @Test
    fun `map then fold — multi-stage pipeline`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              seq::range { start = 1, end = 11 }
                .map {f = {x} -> x * x}
                .fold {init = 0, f = {acc, x} -> acc + x}
            main {}
        """.trimIndent()
        // 1^2 + 2^2 + ... + 10^2 = 385
        assertEquals(385L, eval(src))
    }

    @Test
    fun `filter then map then fold — three-stage pipeline`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              seq::range { start = 0, end = 20 }
                .filter {p = {x} -> x % 3 == 0}
                .map {f = {x} -> x * 2}
                .fold {init = 0, f = {acc, x} -> acc + x}
            main {}
        """.trimIndent()
        // evens divisible by 3: 0,3,6,9,12,15,18 → doubled: 0,6,12,18,24,30,36 → sum=126
        assertEquals(126L, eval(src))
    }

    @Test
    fun `fold over literal seq — non-range source`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              seq::fold { xs = [10, 20, 30], init = 0, f = {acc, x} -> acc + x }
            main {}
        """.trimIndent()
        assertEquals(60L, eval(src))
    }

    @Test
    fun `filter then fold over literal seq`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              let xs = seq::filter { xs = [10, 25, 30], p = {x} -> x % 10 == 0 }
              seq::fold { xs = xs, init = 0, f = {acc, x} -> acc + x }
            main {}
        """.trimIndent()
        // 10 + 30 = 40 (25 filtered out)
        assertEquals(40L, eval(src))
    }

    @Test
    fun `iter take then fold — take first 5 from range`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              iter::range { start = 0, end = 100 }
                .take {n = 5}
                .fold {init = 0, f = {acc, x} -> acc + x}
            main {}
        """.trimIndent()
        // take 5 from 0..99 → 0,1,2,3,4 → sum = 10
        assertEquals(10L, eval(src))
    }

    @Test
    fun `iter drop then fold — drop first 95 from range`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              iter::range { start = 0, end = 100 }
                .drop {n = 95}
                .fold {init = 0, f = {acc, x} -> acc + x}
            main {}
        """.trimIndent()
        // drop 95 from 0..99 → 95,96,97,98,99 → sum = 485
        assertEquals(485L, eval(src))
    }

    @Test
    fun `iter take after map — take first 3 doubled values`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              iter::range { start = 1, end = 100 }
                .map {f = {x} -> x * 2}
                .take {n = 3}
                .fold {init = 0, f = {acc, x} -> acc + x}
            main {}
        """.trimIndent()
        // map: 2,4,6,8,... → take 3: 2,4,6 → sum = 12
        assertEquals(12L, eval(src))
    }

    @Test
    fun `iter drop after filter — drop first 2 even numbers`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              iter::range { start = 0, end = 20 }
                .filter {p = {x} -> x % 2 == 0}
                .drop {n = 2}
                .fold {init = 0, f = {acc, x} -> acc + x}
            main {}
        """.trimIndent()
        // evens: 0,2,4,6,8,10,12,14,16,18 → drop 2: 4,6,8,10,12,14,16,18 → sum = 88
        assertEquals(88L, eval(src))
    }
}
