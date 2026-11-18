package com.mezze.interpreter.continuation

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

/**
 * BUG 76: under the JIT, the stdlib `await` forwarder's propagation yield fired
 * while holding an `Ok` value — a park's RESULT — about 80% of runs of the fan-out
 * bench kernel; 0% with the JIT off; 0% with background compilation off. Every
 * continuation test before this one ran on the interpreter-only engine, so compiled
 * code had never been exercised. This class runs the shapes on a JIT engine with
 * low thresholds and background compilation, which is production's regime.
 */
class ParkUnderJitTest {
    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    @Test
    fun `the test JVM has an optimizing Truffle runtime`() {
        val name = MezzeTestHelper.truffleRuntimeName()
        assertFalse(name.contains("Interpreted") || name.contains("Default"), "no compiler in this JVM: $name")
    }

    private val fanOut = """
        use std::effects::async::{ Async, CoopAsync, await }
        use std::effects::time::{ SystemTime }
        use std::collections::seq
        use std::result
        let xs = seq::range { start = 1, end = 1001 }
        let outer = { b, acc, body } -> if b <= 0 then acc else outer { b = b - 1, acc = body { acc = acc }, body = body }
        let k = { n } -> perform CoopAsync in outer { b = n / 1000, acc = 0,
          body = { acc } -> do
            let ps = seq::map { xs = xs, f = { x } -> Async.spawn { f = {} -> x } }
            seq::fold { xs = ps, init = acc, f = { acc, x } -> acc + result::get_or { result = await { p = x }, def = 0 } } }
        let passes = { i, acc } -> if i <= 0 then acc else passes { i = i - 1, acc = acc + k { n = 3000 } }
        perform SystemTime in passes { i = 6, acc = 0 }
    """.trimIndent()

    /** Two phases: every await PARKS (the promises are pending), then every await finds a
     *  SETTLED promise. A root compiled at tier 1 during phase one ran its propagation
     *  yield in phase two on the `Ok` it had just received. */
    private val twoPhase = """
        use std::effects::async::{ Async, CoopAsync, await }
        use std::effects::time::{ SystemTime }
        use std::collections::seq
        use std::result
        let xs = seq::range { start = 1, end = 2001 }
        let awaits = { ps, acc } -> seq::fold { xs = ps, init = acc, f = { acc, x } -> acc + result::get_or { result = await { p = x }, def = 0 } }
        perform SystemTime in perform CoopAsync in do
          let ps = seq::map { xs = xs, f = { x } -> Async.spawn { f = {} -> x } }
          let a = awaits { ps = ps, acc = 0 }
          let b = awaits { ps = ps, acc = 0 }
          let c = awaits { ps = ps, acc = 0 }
          a + b + c
    """.trimIndent()

    @RepeatedTest(6)
    fun `two phases under tier 1 only`() = assertEquals(3L * 2001000L, MezzeTestHelper.runJitTier1(twoPhase))

    @RepeatedTest(6)
    fun `fan-out under tier 1 only`() = assertEquals(6L * 1501500L, MezzeTestHelper.runJitTier1(fanOut))

    @RepeatedTest(6)
    fun `fan-out awaits under the JIT`() = assertEquals(6L * 1501500L, MezzeTestHelper.runJitStress(fanOut))
}
