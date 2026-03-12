package com.mezze.interpreter.v2

import com.mezze.datastructures.seq.MezzeSeq
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Minimal test for type variable handling in async contexts.
 * Tests that polymorphic functions work correctly with the async effect system.
 *
 * Migrated from V1 to V2 with unboxed values.
 */
@Execution(ExecutionMode.SAME_THREAD)
class MinimalTypeVarTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun testMinimalCase() {
        // Test type variables work correctly across async boundaries
        // Using stdlib Async effect for consistency with other tests
        val code = """
            use std::effects::async::{Async, LoomAsync}

            perform LoomAsync in do
                let p1 = Async.spawn {f = {} -> 1 * 2}
                let p2 = Async.spawn {f = {} -> 2 * 2}
                let p3 = Async.spawn {f = {} -> 3 * 2}
                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}
                [r1, r2, r3]
        """
        val result = eval(code)
        assertTrue(result is MezzeSeq<*>, "Expected MezzeSeq but got ${result?.javaClass?.name}")
        val seq = result as MezzeSeq<*>
        assertEquals(3, seq.size())
        // Verify the results (unboxed Long values)
        assertEquals(2L, seq.get(0))
        assertEquals(4L, seq.get(1))
        assertEquals(6L, seq.get(2))
    }

    @Test
    fun testTypeVarsWithEffectSystem() {
        // Test type variables with the effect system
        val code = """
            use std::effects::async::{Async, LoomAsync}

            perform LoomAsync in do
                let p1 = Async.spawn {f = {} -> 1 * 2}
                let p2 = Async.spawn {f = {} -> 2 * 2}
                let p3 = Async.spawn {f = {} -> 3 * 2}
                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}
                [r1, r2, r3]
        """
        val result = eval(code)
        assertTrue(result is MezzeSeq<*>, "Expected MezzeSeq but got ${result?.javaClass?.name}")
        val seq = result as MezzeSeq<*>
        assertEquals(3, seq.size())
        // Verify the results (unboxed Long values)
        assertEquals(2L, seq.get(0))
        assertEquals(4L, seq.get(1))
        assertEquals(6L, seq.get(2))
    }

    @Test
    fun testTypeVarsWithCoopAsync() {
        // Test type variables with cooperative async (single-threaded, no multi-thread support needed)
        val code = """
            use std::effects::async::{Async, CoopAsync}
            use std::control::continuation

            perform CoopAsync {cont = continuation::new {}} in do
                let p1 = Async.spawn {f = {} -> 1 * 2}
                let p2 = Async.spawn {f = {} -> 2 * 2}
                let p3 = Async.spawn {f = {} -> 3 * 2}
                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}
                [r1, r2, r3]
        """
        val result = eval(code)
        assertTrue(result is MezzeSeq<*>, "Expected MezzeSeq but got ${result?.javaClass?.name}")
        val seq = result as MezzeSeq<*>
        assertEquals(3, seq.size())
        // Verify the results (unboxed Long values)
        assertEquals(2L, seq.get(0))
        assertEquals(4L, seq.get(1))
        assertEquals(6L, seq.get(2))
    }
}
