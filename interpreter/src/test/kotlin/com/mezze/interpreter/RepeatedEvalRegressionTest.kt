package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Regression for task #129: repeated `MezzeTestHelper.run` of the same
 * source must not throw "Global hash not found". This was the underlying
 * issue forcing `runs = 1, warmup = 0` in AsyncMicroBench.Mutex.
 */
class RepeatedEvalRegressionTest {

    private val mutexSource = """
        use std::effects::concurrent::mutex::{ mutex, with_lock }

        let m = mutex {}
        let loop : { n: Int, acc: Int } -> Int = { n, acc } ->
            if n <= 0 then acc
            else loop {
                n = n - 1,
                acc = with_lock { mutex = m, f = {} -> acc + 1 },
            }
        loop { n = 50, acc = 0 }
    """.trimIndent()

    @Test
    fun `repeated eval with mutex + recursive user lambda doesn't lose global hashes`() {
        repeat(4) { i ->
            val r = MezzeTestHelper.run(mutexSource)
            assertEquals(50L, r, "iteration $i should still succeed")
        }
    }

    @Test
    fun `repeated eval simpler recursive user lambda`() {
        val source = """
            let f : { n: Int } -> Int = { n } ->
                if n <= 0 then 0
                else 1 + f { n = n - 1 }
            f { n = 50 }
        """.trimIndent()
        repeat(4) { i ->
            val r = MezzeTestHelper.run(source)
            assertEquals(50L, r, "iteration $i should still succeed")
        }
    }

    @Test
    fun `repeated eval with closure over top-level let preserves capture`() {
        // Tests that closures captured at run N don't leak into run N+1.
        // If a top-level let is cached stalely, the body sees a stale captured
        // value from the previous run instead of the new one.
        val source = """
            let multiplier = 7
            let times_m : { n: Int } -> Int = { n } -> n * multiplier
            times_m { n = 6 }
        """.trimIndent()
        repeat(4) { i ->
            val r = MezzeTestHelper.run(source)
            assertEquals(42L, r, "iteration $i should still succeed")
        }
    }
}
