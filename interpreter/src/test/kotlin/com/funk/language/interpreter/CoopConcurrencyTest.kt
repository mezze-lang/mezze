package com.funk.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for CoopAsync (Cooperative Async) using delimited continuations.
 *
 * Tests cover:
 * - Spawning computations that execute cooperatively
 * - Awaiting promises (which triggers execution of queued tasks)
 * - Deterministic execution order
 * - Closures in cooperative contexts
 * - Extended async functions (race, all, timeout, sleep, is_done)
 *
 * The CoopAsync implementation uses native scheduler with continuation support.
 * This makes it suitable for single-threaded environments like a future
 * JavaScript compile target, and enables workflow orchestration with
 * serializable state.
 *
 * CoopAsync has the same API as LoomAsync - just swap the handler:
 *   perform LoomAsync in do ...
 *   perform CoopAsync {cont = continuation::new {}} in do ...
 */
@Execution(ExecutionMode.SAME_THREAD)
class CoopConcurrencyTest {

    private lateinit var parser: com.funk.language.parser.FunkParser
    private lateinit var typeChecker: com.funk.language.typechecker.TypeChecker

    @BeforeEach
    fun setup() {
        parser = com.funk.language.parser.FunkParser()
        typeChecker = com.funk.language.typechecker.TypeChecker()
        com.funk.language.typechecker.ModuleResolver.clearCache()
        com.funk.language.typechecker.TypeChecker.clearCache()
    }

    @AfterEach
    fun teardown() {
        // Clear ThreadLocal state to prevent context leaks
        TruffleContextHolder.clearEnv()
    }

    private fun eval(source: String): FunkValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = FunkTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    private val setupCode = """
        use std::async::{Async, CoopAsync}
        use std::control::continuation
    """.trimIndent()

    // =============================================================================
    // Basic Spawn/Await Tests
    // =============================================================================

    @Test
    fun testSimpleSpawnAndAwait() {
        // Same API as Loom - just swap the handler!
        val code = """
            $setupCode

            perform CoopAsync {cont = continuation::new {}} in do
                let p = Async.spawn {f = {} -> 5 + 10}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(FunkInt(15), result)
    }

    @Test
    fun testSpawnWithConstant() {
        val code = """
            $setupCode

            perform CoopAsync {cont = continuation::new {}} in do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    @Test
    fun testMultipleSpawnAndAwait() {
        val code = """
            $setupCode

            perform CoopAsync {cont = continuation::new {}} in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let p3 = Async.spawn {f = {} -> 30}

                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}

                r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(FunkInt(60), result)
    }

    @Test
    fun testSpawnWithClosure() {
        val code = """
            $setupCode

            let test = {} ->
                perform CoopAsync {cont = continuation::new {}} in do
                    let x = 100
                    let p = Async.spawn {f = {} -> x * 2}
                    Async.await {p = p}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(200), result)
    }

    @Test
    fun testSpawnWithComplexComputation() {
        val code = """
            $setupCode

            perform CoopAsync {cont = continuation::new {}} in do
                let p = Async.spawn {f = {} -> do
                    let a = 5
                    let b = 7
                    let c = a * b
                    c + 10
                }
                Async.await {p = p}
        """
        val result = eval(code)
        assertEquals(FunkInt(45), result)
    }

    @Test
    fun testNestedSpawns() {
        val code = """
            $setupCode

            perform CoopAsync {cont = continuation::new {}} in do
                let outer = Async.spawn {f = {} ->
                    perform CoopAsync {cont = continuation::new {}} in do
                        let inner = Async.spawn {f = {} -> 42}
                        Async.await {p = inner}
                }
                Async.await {p = outer}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    @Test
    fun testConcurrentComputations() {
        val code = """
            $setupCode

            perform CoopAsync {cont = continuation::new {}} in do
                let p1 = Async.spawn {f = {} -> 5 * 2}
                let p2 = Async.spawn {f = {} -> 10 + 3}
                let p3 = Async.spawn {f = {} -> 7 * 3}

                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}

                r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(FunkInt(44), result)
    }

    // =============================================================================
    // Pipeline Patterns
    // =============================================================================

    @Test
    fun testSimplePipeline() {
        val code = """
            $setupCode

            perform CoopAsync {cont = continuation::new {}} in do
                let stage1 = Async.spawn {f = {} -> 10}
                let result1 = Async.await {p = stage1}

                let stage2 = Async.spawn {f = {} -> result1 * 2}
                let result2 = Async.await {p = stage2}

                let stage3 = Async.spawn {f = {} -> result2 + 5}
                Async.await {p = stage3}
        """
        val result = eval(code)
        assertEquals(FunkInt(25), result)
    }

    @Test
    fun testFanOutFanIn() {
        val code = """
            $setupCode

            let spawn_workers: {remaining: Int, acc: Int} -> Int = {remaining, acc} ->
                if remaining <= 0
                then acc
                else perform CoopAsync {cont = continuation::new {}} in do
                    let p = Async.spawn {f = {} -> remaining * 2}
                    let result = Async.await {p = p}
                    spawn_workers {remaining = remaining - 1, acc = acc + result}

            spawn_workers {remaining = 5, acc = 0}
        """
        val result = eval(code)
        assertEquals(FunkInt(30), result)
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
                else perform CoopAsync {cont = continuation::new {}} in do
                    let p = Async.spawn {f = {} -> recursive_spawn {n = n - 1}}
                    let result = Async.await {p = p}
                    n + result

            recursive_spawn {n = 5}
        """
        val result = eval(code)
        assertEquals(FunkInt(15), result)
    }

    @Test
    fun testRecursiveParallel() {
        val code = """
            $setupCode

            let parallel_sum: {n: Int, acc: Int} -> Int = {n, acc} ->
                if n <= 0
                then acc
                else perform CoopAsync {cont = continuation::new {}} in do
                    let p = Async.spawn {f = {} -> n}
                    let result = Async.await {p = p}
                    parallel_sum {n = n - 1, acc = acc + result}

            parallel_sum {n = 10, acc = 0}
        """
        val result = eval(code)
        assertEquals(FunkInt(55), result)
    }

    // =============================================================================
    // Closures in Concurrent Contexts
    // =============================================================================

    @Test
    fun testMultipleClosures() {
        val code = """
            $setupCode

            let test = {} ->
                perform CoopAsync {cont = continuation::new {}} in do
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
        assertEquals(FunkInt(80), result)
    }

    @Test
    fun testNestedClosuresInSpawn() {
        val code = """
            $setupCode

            let makeAdder = {x} -> {y} -> x + y

            let test = {} ->
                perform CoopAsync {cont = continuation::new {}} in do
                    let add10 = makeAdder {x = 10}

                    let p1 = Async.spawn {f = {} -> add10 {y = 5}}
                    let p2 = Async.spawn {f = {} -> add10 {y = 15}}

                    let r1 = Async.await {p = p1}
                    let r2 = Async.await {p = p2}

                    r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(40), result)
    }

    @Test
    fun testConditionalConcurrency() {
        val code = """
            $setupCode

            let conditional: {use_async: Bool} -> Int = {use_async} ->
                if use_async
                then perform CoopAsync {cont = continuation::new {}} in do
                    let p = Async.spawn {f = {} -> 100}
                    Async.await {p = p}
                else 50

            let r1 = conditional {use_async = True}
            let r2 = conditional {use_async = False}
            r1 + r2
        """
        val result = eval(code)
        assertEquals(FunkInt(150), result)
    }

    // =============================================================================
    // Coop-Specific Tests (Deterministic Execution)
    // =============================================================================

    @Test
    fun testDeterministicExecutionOrder() {
        val code = """
            $setupCode

            perform CoopAsync {cont = continuation::new {}} in do
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}
                let p3 = Async.spawn {f = {} -> 3}

                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}

                r1 * 100 + r2 * 10 + r3
        """
        val result = eval(code)
        assertEquals(FunkInt(123), result)
    }

    @Test
    fun testTasksExecuteOnAwait() {
        val code = """
            $setupCode

            perform CoopAsync {cont = continuation::new {}} in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let p3 = Async.spawn {f = {} -> 30}

                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                let r3 = Async.await {p = p3}

                r1 + r2 + r3
        """
        val result = eval(code)
        assertEquals(FunkInt(60), result)
    }

    // =============================================================================
    // Extended Async Functions (race, all, timeout, sleep, is_done)
    // =============================================================================

    @Test
    fun testRaceFirstCompletes() {
        val code = """
            $setupCode

            perform CoopAsync {cont = continuation::new {}} in do
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}
                let p3 = Async.spawn {f = {} -> 3}
                let result = Async.race {ps = [p1, p2, p3]}
                result >= 1
        """
        val result = eval(code)
        assertEquals(FunkBool(true), result)
    }

    @Test
    fun testAllCollectsResults() {
        val code = """
            $setupCode

            let sum: {xs: [Int]} -> Int = {xs} ->
                match xs is
                    [] -> 0
                    [h, ...t] -> h + sum {xs = t}

            perform CoopAsync {cont = continuation::new {}} in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let p3 = Async.spawn {f = {} -> 30}
                let results = Async.all {ps = [p1, p2, p3]}
                sum {xs = results}
        """
        val result = eval(code)
        assertEquals(FunkInt(60), result)
    }

    @Test
    fun testSleep() {
        val code = """
            $setupCode

            perform CoopAsync {cont = continuation::new {}} in do
                let _ = Async.sleep {ms = 10}
                42
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    @Test
    fun testIsDone() {
        val code = """
            $setupCode

            perform CoopAsync {cont = continuation::new {}} in do
                let p = Async.spawn {f = {} -> 42}
                let _ = Async.await {p = p}
                Async.is_done {p = p}
        """
        val result = eval(code)
        // In Coop, is_done always returns true since promises are always "ready"
        assertEquals(FunkBool(true), result)
    }

    @Test
    fun testTimeoutSuccess() {
        val code = """
            $setupCode

            perform CoopAsync {cont = continuation::new {}} in do
                let p = Async.spawn {f = {} -> 42}
                let result = Async.timeout {ms = 5000, p = p}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }
}
