package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase 5 — effectful chains fuse correctly. Before Phase 5, any chain
 * whose lambdas contained a PerformOpIR was rejected by the
 * ChainCanonicalizer's effect gate and fell back to unfused
 * ability-dispatch. Phase 5 relaxes the gate and adds a cached
 * PerformD specialization (handler-chain-stable Assumption +
 * DirectCallNode) so effectful chains fuse with near-zero overhead.
 */
class EffectfulFusedChainTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `map with perform inside fold — effectful chain fuses`() {
        val src = """
            use std::collections::seq

            effect Counter where {
              tick : {val: Int} -> Int <Counter>
            }

            impl Counter for Tracker where {
              tick : {val: Int} -> Int = {val} -> val + 1
            }

            let main = {} -> perform Tracker in do
              seq::range { start = 0, end = 10 }
                .map {f = {x} -> Counter.tick {val = x}}
                .fold {init = 0, f = {acc, x} -> acc + x}

            main {}
        """.trimIndent()
        // tick adds 1 to each: [1,2,3,...,10], sum = 55
        assertEquals(55L, eval(src))
    }

    @Test
    fun `for_each with perform — side-effecting fused chain`() {
        val src = """
            use std::collections::seq

            effect Logger where {
              log : {msg: Str} -> {} <Logger>
            }

            impl Logger for NoopLogger where {
              log : {msg: Str} -> {} = {msg} -> {}
            }

            let main = {} -> perform NoopLogger in do
              seq::range { start = 0, end = 5 }
                .for_each {f = {x} -> Logger.log {msg = "item"}}
              42

            main {}
        """.trimIndent()
        assertEquals(42L, eval(src))
    }

    @Test
    fun `filter then map with perform then fold — three-stage effectful chain`() {
        val src = """
            use std::collections::seq

            effect Transform where {
              apply : {val: Int} -> Int <Transform>
            }

            impl Transform for Tripler where {
              apply : {val: Int} -> Int = {val} -> val * 3
            }

            let main = {} -> perform Tripler in do
              seq::range { start = 0, end = 20 }
                .filter {p = {x} -> x % 2 == 0}
                .map {f = {x} -> Transform.apply {val = x}}
                .fold {init = 0, f = {acc, x} -> acc + x}

            main {}
        """.trimIndent()
        // even numbers 0..18: 0,2,4,6,8,10,12,14,16,18
        // tripled: 0,6,12,18,24,30,36,42,48,54, sum = 270
        assertEquals(270L, eval(src))
    }
}
