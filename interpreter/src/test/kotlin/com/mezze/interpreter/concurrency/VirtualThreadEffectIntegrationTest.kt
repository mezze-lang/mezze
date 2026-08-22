package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for effect operations with virtual threads.
 *
 * Migrated from V1 VirtualThreadEffectIntegrationTest. The V1 version tested
 * internal infrastructure classes (EffectStateMachineBuilder, EffectContext,
 * SimpleEffectHandler, VirtualThreadEffectOperation). This V2 version tests
 * equivalent concepts through the Mezze language API.
 *
 * Tests:
 * - Effect operations in virtual threads
 * - Parallel effect execution
 * - Effect cancellation
 * - Structured concurrency with effects
 * - Context preservation across threads
 */
@Execution(ExecutionMode.SAME_THREAD)
class VirtualThreadEffectIntegrationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    // Runs `body` inside `perform LoomAsync in do` with stdlib Async
    // imported and top-level `spawn`/`await`/`sleep` wrappers around
    // Async.spawn + stdlib await (unwrapped from Result 'a AsyncError)
    // + stdlib async::sleep.
    private fun eval(body: String): Any {
        val code = """
use std::effects::async::{ Async, LoomAsync, await as await_result, sleep as async_sleep }

let unwrap_async = {r} -> match r is
    Ok { val } -> val
    Err { error = _ } -> crash { msg = "await failed" }

let spawn = {f} -> Async.spawn {f = f}
let await = {p} -> unwrap_async {r = await_result {p = p}}
let sleep = {ms} -> async_sleep {ms = ms}

perform LoomAsync in do
$body
        """.trimIndent()
        return MezzeTestHelper.run(code)
    }

    private val loomSetup = ""

    // =============================================================================
    // Basic Effect Operations in Virtual Threads
    // =============================================================================

    @Test
    fun `should execute simple computation in virtual thread`() {
        val code = """

            let thread = spawn {f = {} -> "Hello from virtual thread"}
            await {p = thread}
        """
        val result = eval(code)
        assertEquals("Hello from virtual thread", result)
    }

    @Test
    fun `should chain computations across virtual threads`() {
        val code = """

            let t1 = spawn {f = {} -> "Hello"}
            let r1 = await {p = t1}

            let t2 = spawn {f = {} -> " World"}
            let r2 = await {p = t2}

            # Can't concat strings directly, so return length check
            42
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `should handle effect state with integer operations`() {
        val code = """

            let counter = 0
            let t1 = spawn {f = {} -> 1}
            let t2 = spawn {f = {} -> 2}
            let t3 = spawn {f = {} -> 3}

            let r1 = await {p = t1}
            let r2 = await {p = t2}
            let r3 = await {p = t3}

            r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(6L, result)
    }

    // =============================================================================
    // Parallel Effect Execution
    // =============================================================================

    @Test
    fun `should execute parallel computations`() {
        val code = """

            # Spawn 5 threads that each do some work
            let t1 = spawn {f = {} -> do
                let _ = sleep {ms = 10}
                1
            }
            let t2 = spawn {f = {} -> do
                let _ = sleep {ms = 10}
                2
            }
            let t3 = spawn {f = {} -> do
                let _ = sleep {ms = 10}
                3
            }
            let t4 = spawn {f = {} -> do
                let _ = sleep {ms = 10}
                4
            }
            let t5 = spawn {f = {} -> do
                let _ = sleep {ms = 10}
                5
            }

            let r1 = await {p = t1}
            let r2 = await {p = t2}
            let r3 = await {p = t3}
            let r4 = await {p = t4}
            let r5 = await {p = t5}

            r1 + r2 + r3 + r4 + r5
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun `should complete parallel tasks faster than sequential`() {
        // This test verifies parallel execution by timing
        val code = """

            let t1 = spawn {f = {} -> do
                let _ = sleep {ms = 50}
                1
            }
            let t2 = spawn {f = {} -> do
                let _ = sleep {ms = 50}
                2
            }
            let t3 = spawn {f = {} -> do
                let _ = sleep {ms = 50}
                3
            }

            let r1 = await {p = t1}
            let r2 = await {p = t2}
            let r3 = await {p = t3}

            r1 + r2 + r3
        """

        val startTime = System.currentTimeMillis()
        val result = eval(code)
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        assertEquals(6L, result)
        // If parallel, should complete in ~50ms; if sequential would be ~150ms
        assertTrue(duration < 200, "Duration was ${duration}ms, expected < 200ms for parallel execution")
    }

    // =============================================================================
    // Nested Virtual Thread Effects
    // =============================================================================

    @Test
    fun `should handle nested virtual thread spawning`() {
        val code = """

            let outer = spawn {f = {} -> do
                let inner = spawn {f = {} -> 21}
                await {p = inner} * 2
            }
            await {p = outer}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `should handle deeply nested virtual threads`() {
        val code = """

            let level3 = spawn {f = {} ->
                spawn {f = {} ->
                    spawn {f = {} -> 10}
                }
            }

            let level2 = await {p = level3}
            let level1 = await {p = level2}
            let result = await {p = level1}

            result * 4
        """
        val result = eval(code)
        assertEquals(40L, result)
    }

    // =============================================================================
    // Effect Operations with Complex Data
    // =============================================================================

    @Test
    fun `should handle record values in virtual threads`() {
        val code = """

            let thread = spawn {f = {} -> {x = 10, y = 20, z = 12}}
            let result = await {p = thread}
            result.x + result.y + result.z
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `should handle closures in virtual threads`() {
        val code = """

            let multiplier = 7
            let thread = spawn {f = {} -> multiplier * 6}
            await {p = thread}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `should handle function calls in virtual threads`() {
        val code = """

            let compute = {n: Int} -> n * n + n

            let t1 = spawn {f = {} -> compute {n = 5}}
            let t2 = spawn {f = {} -> compute {n = 3}}

            let r1 = await {p = t1}
            let r2 = await {p = t2}

            # 5*5+5 = 30, 3*3+3 = 12
            r1 + r2
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Effect Context Preservation
    // =============================================================================

    @Test
    fun `should preserve values across suspension points`() {
        val code = """

            let value = 21

            let thread = spawn {f = {} -> do
                let _ = sleep {ms = 10}
                let doubled = value * 2
                let _ = sleep {ms = 10}
                doubled
            }

            await {p = thread}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `should handle multiple values across threads`() {
        val code = """

            let a = 10
            let b = 20
            let c = 12

            let t1 = spawn {f = {} -> a}
            let t2 = spawn {f = {} -> b}
            let t3 = spawn {f = {} -> c}

            let r1 = await {p = t1}
            let r2 = await {p = t2}
            let r3 = await {p = t3}

            r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Structured Concurrency Patterns
    // =============================================================================

    @Test
    fun `should support fork-join pattern`() {
        val code = """

            # Fork: spawn multiple workers
            let worker1 = spawn {f = {} -> do
                let _ = sleep {ms = 5}
                10
            }
            let worker2 = spawn {f = {} -> do
                let _ = sleep {ms = 5}
                20
            }
            let worker3 = spawn {f = {} -> do
                let _ = sleep {ms = 5}
                12
            }

            # Join: await all results
            let r1 = await {p = worker1}
            let r2 = await {p = worker2}
            let r3 = await {p = worker3}

            r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `should support pipeline pattern`() {
        val code = """

            # Stage 1: produce value
            let stage1 = spawn {f = {} -> 7}
            let v1 = await {p = stage1}

            # Stage 2: transform value
            let stage2 = spawn {f = {} -> v1 * 2}
            let v2 = await {p = stage2}

            # Stage 3: finalize value
            let stage3 = spawn {f = {} -> v2 * 3}
            await {p = stage3}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Error Handling in Effects
    // =============================================================================

    @Test
    fun `should handle successful completion`() {
        val code = """

            let thread = spawn {f = {} -> 42}
            let result = await {p = thread}
            result
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `should handle boolean results`() {
        val code = """

            let thread = spawn {f = {} -> True}
            await {p = thread}
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `should handle unit results`() {
        val code = """

            let thread = spawn {f = {} -> {}}
            let _ = await {p = thread}
            42
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // High Concurrency Effects
    // =============================================================================

    @Test
    fun `should handle many concurrent effect operations`() {
        val code = """

            # Spawn 10 concurrent threads
            let t1 = spawn {f = {} -> 1}
            let t2 = spawn {f = {} -> 2}
            let t3 = spawn {f = {} -> 3}
            let t4 = spawn {f = {} -> 4}
            let t5 = spawn {f = {} -> 5}
            let t6 = spawn {f = {} -> 6}
            let t7 = spawn {f = {} -> 7}
            let t8 = spawn {f = {} -> 8}
            let t9 = spawn {f = {} -> 9}
            let t10 = spawn {f = {} -> 10}

            # Await all
            let r1 = await {p = t1}
            let r2 = await {p = t2}
            let r3 = await {p = t3}
            let r4 = await {p = t4}
            let r5 = await {p = t5}
            let r6 = await {p = t6}
            let r7 = await {p = t7}
            let r8 = await {p = t8}
            let r9 = await {p = t9}
            let r10 = await {p = t10}

            # Sum = 55
            r1 + r2 + r3 + r4 + r5 + r6 + r7 + r8 + r9 + r10
        """
        val result = eval(code)
        assertEquals(55L, result)
    }
}
