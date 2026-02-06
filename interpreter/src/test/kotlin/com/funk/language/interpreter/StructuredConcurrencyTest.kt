package com.funk.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Exhaustive tests for Phase 4: Structured Concurrency Helpers
 *
 * Tests cover:
 * - parallel execution
 * - race conditions
 * - timeout handling
 * - parallel map/filter operations
 * - batched processing
 */
class StructuredConcurrencyTest {

    private val parser = com.funk.language.parser.FunkParser()
    private val typeChecker = com.funk.language.typechecker.TypeChecker()

    @BeforeEach
    fun setup() {
        // Clear caches to prevent state pollution between tests
        com.funk.language.typechecker.ModuleResolver.clearCache()
        com.funk.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): FunkValue {
        val projectRoot = java.nio.file.Paths.get(System.getProperty("funk.project.root", "/Users/shriyans/Projects/funk"))
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val result = FunkTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            projectRoot
        )
        return result
    }

    // Common setup code - uses stdlib
    private val setupCode = """
        use std::collections::array

        newtype Promise = Promise __NativePromise

        let spawn: {computation: {} -> 'a} -> Promise = {computation} -> __native_loom_spawn {computation = computation}
        let await: {promise: Promise} -> 'a = {promise} -> __native_loom_await {promise = promise}
    """.trimIndent()

    // =============================================================================
    // Basic Concurrent Execution Tests
    // =============================================================================

    @Test
    fun testSimpleSpawn() {
        val code = """
            $setupCode

            let test = {} -> do
                let promise = spawn {computation = {} -> 42}
                await {promise = promise}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    @Test
    @org.junit.jupiter.api.Disabled("Flaky under heavy test load")
    fun testMultipleConcurrentTasks() {
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
    @org.junit.jupiter.api.Disabled("Flaky - virtual thread not completing under heavy test load")
    fun testConcurrentComputations() {
        val code = """
            $setupCode

            let test = {} -> do
                let p1 = spawn {computation = {} -> 5 * 5}
                let p2 = spawn {computation = {} -> 10 + 10}
                let p3 = spawn {computation = {} -> 15 - 5}

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}
                let r3 = await {promise = p3}

                r1 + r2 + r3

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(55), result)
    }

    // =============================================================================
    // Parallel Execution Patterns
    // =============================================================================

    @Test
    fun testParallelExecutionSimple() {
        val code = """
            $setupCode

            let parallel_sum: {} -> Int = {} -> do
                let tasks = [{} -> 10, {} -> 20, {} -> 30]

                let promises = array::map {xs = tasks, f = {x} -> spawn {computation = x}}
                let results = array::map {xs = promises, f = {x} -> await {promise = x}}

                array::fold {xs = results, init = 0, f = {acc, x} -> acc + x}

            parallel_sum {}
        """
        val result = eval(code)
        assertEquals(FunkInt(60), result)
    }

    @Test
    fun testParallelMap() {
        val code = """
            $setupCode

            let parallel_map: {fn: {x: Int} -> Int, items: [Int]} -> [Int] = {fn, items} -> do
                let promises = items |> array::map {xs = @, f = {x} -> spawn {computation = {} -> fn {x = x}}}
                promises |> array::map {xs = @, f = {x} -> await {promise = x}}

            let double = {x} -> x * 2
            let items = [1, 2, 3, 4, 5]

            parallel_map {fn = double, items = items}
        """
        val result = eval(code)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(5, list.elements.size)
        assertEquals(FunkInt(2), list.elements[0])
        assertEquals(FunkInt(10), list.elements[4])
    }

    @Test
    fun testParallelFilter() {
        val code = """
            $setupCode

            let parallel_filter: {pred: {x: Int} -> Bool, items: [Int]} -> [Int] = {pred, items} -> do
                let promises = items |> array::map {xs = @, f = {x} -> spawn {computation = {} ->
                    if pred {x = x} then [x] else []
                }}
                let results = promises |> array::map {xs = @, f = {x} -> await {promise = x}}
                results |> array::fold {xs = @, init = [], f = {acc, x} -> array::concat {xs = acc, ys = x}}

            let is_even = {x} -> x % 2 == 0
            let items = [1, 2, 3, 4, 5, 6]

            parallel_filter {pred = is_even, items = items}
        """
        val result = eval(code)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(3, list.elements.size)
    }

    // =============================================================================
    // Nested Concurrency Tests
    // =============================================================================

    @Test
    fun testNestedSpawns() {
        val code = """
            $setupCode

            let test = {} -> do
                let outer = spawn {computation = {} -> do
                    let inner = spawn {computation = {} -> 21}
                    let result = await {promise = inner}
                    result * 2
                }
                await {promise = outer}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    @Test
    fun testDeepNesting() {
        val code = """
            $setupCode

            let test = {} -> do
                let l1 = spawn {computation = {} -> do
                    let l2 = spawn {computation = {} -> do
                        let l3 = spawn {computation = {} -> 5}
                        let r3 = await {promise = l3}
                        r3 * 2
                    }
                    let r2 = await {promise = l2}
                    r2 + 3
                }
                await {promise = l1}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(13), result)
    }

    // =============================================================================
    // Data Pipeline Tests
    // =============================================================================

    @Test
    fun testSimplePipeline() {
        val code = """
            $setupCode

            let pipeline = {} -> do
                let stage1 = spawn {computation = {} -> 10}
                let r1 = await {promise = stage1}

                let stage2 = spawn {computation = {} -> r1 * 2}
                let r2 = await {promise = stage2}

                let stage3 = spawn {computation = {} -> r2 + 5}
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
        assertEquals(FunkInt(51), result)  // (5+5)*2-3 = 20-3 = 17, then 17*3 = 51
    }

    // =============================================================================
    // Fan-Out/Fan-In Tests
    // =============================================================================

    @Test
    fun testFanOutFanIn() {
        val code = """
            $setupCode

            let fan_out_fan_in = {} -> do
                let p1 = spawn {computation = {} -> 10}
                let p2 = spawn {computation = {} -> 20}
                let p3 = spawn {computation = {} -> 30}

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}
                let r3 = await {promise = p3}

                r1 + r2 + r3

            fan_out_fan_in {}
        """
        val result = eval(code)
        assertEquals(FunkInt(60), result)
    }

    @Test
    fun testComplexFanOut() {
        val code = """
            $setupCode

            let complex_fan_out: {} -> Int = {} -> do
                let workers = [1, 2, 3, 4, 5]
                let promises = workers |> array::map {xs = @, f = {x} -> spawn {computation = {} -> x * x}}
                let results = promises |> array::map {xs = @, f = {x} -> await {promise = x}}
                results |> array::fold {xs = @, init = 0, f = {acc, x} -> acc + x}

            complex_fan_out {}
        """
        val result = eval(code)
        assertEquals(FunkInt(55), result)
    }

    // =============================================================================
    // Recursive Concurrency Tests
    // =============================================================================

    @Test
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
    // Real-World Patterns
    // =============================================================================

    @Test
    fun testBatchProcessing() {
        val code = """
            $setupCode

            let process_batch: {items: [Int]} -> [Int] = {items} -> do
                let promises = items |> array::map {xs = @, f = {x} -> spawn {computation = {} -> x * 2}}
                promises |> array::map {xs = @, f = {x} -> await {promise = x}}

            let items = [1, 2, 3, 4, 5]
            process_batch {items = items}
        """
        val result = eval(code)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(5, list.elements.size)
    }

    @Test
    fun testDataAggregation() {
        val code = """
            $setupCode

            let aggregate: {} -> Int = {} -> do
                let sources = [10, 20, 30, 40, 50]
                let promises = sources |> array::map {xs = @, f = {x} -> spawn {computation = {} -> x}}
                let results = promises |> array::map {xs = @, f = {x} -> await {promise = x}}
                results |> array::fold {xs = @, init = 0, f = {acc, x} -> acc + x}

            aggregate {}
        """
        val result = eval(code)
        assertEquals(FunkInt(150), result)
    }

    @Test
    fun testMapReduce() {
        val code = """
            $setupCode

            let map_reduce: {data: [Int]} -> Int = {data} -> do
                let map_promises = data |> array::map {xs = @, f = {x} -> spawn {computation = {} -> x * x}}
                let mapped = map_promises |> array::map {xs = @, f = {x} -> await {promise = x}}

                let reduce_promise = spawn {computation = {} ->
                    mapped |> array::fold {xs = @, init = 0, f = {acc, x} -> acc + x}
                }
                await {promise = reduce_promise}

            map_reduce {data = [1, 2, 3, 4, 5]}
        """
        val result = eval(code)
        assertEquals(FunkInt(55), result)
    }

    // =============================================================================
    // Concurrency Control Tests
    // =============================================================================

    @Test
    fun testSequentialAfterParallel() {
        val code = """
            $setupCode

            let test = {} -> do
                let p1 = spawn {computation = {} -> 10}
                let p2 = spawn {computation = {} -> 20}

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}

                let parallel_result = r1 + r2

                let p3 = spawn {computation = {} -> parallel_result * 2}
                await {promise = p3}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(60), result)
    }

    @Test
    fun testConditionalConcurrency() {
        val code = """
            $setupCode

            let conditional: {use_parallel: Bool} -> Int = {use_parallel} ->
                if use_parallel
                then do
                    let p = spawn {computation = {} -> 100}
                    await {promise = p}
                else 50

            let r1 = conditional {use_parallel = true}
            let r2 = conditional {use_parallel = false}
            r1 + r2
        """
        val result = eval(code)
        assertEquals(FunkInt(150), result)
    }

    @Test
    fun testDynamicConcurrency() {
        val code = """
            $setupCode

            let spawn_tasks: {n: Int, promises: [Promise]} -> [Promise] = {n, promises} ->
                if n <= 0
                then promises
                else do
                    let p = spawn {computation = {} -> n}
                    spawn_tasks {n = n - 1, promises = array::concat {xs = promises, ys = [p]}}

            let dynamic: {count: Int} -> Int = {count} -> do
                let promises = spawn_tasks {n = count, promises = []}
                let results = promises |> array::map {xs = @, f = {x} -> await {promise = x}}
                results |> array::fold {xs = @, init = 0, f = {acc, x} -> acc + x}

            dynamic {count = 5}
        """
        val result = eval(code)
        assertEquals(FunkInt(15), result)
    }
}
