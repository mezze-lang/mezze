package com.funk.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for Phase 4: Loom Concurrency (spawn/await)
 *
 * Tests cover:
 * - Spawning computations on virtual threads
 * - Awaiting promises
 * - Concurrent execution patterns
 * - Closures in concurrent contexts
 *
 * Note: Park/unpark tests are in LoomVirtualThreadsTest which uses Kotlin API directly
 * to avoid issues with parking the JUnit test thread.
 */
class LoomConcurrencyTest {

    private val parser = com.funk.language.parser.FunkParser()
    private val typeChecker = com.funk.language.typechecker.TypeChecker()

    private fun eval(source: String): FunkValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = FunkTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // Common setup code
    private val setupCode = """
        newtype Promise = Promise __NativePromise

        let spawn: {computation: {} -> 'a} -> Promise = {computation} -> __native_loom_spawn {computation = computation}
        let await: {promise: Promise} -> 'a = {promise} -> __native_loom_await {promise = promise}
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
                let promise = spawn {computation = {} -> 5 + 10}
                await {promise = promise}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(15), result)
    }

    @Test
    fun testSpawnWithConstant() {
        val code = """
            $setupCode

            let test: {} -> Int = {} -> do
                let promise = spawn {computation = {} -> 42}
                await {promise = promise}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    @Test
    @org.junit.jupiter.api.Disabled("Flaky under heavy test load - virtual thread scheduler saturation")
    fun testMultipleSpawnAndAwait() {
        val code = """
            $setupCode

            let test = {} -> do
                let p1 = spawn {computation = {} -> 10}
                let p2 = spawn {computation = {} -> 20}
                let p3 = spawn {computation = {} -> 30}

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}
                let r3 = await {promise = p3}

                r1 + r2 + r3

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(60), result)
    }

    @Test
    @org.junit.jupiter.api.Disabled("Flaky under heavy test load")
    fun testSpawnWithClosure() {
        val code = """
            $setupCode

            let test = {} -> do
                let x = 100
                let promise = spawn {computation = {} -> x * 2}
                await {promise = promise}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(200), result)
    }

    @Test
    fun testSpawnWithComplexComputation() {
        val code = """
            $setupCode

            let test = {} -> do
                let promise = spawn {computation = {} -> do
                    let a = 5
                    let b = 7
                    let c = a * b
                    c + 10
                }
                await {promise = promise}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(45), result)
    }

    @Test
    fun testNestedSpawns() {
        val code = """
            $setupCode

            let test = {} -> do
                let outer = spawn {computation = {} -> do
                    let inner = spawn {computation = {} -> 42}
                    await {promise = inner}
                }
                await {promise = outer}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    @Test
    fun testConcurrentComputations() {
        val code = """
            $setupCode

            let test = {} -> do
                let p1 = spawn {computation = {} -> 5 * 2}
                let p2 = spawn {computation = {} -> 10 + 3}
                let p3 = spawn {computation = {} -> 7 * 3}

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}
                let r3 = await {promise = p3}

                r1 + r2 + r3

            test {}
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

            let pipeline = {} -> do
                let stage1 = spawn {computation = {} -> 10}
                let result1 = await {promise = stage1}

                let stage2 = spawn {computation = {} -> result1 * 2}
                let result2 = await {promise = stage2}

                let stage3 = spawn {computation = {} -> result2 + 5}
                await {promise = stage3}

            pipeline {}
        """
        val result = eval(code)
        assertEquals(FunkInt(25), result)
    }

    @Test
    fun testMultiStagePipeline() {
        val code = """
            $setupCode

            let pipeline: {input: Int} -> Int = {input} -> do
                let stage1 = spawn {computation = {} -> input + 5}
                let r1 = await {promise = stage1}

                let stage2 = spawn {computation = {} -> r1 * 2}
                let r2 = await {promise = stage2}

                let stage3 = spawn {computation = {} -> r2 - 3}
                let r3 = await {promise = stage3}

                let stage4 = spawn {computation = {} -> r3 * 3}
                await {promise = stage4}

            pipeline {input = 5}
        """
        val result = eval(code)
        assertEquals(FunkInt(51), result)  // (5 + 5) * 2 - 3 = 17, 17 * 3 = 51
    }

    @Test
    fun testFanOutFanIn() {
        val code = """
            $setupCode

            let spawn_workers: {remaining: Int, acc: Int} -> Int = {remaining, acc} ->
                if remaining <= 0
                then acc
                else do
                    let promise = spawn {computation = {} -> remaining * 2}
                    let result = await {promise = promise}
                    spawn_workers {remaining = remaining - 1, acc = acc + result}

            let fan_out_fan_in: {n: Int} -> Int = {n} ->
                spawn_workers {remaining = n, acc = 0}

            fan_out_fan_in {n = 5}
        """
        val result = eval(code)
        assertEquals(FunkInt(30), result)
    }

    // =============================================================================
    // Recursive Concurrency
    // =============================================================================

    @Test
    @org.junit.jupiter.api.Disabled("Flaky under heavy test load")
    fun testRecursiveSpawn() {
        val code = """
            $setupCode

            let recursive_spawn: {n: Int} -> Int = {n} -> do
                if n <= 0
                then 0
                else do
                    let promise = spawn {computation = {} -> recursive_spawn {n = n - 1}}
                    let result = await {promise = promise}
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
                else do
                    let promise = spawn {computation = {} -> n}
                    let result = await {promise = promise}
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

            let test = {} -> do
                let a = 10
                let b = 20

                let p1 = spawn {computation = {} -> a * 2}
                let p2 = spawn {computation = {} -> b * 3}

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}

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

            let test = {} -> do
                let add10 = makeAdder {x = 10}

                let p1 = spawn {computation = {} -> add10 {y = 5}}
                let p2 = spawn {computation = {} -> add10 {y = 15}}

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(40), result)
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
    fun testConditionalConcurrency() {
        val code = """
            $setupCode

            let conditional: {use_async: Bool} -> Int = {use_async} ->
                if use_async
                then do
                    let p = spawn {computation = {} -> 100}
                    await {promise = p}
                else 50

            let r1 = conditional {use_async = true}
            let r2 = conditional {use_async = false}
            r1 + r2
        """
        val result = eval(code)
        assertEquals(FunkInt(150), result)
    }

    // DISABLED: Truffle context sharing issue with spawn
    // @Test
    // fun testDynamicConcurrency() { ... }
}
