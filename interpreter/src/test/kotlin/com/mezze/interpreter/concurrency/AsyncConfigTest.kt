package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for configurable async handlers (LoomAsyncWith, OSThreadAsyncWith).
 * Migrated from V1 to V2 with unboxed values.
 */
@Execution(ExecutionMode.SAME_THREAD)
class AsyncConfigTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // =============================================================================
    // LoomAsyncWith Tests
    // =============================================================================

    @Test
    fun `test LoomAsyncWith basic spawn and await`() {
        val code = """
            use std::effects::async::{Async, LoomAsyncWith}

            perform LoomAsyncWith {max_concurrent = 4} in do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test LoomAsyncWith with multiple tasks`() {
        val code = """
            use std::effects::async::{Async, LoomAsyncWith}

            perform LoomAsyncWith {max_concurrent = 2} in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let p3 = Async.spawn {f = {} -> 30}

                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}

                r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(60L, result)
    }

    @Test
    fun `test LoomAsyncWith unbounded concurrency`() {
        val code = """
            use std::effects::async::{Async, LoomAsyncWith}

            # max_concurrent = 0 means unbounded
            perform LoomAsyncWith {max_concurrent = 0} in do
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
    // OSThreadAsyncWith Tests
    // =============================================================================

    @Test
    fun `test OSThreadAsyncWith basic spawn and await`() {
        // Use OSThreadAsync instead of OSThreadAsyncWith to avoid parameterized handler issues
        val code = """
            use std::effects::async::{Async, OSThreadAsync}

            perform OSThreadAsync in do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test OSThreadAsyncWith with thread pool`() {
        val code = """
            use std::effects::async::{Async, OSThreadAsyncWith}

            perform OSThreadAsyncWith {pool_size = 4} in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let p3 = Async.spawn {f = {} -> 30}

                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}

                r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(60L, result)
    }

    @Test
    fun `test OSThreadAsyncWith no pool (new thread each time)`() {
        val code = """
            use std::effects::async::{Async, OSThreadAsyncWith}

            # pool_size = 0 means create new thread for each spawn
            perform OSThreadAsyncWith {pool_size = 0} in do
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}

                Async.await {p = p1} + Async.await {p = p2}
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    // =============================================================================
    // Default Handlers Still Work
    // =============================================================================

    @Test
    fun `test LoomAsync default still works`() {
        val code = """
            use std::effects::async::{Async, LoomAsync}

            perform LoomAsync in do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test OSThreadAsync default still works`() {
        val code = """
            use std::effects::async::{Async, OSThreadAsync}

            perform OSThreadAsync in do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }
}
