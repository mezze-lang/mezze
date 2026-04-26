package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for Phase 4: LoomAsync Concurrency (spawn/await)
 *
 * Tests cover:
 * - Spawning computations on virtual threads
 * - Awaiting promises
 * - Concurrent execution patterns
 * - Closures in concurrent contexts
 *
 * Note: Park/unpark tests are in LoomVirtualThreadsTest which uses Kotlin API directly
 * to avoid issues with parking the JUnit test thread.
 *
 * IMPORTANT: Tests are run sequentially (@Execution(SAME_THREAD)) to avoid virtual thread
 * scheduler saturation when many tests spawn threads concurrently.
 *
 * Migrated from V1 to V2 with unboxed values.
 */
@Execution(ExecutionMode.SAME_THREAD)
class LoomConcurrencyTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Common setup code - uses same field names as std::async
    private val setupCode = """
        newtype Promise 'a = Promise __NativePromise

        let spawn: {f: {} -> 'a} -> Promise 'a = {f} -> __native_loom_spawn {f = f}
        let await: {p: Promise 'a} -> 'a = {p} -> __native_loom_await {p = p}
    """.trimIndent()

    // Stdlib functions for tests that need them
    private val stdlibCode = """
        let map = {f, xs} -> match xs is [] -> [] | [head, ...tail] -> [f head] ++ map {f = f, xs = tail}
        let foldl = {f, init, xs} -> match xs is [] -> init | [head, ...tail] -> foldl {f = f, init = f init head, xs = tail}
    """.trimIndent()

    // =============================================================================
    // Basic Spawn/Await Tests
    // =============================================================================

    @Test
    fun testSimpleSpawnAndAwait() {
        val code = """
            $setupCode

            let test = {} -> do
                let promise = spawn {f = {} -> 5 + 10}
                await {p =promise}

            test {}
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun testSpawnWithConstant() {
        val code = """
            $setupCode

            let test: {} -> Int = {} -> do
                let promise = spawn {f = {} -> 42}
                await {p =promise}

            test {}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun testMultipleSpawnAndAwait() {
        val code = """
            $setupCode

            let test = {} -> do
                let p1 = spawn {f = {} -> 10}
                let p2 = spawn {f = {} -> 20}
                let p3 = spawn {f = {} -> 30}

                let r1 = await {p =p1}
                let r2 = await {p =p2}
                let r3 = await {p =p3}

                r1 + r2 + r3

            test {}
        """
        val result = eval(code)
        assertEquals(60L, result)
    }

    @Test
    fun testSpawnWithClosure() {
        val code = """
            $setupCode

            let test = {} -> do
                let x = 100
                let promise = spawn {f = {} -> x * 2}
                await {p =promise}

            test {}
        """
        val result = eval(code)
        assertEquals(200L, result)
    }

    @Test
    fun testSpawnWithComplexComputation() {
        val code = """
            $setupCode

            let test = {} -> do
                let promise = spawn {f = {} -> do
                    let a = 5
                    let b = 7
                    let c = a * b
                    c + 10
                }
                await {p =promise}

            test {}
        """
        val result = eval(code)
        assertEquals(45L, result)
    }

    @Test
    fun testNestedSpawns() {
        val code = """
            $setupCode

            let test = {} -> do
                let outer = spawn {f = {} -> do
                    let inner = spawn {f = {} -> 42}
                    await {p =inner}
                }
                await {p =outer}

            test {}
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun testConcurrentComputations() {
        val code = """
            $setupCode

            let test = {} -> do
                let p1 = spawn {f = {} -> 5 * 2}
                let p2 = spawn {f = {} -> 10 + 3}
                let p3 = spawn {f = {} -> 7 * 3}

                let r1 = await {p =p1}
                let r2 = await {p =p2}
                let r3 = await {p =p3}

                r1 + r2 + r3

            test {}
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

            let pipeline = {} -> do
                let stage1 = spawn {f = {} -> 10}
                let result1 = await {p =stage1}

                let stage2 = spawn {f = {} -> result1 * 2}
                let result2 = await {p =stage2}

                let stage3 = spawn {f = {} -> result2 + 5}
                await {p =stage3}

            pipeline {}
        """
        val result = eval(code)
        assertEquals(25L, result)
    }

    @Test
    fun testMultiStagePipeline() {
        val code = """
            $setupCode

            let pipeline: {input: Int} -> Int = {input} -> do
                let stage1 = spawn {f = {} -> input + 5}
                let r1 = await {p =stage1}

                let stage2 = spawn {f = {} -> r1 * 2}
                let r2 = await {p =stage2}

                let stage3 = spawn {f = {} -> r2 - 3}
                let r3 = await {p =stage3}

                let stage4 = spawn {f = {} -> r3 * 3}
                await {p =stage4}

            pipeline {input = 5}
        """
        val result = eval(code)
        assertEquals(51L, result)  // (5 + 5) * 2 - 3 = 17, 17 * 3 = 51
    }

    @Test
    fun testFanOutFanIn() {
        val code = """
            $setupCode

            let spawn_workers: {remaining: Int, acc: Int} -> Int = {remaining, acc} ->
                if remaining <= 0
                then acc
                else do
                    let promise = spawn {f = {} -> remaining * 2}
                    let result = await {p =promise}
                    spawn_workers {remaining = remaining - 1, acc = acc + result}

            let fan_out_fan_in: {n: Int} -> Int = {n} ->
                spawn_workers {remaining = n, acc = 0}

            fan_out_fan_in {n = 5}
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    // =============================================================================
    // Recursive Concurrency
    // =============================================================================

    @Test
    // Previously disabled for flakiness - now fixed with sequential execution
    fun testRecursiveSpawn() {
        val code = """
            $setupCode

            let recursive_spawn: {n: Int} -> Int = {n} -> do
                if n <= 0
                then 0
                else do
                    let promise = spawn {f = {} -> recursive_spawn {n = n - 1}}
                    let result = await {p =promise}
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
                else do
                    let promise = spawn {f = {} -> n}
                    let result = await {p =promise}
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
    // Test spawning multiple concurrent tasks with closure capture
    fun testMultipleClosures() {
        val code = """
            $setupCode

            let test = {} -> do
                let a = 10
                let b = 20

                # Closures capture a and b from outer scope
                let p1 = spawn {f = {} -> a * 2}
                let p2 = spawn {f = {} -> b * 3}

                let r1 = await {p = p1}
                let r2 = await {p = p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(80L, result)
    }

    @Test
    // Test higher-order functions with closure capture in concurrent execution
    fun testNestedClosuresInSpawn() {
        val code = """
            $setupCode

            # Higher-order function that returns a closure capturing x
            let makeAdder = {x} -> {y} -> x + y

            let test = {} -> do
                # add10 is a closure that captures x=10
                let add10 = makeAdder {x = 10}

                # Spawn tasks that use the captured closure
                let p1 = spawn {f = {} -> add10 {y = 5}}
                let p2 = spawn {f = {} -> add10 {y = 15}}

                let r1 = await {p = p1}
                let r2 = await {p = p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(40L, result)
    }

    // DISABLED: ref syntax needs investigation
    // @Test
    // fun testSharedClosureState() { ... }

    // =============================================================================
    // Real-World Patterns
    // =============================================================================

    // DISABLED: Truffle context sharing issue with spawn
    // @Test
    // fun testConcurrentDataProcessing() { ... }

    @Test
    // Previously disabled for flakiness - now fixed with sequential execution
    fun testConditionalConcurrency() {
        val code = """
            $setupCode

            let conditional: {use_async: Bool} -> Int = {use_async} ->
                if use_async
                then do
                    let p = spawn {f = {} -> 100}
                    await {p =p}
                else 50

            let r1 = conditional {use_async = True}
            let r2 = conditional {use_async = False}
            r1 + r2
        """
        val result = eval(code)
        assertEquals(150L, result)
    }

    // DISABLED: Truffle context sharing issue with spawn
    // @Test
    // fun testDynamicConcurrency() { ... }
}
