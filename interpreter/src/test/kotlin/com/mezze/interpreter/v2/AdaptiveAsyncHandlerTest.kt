package com.mezze.interpreter.v2

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Async execution tests for V2 interpreter.
 *
 * Note: This test is migrated from V1's AdaptiveAsyncHandlerTest which tested
 * internal V1-specific classes (AdaptiveAsyncHandler, ContinuationStateMachine,
 * checkpoint strategies like AlwaysLoom, AlwaysContinuation, etc.).
 *
 * The V2 interpreter uses a fundamentally different architecture based on
 * GraalVM Truffle with different async implementations (LoomAsync, OSThreadAsync,
 * CoopAsync). Rather than testing internal APIs, these tests verify async behavior
 * through actual Mezze language code.
 *
 * Tests cover:
 * - Loom (virtual thread) execution
 * - Cooperative (single-threaded) execution
 * - Promise operations (spawn, await, is_done)
 * - Concurrent execution patterns
 * - Error handling in async contexts
 */
@Execution(ExecutionMode.SAME_THREAD)
class AdaptiveAsyncHandlerTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Setup for Loom-based async (virtual threads)
    private val loomSetup = """
        use std::effects::async::{Async, LoomAsync}
    """.trimIndent()

    // Setup for Coop-based async (cooperative scheduling)
    private val coopSetup = """
        use std::effects::async::{Async, CoopAsync}
        use std::control::continuation
    """.trimIndent()

    // =============================================================================
    // Basic Loom Execution Tests (analogous to AlwaysLoom)
    // =============================================================================

    @Test
    fun `test loom spawn and await basic`() {
        val code = """
            $loomSetup

            perform LoomAsync {} in do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test loom multiple spawn and await`() {
        val code = """
            $loomSetup

            perform LoomAsync {} in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let p3 = Async.spawn {f = {} -> 12}

                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}

                r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test loom with string result`() {
        val code = """
            $loomSetup

            perform LoomAsync {} in do
                let p = Async.spawn {f = {} -> "test"}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals("test", result)
    }

    // =============================================================================
    // Coop Execution Tests (analogous to AlwaysContinuation)
    // =============================================================================

    @Test
    fun `test coop spawn and await basic`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test coop multiple spawn and await`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let p3 = Async.spawn {f = {} -> 12}

                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}

                r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test coop sequential execution`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                # In Coop, execution is deterministic and sequential
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}
                let p3 = Async.spawn {f = {} -> 3}

                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}

                r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(6L, result)
    }

    // =============================================================================
    // Promise Status Tests
    // =============================================================================

    @Test
    fun `test loom is_done after await`() {
        val code = """
            $loomSetup

            perform LoomAsync {} in do
                let p = Async.spawn {f = {} -> 42}
                let _ = Async.await {p = p}
                Async.is_done {p = p}
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test coop is_done after spawn`() {
        val code = """
            $coopSetup

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> 42}
                # In real Coop scheduler, tasks are queued, not run immediately
                # So is_done returns false until await is called
                Async.is_done {p = p}
        """
        val result = eval(code)
        // With real scheduler, spawned tasks are queued, not immediately run
        assertEquals(false, result)
    }

    // =============================================================================
    // Concurrent Operations Tests
    // =============================================================================

    @Test
    fun `test loom many concurrent spawns`() {
        val code = """
            $loomSetup

            perform LoomAsync {} in do
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}
                let p3 = Async.spawn {f = {} -> 3}
                let p4 = Async.spawn {f = {} -> 4}
                let p5 = Async.spawn {f = {} -> 5}

                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}
                let r4 = Async.await {p = p4}
                let r5 = Async.await {p = p5}

                r1 + r2 + r3 + r4 + r5
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun `test loom nested spawn`() {
        val code = """
            $loomSetup

            perform LoomAsync {} in do
                let outer = Async.spawn {f = {} -> do
                    let inner = Async.spawn {f = {} -> 21}
                    let x = Async.await {p = inner}
                    x * 2
                }
                Async.await {p = outer}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test coop nested spawn`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                let outer = Async.spawn {f = {} -> do
                    let inner = Async.spawn {f = {} -> 21}
                    let x = Async.await {p = inner}
                    x * 2
                }
                Async.await {p = outer}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Sleep and Timing Tests
    // =============================================================================

    @Test
    fun `test loom sleep returns unit`() {
        val code = """
            $loomSetup

            perform LoomAsync {} in do
                let _ = Async.sleep {ms = 1}  # Very short sleep
                True
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test coop sleep is no-op`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                # In Coop, sleep is a no-op (single-threaded)
                let _ = Async.sleep {ms = 1000}
                True
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    // =============================================================================
    // Cancellation Tests
    // =============================================================================

    @Test
    fun `test loom is_cancelled false by default`() {
        val code = """
            $loomSetup

            perform LoomAsync {} in do
                Async.is_cancelled {}
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `test coop is_cancelled always false`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                # In single-threaded Coop, cancellation is not applicable
                Async.is_cancelled {}
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    // =============================================================================
    // Complex Computation Tests
    // =============================================================================

    @Test
    fun `test loom with arithmetic in spawned task`() {
        val code = """
            $loomSetup

            perform LoomAsync {} in do
                let p = Async.spawn {f = {} -> (10 + 20) * 2}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(60L, result)
    }

    @Test
    fun `test coop with arithmetic in spawned task`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                let p = Async.spawn {f = {} -> (10 + 20) * 2}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(60L, result)
    }

    @Test
    fun `test loom spawned tasks with closure capture`() {
        val code = """
            $loomSetup

            perform LoomAsync {} in do
                let x = 10
                let y = 20
                let p = Async.spawn {f = {} -> x + y}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    fun `test coop spawned tasks with closure capture`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                let x = 10
                let y = 20
                let p = Async.spawn {f = {} -> x + y}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    // =============================================================================
    // Check Operation Tests
    // =============================================================================

    @Test
    fun `test loom check is no-op`() {
        val code = """
            $loomSetup

            perform LoomAsync {} in do
                let _ = Async.check {}
                True
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test coop check is no-op`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                let _ = Async.check {}
                True
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    // =============================================================================
    // Mixed Operations Tests
    // =============================================================================

    @Test
    fun `test loom spawn await sleep sequence`() {
        val code = """
            $loomSetup

            perform LoomAsync {} in do
                let p1 = Async.spawn {f = {} -> 10}
                let _ = Async.sleep {ms = 1}
                let p2 = Async.spawn {f = {} -> 20}
                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                r1 + r2
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    fun `test coop spawn await check sequence`() {
        val code = """
            $coopSetup

            perform CoopAsync {cont = continuation::new {}} in do
                let p1 = Async.spawn {f = {} -> 10}
                let _ = Async.check {}
                let p2 = Async.spawn {f = {} -> 20}
                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                r1 + r2
        """
        val result = eval(code)
        assertEquals(30L, result)
    }
}
