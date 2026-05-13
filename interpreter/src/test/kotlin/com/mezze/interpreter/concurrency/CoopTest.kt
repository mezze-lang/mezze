package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for Coop (cooperative) async implementation using actual Mezze code.
 *
 * These tests use the stdlib's Async effect with CoopAsync handler, which
 * provides cooperative scheduling using delimited continuations.
 * Migrated from V1 to V2 with unboxed values.
 */
@Execution(ExecutionMode.SAME_THREAD)
class CoopTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Setup code for Coop operations using stdlib
    private val coopSetup = """
        use std::effects::async::{Async, CoopAsync}
    """.trimIndent()

    // =============================================================================
    // Basic Coop Operations
    // =============================================================================

    @Test
    fun `test coop_spawn stores computation`() {
        val code = """
            $coopSetup

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> 42}
                # Promise is created but computation not executed yet
                Async.is_done {p = p}
        """
        val result = eval(code)
        // With real scheduler, spawn queues task, it's NOT done yet
        assertEquals(false, result)
    }

    @Test
    fun `test coop_await executes computation`() {
        val code = """
            $coopSetup

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test coop_await with complex computation`() {
        val code = """
            $coopSetup

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> 10 + 20 + 12}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test coop_sleep is no-op`() {
        val code = """
            $coopSetup

            perform CoopAsync in do
                # In Coop, sleep is a no-op (single-threaded)
                let _ = Async.sleep {ms = 1000}
                True
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test coop_is_cancelled always false`() {
        val code = """
            $coopSetup

            perform CoopAsync in do
                # In single-threaded Coop, cancellation is not applicable
                Async.is_cancelled {}
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `test coop_check is no-op`() {
        val code = """
            $coopSetup

            perform CoopAsync in do
                let _ = Async.check {}
                True
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    // =============================================================================
    // Sequential Execution (Coop is single-threaded)
    // =============================================================================

    @Test
    fun `test coop executes sequentially`() {
        val code = """
            $coopSetup

            perform CoopAsync in do
                # In Coop, spawn stores computation, await executes it
                # This is deterministic and sequential
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

    @Test
    fun `test coop with nested computation`() {
        val code = """
            $coopSetup

            perform CoopAsync in do
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
}
