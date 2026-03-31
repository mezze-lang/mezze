package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for CoopAsync (Cooperative Async) using scoped effects.
 *
 * Tests cover:
 * - Spawning computations that execute cooperatively
 * - Awaiting promises (which triggers execution of queued tasks)
 * - Deterministic execution order
 * - Closures in cooperative contexts
 * - Extended async functions (race, all, timeout, sleep, is_done)
 *
 * The CoopAsync implementation uses scoped effects with initialize_scope/finalize_scope
 * to manage the continuation lifecycle automatically.
 *
 * CoopAsync has the same API as LoomAsync - just swap the handler:
 *   perform LoomAsync in do ...
 *   perform CoopAsync in do ...
 */
@Execution(ExecutionMode.SAME_THREAD)
class CoopConcurrencyTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private val setupCode = """
        use std::effects::async::{Async, CoopAsync}
    """.trimIndent()

    // =============================================================================
    // Basic Spawn/Await Tests
    // =============================================================================

    @Test
    fun testSimpleSpawnAndAwait() {
        val code = """
            $setupCode

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> 5 + 10}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun testSpawnWithConstant() {
        val code = """
            $setupCode

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun testSpawnWithDoBlock() {
        // Test that do block with let binding works inside spawn
        val code = """
            $setupCode

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> do
                    let x = 42
                    x
                }
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun testMultipleSpawnAndAwait() {
        val code = """
            $setupCode

            perform CoopAsync in do
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
    fun testSpawnWithClosure() {
        val code = """
            $setupCode

            let test = {} ->
                perform CoopAsync in do
                    let x = 100
                    let p = Async.spawn {f = {} -> x * 2}
                    Async.await {p = p}

            test {}
        """
        val result = eval(code)
        assertEquals(200L, result)
    }

    @Test
    fun testSpawnWithComplexComputation() {
        val code = """
            $setupCode

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> do
                    let a = 5
                    let b = 7
                    let c = a * b
                    c + 10
                }
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(45L, result)
    }

    @Test
    fun testNestedSpawns() {
        val code = """
            $setupCode

            perform CoopAsync in do
                let outer = Async.spawn {f = {} ->
                    perform CoopAsync in do
                        let inner = Async.spawn {f = {} -> 42}
                        Async.await {p = inner}
                }
                Async.await {p = outer}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun testConcurrentComputations() {
        val code = """
            $setupCode

            perform CoopAsync in do
                let p1 = Async.spawn {f = {} -> 5 * 2}
                let p2 = Async.spawn {f = {} -> 10 + 3}
                let p3 = Async.spawn {f = {} -> 7 * 3}

                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}

                r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(44L, result)
    }

    // =============================================================================
    // Pipeline Patterns
    // =============================================================================

    @Test
    fun testSimplePipeline() {
        val code = """
            $setupCode

            perform CoopAsync in do
                let stage1 = Async.spawn {f = {} -> 10}
                let result1 = Async.await {p = stage1}

                let stage2 = Async.spawn {f = {} -> result1 * 2}
                let result2 = Async.await {p = stage2}

                let stage3 = Async.spawn {f = {} -> result2 + 5}
                Async.await {p = stage3}
        """
        val result = eval(code)
        assertEquals(25L, result)
    }

    @Test
    fun testFanOutFanIn() {
        val code = """
            $setupCode

            let spawn_workers: {remaining: Int, acc: Int} -> Int = {remaining, acc} ->
                if remaining <= 0
                then acc
                else perform CoopAsync in do
                    let p = Async.spawn {f = {} -> remaining * 2}
                    let result = Async.await {p = p}
                    spawn_workers {remaining = remaining - 1, acc = acc + result}

            spawn_workers {remaining = 5, acc = 0}
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    // =============================================================================
    // Recursive Concurrency
    // =============================================================================

    @Test
    fun testRecursiveSpawn() {
        val code = """
            $setupCode

            let recursive_spawn: {n: Int} -> Int = {n} ->
                if n <= 0
                then 0
                else perform CoopAsync in do
                    let p = Async.spawn {f = {} -> recursive_spawn {n = n - 1}}
                    let result = Async.await {p = p}
                    n + result

            recursive_spawn {n = 5}
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun testRecursiveParallel() {
        val code = """
            $setupCode

            let parallel_sum: {n: Int, acc: Int} -> Int = {n, acc} ->
                if n <= 0
                then acc
                else perform CoopAsync in do
                    let p = Async.spawn {f = {} -> n}
                    let result = Async.await {p = p}
                    parallel_sum {n = n - 1, acc = acc + result}

            parallel_sum {n = 10, acc = 0}
        """
        val result = eval(code)
        assertEquals(55L, result)
    }

    // =============================================================================
    // Closures in Concurrent Contexts
    // =============================================================================

    @Test
    fun testMultipleClosures() {
        val code = """
            $setupCode

            let test = {} ->
                perform CoopAsync in do
                    let a = 10
                    let b = 20

                    let p1 = Async.spawn {f = {} -> a * 2}
                    let p2 = Async.spawn {f = {} -> b * 3}

                    let r1 = Async.await {p = p1}
                    let r2 = Async.await {p = p2}

                    r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(80L, result)
    }

    @Test
    fun testNestedClosuresInSpawn() {
        val code = """
            $setupCode

            let makeAdder = {x} -> {y} -> x + y

            let test = {} ->
                perform CoopAsync in do
                    let add10 = makeAdder {x = 10}

                    let p1 = Async.spawn {f = {} -> add10 {y = 5}}
                    let p2 = Async.spawn {f = {} -> add10 {y = 15}}

                    let r1 = Async.await {p = p1}
                    let r2 = Async.await {p = p2}

                    r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(40L, result)
    }

    @Test
    fun testConditionalConcurrency() {
        val code = """
            $setupCode

            let conditional: {use_async: Bool} -> Int = {use_async} ->
                if use_async
                then perform CoopAsync in do
                    let p = Async.spawn {f = {} -> 100}
                    Async.await {p = p}
                else 50

            let r1 = conditional {use_async = True}
            let r2 = conditional {use_async = False}
            r1 + r2
        """
        val result = eval(code)
        assertEquals(150L, result)
    }

    // =============================================================================
    // Coop-Specific Tests (Deterministic Execution)
    // =============================================================================

    @Test
    fun testDeterministicExecutionOrder() {
        val code = """
            $setupCode

            perform CoopAsync in do
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}
                let p3 = Async.spawn {f = {} -> 3}

                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}

                r1 * 100 + r2 * 10 + r3
        """
        val result = eval(code)
        assertEquals(123L, result)
    }

    @Test
    fun testTasksExecuteOnAwait() {
        val code = """
            $setupCode

            perform CoopAsync in do
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

    // =============================================================================
    // Extended Async Functions (race, all, timeout, sleep, is_done)
    // =============================================================================

    @Test
    fun testRaceFirstCompletes() {
        val code = """
            $setupCode

            perform CoopAsync in do
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}
                let p3 = Async.spawn {f = {} -> 3}
                let result = Async.race {ps = [p1, p2, p3]}
                result >= 1
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun testAllCollectsResults() {
        val code = """
            $setupCode

            let sum: {xs: [Int]} -> Int = {xs} ->
                match xs is
                    [] -> 0
                    [h, ...t] -> h + sum {xs = t}

            perform CoopAsync in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let p3 = Async.spawn {f = {} -> 30}
                let results = Async.all {ps = [p1, p2, p3]}
                sum {xs = results}
        """
        val result = eval(code)
        assertEquals(60L, result)
    }

    @Test
    fun testSleep() {
        val code = """
            $setupCode

            perform CoopAsync in do
                let _ = Async.sleep {ms = 10}
                42
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun testIsDone() {
        val code = """
            $setupCode

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> 42}
                let _ = Async.await {p = p}
                Async.is_done {p = p}
        """
        val result = eval(code)
        // After await, the task has run and is_done returns true
        assertEquals(true, result)
    }

    @Test
    fun testTimeoutSuccess() {
        val code = """
            $setupCode

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> 42}
                let result = Async.timeout {ms = 5000, p = p}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(42L, result)
    }
}
