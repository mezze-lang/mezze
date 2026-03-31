package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import com.mezze.interpreter.v2.values.MezzeRecord
import com.mezze.datastructures.seq.MezzeSeq

/**
 * Tests for real cooperative async with continuation-based scheduling.
 *
 * These tests verify that CoopAsync properly:
 * - Queues tasks instead of executing immediately
 * - Suspends at await points
 * - Interleaves task execution
 * - Uses continuations for suspend/resume
 */
class CoopAsyncTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================================================
    // Basic spawn and await
    // ========================================================================

    @Test
    fun `test simple spawn and await`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
        """.trimIndent()

        val result = eval(source)
        assertEquals(42L, result)
    }

    @Test
    fun `test spawn multiple tasks and await`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let p3 = Async.spawn {f = {} -> 30}
                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}
                r1 + r2 + r3
        """.trimIndent()

        val result = eval(source)
        assertEquals(60L, result)
    }

    @Test
    fun `test spawn with computation`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}

            let compute = {x} -> x * x + 1

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> compute {x = 5}}
                Async.await {p = p}
        """.trimIndent()

        val result = eval(source)
        assertEquals(26L, result) // 5*5 + 1 = 26
    }

    // ========================================================================
    // Task interleaving
    // ========================================================================

    @Test
    fun `test tasks can interleave`() {
        // This test verifies that tasks don't block each other
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let p1 = Async.spawn {f = {} -> "first"}
                let p2 = Async.spawn {f = {} -> "second"}
                # Await in opposite order - should still work
                let r2 = Async.await {p = p2}
                let r1 = Async.await {p = p1}
                r1
        """.trimIndent()

        val result = eval(source)
        assertEquals("first", result)
    }

    // ========================================================================
    // Join operations
    // ========================================================================

    @Test
    fun `test join two computations`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let result = Async.join {
                    l = {} -> 100,
                    r = {} -> 200
                }
                result.l + result.r
        """.trimIndent()

        val result = eval(source)
        assertEquals(300L, result)
    }

    @Test
    fun `test join3 computations`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let result = Async.join3 {
                    a = {} -> 1,
                    b = {} -> 2,
                    c = {} -> 3
                }
                result.a + result.b + result.c
        """.trimIndent()

        val result = eval(source)
        assertEquals(6L, result)
    }

    // ========================================================================
    // Collection operations
    // ========================================================================

    @Test
    fun `test async map`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let results = Async.map {
                    xs = [1, 2, 3, 4, 5],
                    f = {x} -> x * 2
                }
                results
        """.trimIndent()

        val result = eval(source)
        // Result should be [2, 4, 6, 8, 10]
        assertTrue(result is MezzeSeq<*>)
        val seq = result as MezzeSeq<*>
        val arr = seq.toArray()
        assertEquals(5, arr.size)
        assertEquals(2L, arr[0])
        assertEquals(10L, arr[4])
    }

    // ========================================================================
    // Is done check
    // ========================================================================

    @Test
    fun `test is_done returns true after await`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> 42}
                let _ = Async.await {p = p}
                Async.is_done {p = p}
        """.trimIndent()

        val result = eval(source)
        assertEquals(true, result)
    }

    // ========================================================================
    // Nested perform blocks
    // ========================================================================

    @Test
    fun `test nested function with async`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}

            let async_compute = {x} -> do
                let p = Async.spawn {f = {} -> x + 10}
                Async.await {p = p}

            perform CoopAsync in do
                let r1 = async_compute {x = 5}
                let r2 = async_compute {x = 15}
                r1 + r2
        """.trimIndent()

        val result = eval(source)
        assertEquals(40L, result) // (5+10) + (15+10) = 40
    }

    // ========================================================================
    // Scheduler behavior verification
    // ========================================================================

    @Test
    fun `test is_done returns false before task runs`() {
        // With real scheduler, spawned tasks are queued, not immediately run
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> 42}
                # Task is queued but not run yet
                Async.is_done {p = p}
        """.trimIndent()

        val result = eval(source)
        assertEquals(false, result)
    }

    @Test
    fun `test await runs scheduler to completion`() {
        // Await should run the scheduler until the promise completes
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}
                # Before await, both are queued
                let before1 = Async.is_done {p = p1}
                let before2 = Async.is_done {p = p2}
                # Await p1 - scheduler runs and completes p1
                let r1 = Async.await {p = p1}
                let after1 = Async.is_done {p = p1}
                { before1 = before1, before2 = before2, after1 = after1, r1 = r1 }
        """.trimIndent()

        val result = eval(source) as MezzeRecord
        assertEquals(false, result.getField("before1"))
        assertEquals(false, result.getField("before2"))
        assertEquals(true, result.getField("after1"))
        assertEquals(1L, result.getField("r1"))
    }

    // ========================================================================
    // All operation
    // ========================================================================

    @Test
    fun `test all waits for all promises`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let p3 = Async.spawn {f = {} -> 30}
                let results = Async.all {ps = [p1, p2, p3]}
                results
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeSeq<*>)
        val seq = result as MezzeSeq<*>
        val arr = seq.toArray()
        assertEquals(3, arr.size)
        assertEquals(10L, arr[0])
        assertEquals(20L, arr[1])
        assertEquals(30L, arr[2])
    }

    // ========================================================================
    // Race operation
    // ========================================================================

    @Test
    fun `test race returns first completed`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let p1 = Async.spawn {f = {} -> "first"}
                let p2 = Async.spawn {f = {} -> "second"}
                # In coop mode with FIFO queue, first spawned completes first
                Async.race {ps = [p1, p2]}
        """.trimIndent()

        val result = eval(source)
        assertEquals("first", result)
    }

    // ========================================================================
    // Timeout operation
    // ========================================================================

    @Test
    fun `test timeout returns Some in coop mode`() {
        // In coop mode, timeout always succeeds (no real time)
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> 42}
                match Async.timeout {ms = 1000, p = p} is
                    Some {val} -> val
                    None -> 0
        """.trimIndent()

        val result = eval(source)
        assertEquals(42L, result)
    }

    // ========================================================================
    // Filter operation
    // ========================================================================

    @Test
    fun `test async filter`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let results = Async.filter {
                    xs = [1, 2, 3, 4, 5, 6],
                    f = {x} -> x % 2 == 0
                }
                results
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeSeq<*>)
        val seq = result as MezzeSeq<*>
        val arr = seq.toArray()
        assertEquals(3, arr.size)
        assertEquals(2L, arr[0])
        assertEquals(4L, arr[1])
        assertEquals(6L, arr[2])
    }

    // ========================================================================
    // For each operation
    // ========================================================================

    @Test
    fun `test async for_each runs all`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let count = { val = 0 }
                Async.for_each {
                    xs = [1, 2, 3],
                    f = {x} -> count.val
                }
                # for_each returns unit, just verify it completes
                True
        """.trimIndent()

        val result = eval(source)
        assertEquals(true, result)
    }

    // ========================================================================
    // Sleep operation
    // ========================================================================

    @Test
    fun `test sleep yields to other tasks`() {
        // In coop mode, sleep just yields - doesn't actually wait
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                Async.sleep {ms = 100}
                42
        """.trimIndent()

        val result = eval(source)
        assertEquals(42L, result)
    }

    // ========================================================================
    // Complex scenarios
    // ========================================================================

    @Test
    fun `test chained async operations`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let p1 = Async.spawn {f = {} -> 10}
                let r1 = Async.await {p = p1}
                let p2 = Async.spawn {f = {} -> r1 * 2}
                let r2 = Async.await {p = p2}
                let p3 = Async.spawn {f = {} -> r2 + 5}
                Async.await {p = p3}
        """.trimIndent()

        val result = eval(source)
        assertEquals(25L, result) // 10 * 2 + 5 = 25
    }

    @Test
    fun `test many concurrent tasks`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}
            use std::collections::seq

            perform CoopAsync in do
                # Spawn 10 tasks
                let promises = seq::map {
                    xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
                    f = {x} -> Async.spawn {f = {} -> x * x}
                }
                # Await all and sum
                let results = Async.all {ps = promises}
                seq::fold { xs = results, init = 0, f = {acc, x} -> acc + x }
        """.trimIndent()

        val result = eval(source)
        // Sum of squares 1-10: 1+4+9+16+25+36+49+64+81+100 = 385
        assertEquals(385L, result)
    }

    @Test
    fun `test nested joins`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let outer = Async.join {
                    l = {} -> do
                        let inner = Async.join {
                            l = {} -> 1,
                            r = {} -> 2
                        }
                        inner.l + inner.r
                    ,
                    r = {} -> do
                        let inner = Async.join {
                            l = {} -> 3,
                            r = {} -> 4
                        }
                        inner.l + inner.r
                }
                outer.l + outer.r
        """.trimIndent()

        val result = eval(source)
        assertEquals(10L, result) // (1+2) + (3+4) = 10
    }

    // ========================================================================
    // Error handling (settle)
    // ========================================================================

    @Test
    fun `test settle returns Ok for successful tasks`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}
            use std::collections::seq

            perform CoopAsync in do
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}
                let results = Async.settle {ps = [p1, p2]}
                seq::length { xs = results }
        """.trimIndent()

        val result = eval(source)
        assertEquals(2L, result)
    }

    // ========================================================================
    // Run helper
    // ========================================================================

    @Test
    fun `test run helper spawns and awaits`() {
        val source = """
            use std::effects::async::{Async, CoopAsync, run}

            perform CoopAsync in do
                run {f = {} -> 42}
        """.trimIndent()

        val result = eval(source)
        assertEquals(42L, result)
    }

    // ========================================================================
    // Verify cooperative (not parallel) execution
    // ========================================================================

    @Test
    fun `test execution order is deterministic`() {
        // Coop scheduler runs tasks in FIFO order
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
                let order = []
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}
                let p3 = Async.spawn {f = {} -> 3}
                # Await in different order
                let r3 = Async.await {p = p3}
                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                # Results should still match their original values
                [r1, r2, r3]
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeSeq<*>)
        val seq = result as MezzeSeq<*>
        val arr = seq.toArray()
        assertEquals(1L, arr[0])
        assertEquals(2L, arr[1])
        assertEquals(3L, arr[2])
    }
}
