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
}
