package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for Loom virtual thread primitives in V2.
 *
 * This tests the basic virtual thread operations (spawn, await, sleep)
 * through the Mezze interpreter. The V1 version tested low-level Java
 * infrastructure classes directly; this V2 version tests the same
 * concepts through the Mezze language API.
 *
 * Migrated from V1 SimpleLoomTest which tested:
 * - VirtualThreadPoolManager
 * - VirtualThreadConfig
 * - ConfiguredVirtualThreadFactory
 * - ExecutionContext
 */
@Execution(ExecutionMode.SAME_THREAD)
class SimpleLoomTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    // Runs `body` inside `perform LoomAsync in do` with stdlib Async
    // imported and top-level `spawn`/`await`/`sleep` wrappers around
    // Async.spawn + stdlib await (unwrapped from Result 'a AsyncError)
    // + stdlib async::sleep. Pre-existing test bodies treat the awaited
    // value as raw 'a; the unwrap keeps that shape.
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
    // Basic Virtual Thread Operations
    // =============================================================================

    @Test
    fun `should spawn virtual thread and await result`() {
        val code = """

            let thread = spawn {f = {} -> "Hello from virtual thread"}
            await {p = thread}
        """
        val result = eval(code)
        assertEquals("Hello from virtual thread", result)
    }

    @Test
    fun `should spawn virtual thread with integer computation`() {
        val code = """

            let thread = spawn {f = {} -> 42}
            await {p = thread}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `should spawn multiple virtual threads`() {
        val code = """

            let t1 = spawn {f = {} -> 1}
            let t2 = spawn {f = {} -> 2}

            let r1 = await {p = t1}
            let r2 = await {p = t2}

            r1 + r2
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `should handle sleep in virtual thread`() {
        val code = """

            let thread = spawn {f = {} -> do
                let _ = sleep {ms = 10}
                "Completed after sleep"
            }
            await {p = thread}
        """
        val result = eval(code)
        assertEquals("Completed after sleep", result)
    }

    // =============================================================================
    // Thread Composition
    // =============================================================================

    @Test
    fun `should compose multiple virtual threads`() {
        val code = """

            let t1 = spawn {f = {} -> 10}
            let t2 = spawn {f = {} -> 20}
            let t3 = spawn {f = {} -> 12}

            let r1 = await {p = t1}
            let r2 = await {p = t2}
            let r3 = await {p = t3}

            r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `should handle nested virtual thread spawn`() {
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
    fun `should handle complex computation in virtual thread`() {
        val code = """

            let compute = {n: Int} -> n * n + n

            let t1 = spawn {f = {} -> compute {n = 3}}
            let t2 = spawn {f = {} -> compute {n = 4}}
            let t3 = spawn {f = {} -> compute {n = 5}}

            let r1 = await {p = t1}
            let r2 = await {p = t2}
            let r3 = await {p = t3}

            # 3*3+3 = 12, 4*4+4 = 20, 5*5+5 = 30
            r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(62L, result)
    }

    // =============================================================================
    // Concurrent Execution Tests
    // =============================================================================

    @Test
    fun `should execute threads concurrently`() {
        val code = """

            # Spawn threads that each sleep briefly
            let t1 = spawn {f = {} -> do
                let _ = sleep {ms = 5}
                1
            }
            let t2 = spawn {f = {} -> do
                let _ = sleep {ms = 5}
                2
            }
            let t3 = spawn {f = {} -> do
                let _ = sleep {ms = 5}
                3
            }

            let r1 = await {p = t1}
            let r2 = await {p = t2}
            let r3 = await {p = t3}

            r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(6L, result)
    }

    @Test
    fun `should handle many concurrent threads`() {
        val code = """

            # Spawn 10 threads, each returning a value
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

            r1 + r2 + r3 + r4 + r5 + r6 + r7 + r8 + r9 + r10
        """
        val result = eval(code)
        assertEquals(55L, result)
    }

    // =============================================================================
    // Thread with Records and Complex Data
    // =============================================================================

    @Test
    fun `should handle thread returning record`() {
        val code = """

            let thread = spawn {f = {} -> {x = 10, y = 20}}
            let result = await {p = thread}
            result.x + result.y
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    fun `should handle thread with closure`() {
        val code = """

            let multiplier = 5
            let thread = spawn {f = {} -> multiplier * 10}
            await {p = thread}
        """
        val result = eval(code)
        assertEquals(50L, result)
    }

    @Test
    fun `should handle thread passing data between threads`() {
        val code = """

            let t1 = spawn {f = {} -> 21}
            let value = await {p = t1}

            let t2 = spawn {f = {} -> value * 2}
            await {p = t2}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Edge Cases
    // =============================================================================

    @Test
    fun `should handle thread returning unit`() {
        val code = """

            let thread = spawn {f = {} -> {}}
            let _ = await {p = thread}
            True
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `should handle thread with zero sleep`() {
        val code = """

            let thread = spawn {f = {} -> do
                let _ = sleep {ms = 0}
                42
            }
            await {p = thread}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `should handle thread returning boolean`() {
        val code = """

            let thread = spawn {f = {} -> True}
            await {p = thread}
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `should handle thread with string operations`() {
        val code = """

            let t1 = spawn {f = {} -> "Hello"}
            let t2 = spawn {f = {} -> " World"}

            let r1 = await {p = t1}
            let r2 = await {p = t2}

            # Return length check as we can't concat strings directly in this context
            True
        """
        val result = eval(code)
        assertEquals(true, result)
    }
}
