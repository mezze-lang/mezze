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
        use std::effects::async::{ Async, LoomAsync }

        perform LoomAsync in do
            let m = mutex {}
            let loop : { n: Int, acc: Int } -> Int <Async, ...> = { n, acc } ->
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
    fun `repeated eval — closure values captured via top-level lambda stay fresh`() {
        // Build a closure that captures a top-level Int. If the
        // MezzeFunction's closure-values array is cached on a node
        // shared across contexts, we'd see the OLD context's bonus
        // value here. Each run mutates the local bonus differently
        // by writing a different top-level number.
        val template = """
            let bonus = %d
            let calc = { n: Int } -> n + bonus
            calc { n = 0 }
        """.trimIndent()
        repeat(4) { i ->
            val expected = 10 + i * 5L
            val r = MezzeTestHelper.run(template.format(expected.toInt()))
            assertEquals(expected, r, "iter $i expected $expected")
        }
    }

    @Test
    fun `repeated eval — same source, recursive call survives stale function cache`() {
        // Stress the FunctionLookupNode/CallDirectScalarNode caches. Same
        // source on every iter, so the same shared CallTarget is reused
        // by Truffle; any node that latched a MezzeFunction reference
        // from run 1 must still produce correct results on run N.
        val source = """
            let g : { n: Int } -> Int = { n } ->
                if n <= 0 then 0 else n + g { n = n - 1 }
            g { n = 10 }
        """.trimIndent()
        repeat(6) { i ->
            val r = MezzeTestHelper.run(source)
            // 10 + 9 + 8 + … + 1 + 0 = 55
            assertEquals(55L, r, "iter $i should produce 55")
        }
    }

    @Test
    fun `repeated eval with different captured value each run still works`() {
        // Caches keyed only on content-hash of the node-level function
        // will return the previous run's MezzeFunction (with its captured
        // closure values) when the same source's compiled CallTarget is
        // reused on a fresh Context. Make sure each call returns the
        // value bound in *that* run.
        val template = """
            let bonus = %d
            let calc : { n: Int } -> Int = { n } -> n + bonus
            calc { n = 100 }
        """.trimIndent()
        // Different sources → different CallTargets, so this exercises
        // the new-context populate-globals path:
        repeat(4) { i ->
            val bonus = 7 + i
            val r = MezzeTestHelper.run(template.format(bonus))
            assertEquals((100 + bonus).toLong(), r,
                "iteration $i expected ${100 + bonus} for bonus=$bonus")
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
