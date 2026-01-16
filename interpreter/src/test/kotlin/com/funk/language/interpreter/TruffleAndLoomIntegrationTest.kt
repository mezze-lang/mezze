package com.funk.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for Phase 3 (Truffle) + Phase 4 (Loom)
 *
 * Tests cover:
 * - Truffle-optimized functions running on Loom threads
 * - Closures in concurrent contexts
 * - Effects with async operations
 * - Pattern matching with concurrent data
 * - Complex real-world scenarios combining both phases
 */
class TruffleAndLoomIntegrationTest {

    private val parser = com.funk.language.parser.FunkParser()
    private val typeChecker = com.funk.language.typechecker.TypeChecker()

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

    private val setupCode = """
        use std::collections::array

        newtype Promise = Promise __NativePromise
        newtype Thread = Thread __NativeThread

        let spawn: {computation: {} -> 'a} -> Promise = {computation} -> __native_loom_spawn {computation = computation}
        let await: {promise: Promise} -> 'a = {promise} -> __native_loom_await {promise = promise}
        let park: {} -> {} = {} -> __native_loom_park {}
        let unpark: {thread: Thread} -> {} = {thread} -> __native_loom_unpark {thread = thread}
        let current_thread: {} -> Thread = {} -> __native_loom_current_thread {}
    """.trimIndent()

    // =============================================================================
    // Truffle Functions + Loom Concurrency
    // =============================================================================

    @Test
    fun testTruffleFunctionInLoomThread() {
        val code = """
            $setupCode

            let factorial = {n} ->
                if n <= 1
                then 1
                else n * factorial {n = n - 1}

            let test = {} -> do
                let promise = spawn {computation = {} -> factorial {n = 5}}
                await {promise = promise}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(120), result)
    }

    @Test
    fun testConcurrentRecursiveFunctions() {
        val code = """
            $setupCode

            let fib = {n} ->
                if n <= 1
                then n
                else fib {n = n - 1} + fib {n = n - 2}

            let test = {} -> do
                let p1 = spawn {computation = {} -> fib {n = 7}}
                let p2 = spawn {computation = {} -> fib {n = 6}}
                let p3 = spawn {computation = {} -> fib {n = 5}}

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}
                let r3 = await {promise = p3}

                r1 + r2 + r3

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(26), result) // fib(7)=13, fib(6)=8, fib(5)=5, sum=13+8+5=26
    }

    @Test
    fun testHigherOrderFunctionsConcurrent() {
        val code = """
            $setupCode

            let test: {} -> [Int] = {} -> do
                let double = {x} -> x * 2
                let data = [1, 2, 3, 4, 5]

                let promise = spawn {computation = {} -> array::map {f = double, xs = data}}
                await {promise = promise}

            test {}
        """
        val result = eval(code)
        assertTrue(result is FunkArrayList)
        val list = result as FunkArrayList
        assertEquals(5, list.size())
        assertEquals(2L, (list.get(0) as FunkInt).value)
        assertEquals(10L, (list.get(4) as FunkInt).value)
    }

    // =============================================================================
    // Closures in Concurrent Contexts
    // =============================================================================

    @Test
    fun testClosureInSpawnedThread() {
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
    fun testNestedClosuresWithConcurrency() {
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

    @Test
    fun testClosureFactoryWithConcurrency() {
        val code = """
            $setupCode

            let makeMultiplier = {factor} -> {value} -> value * factor

            let test = {} -> do
                let double = makeMultiplier {factor = 2}
                let triple = makeMultiplier {factor = 3}

                let p1 = spawn {computation = {} -> double {value = 10}}
                let p2 = spawn {computation = {} -> triple {value = 10}}

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(50), result)
    }

    @Test
    fun testSharedClosureState() {
        val code = """
            $setupCode

            let test = {} -> do
                let base_value = 100
                let compute = {offset} -> base_value + offset

                let p1 = spawn {computation = {} -> compute {offset = 1}}
                let p2 = spawn {computation = {} -> compute {offset = 2}}
                let p3 = spawn {computation = {} -> compute {offset = 3}}

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}
                let r3 = await {promise = p3}

                r1 + r2 + r3

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(306), result) // 101 + 102 + 103 = 306
    }

    // =============================================================================
    // Effects with Async Operations
    // =============================================================================

    @Test
    fun testEffectsInConcurrentContext() {
        val code = """
            $setupCode

            effect Math where {
                add: {x: Int, y: Int} -> Int <Math>
            }

            impl Math for Calculator where {
                add: {x: Int, y: Int} -> Int = {x, y} -> x + y
            }

            let test = {} -> do
                let promise = spawn {computation = {} ->
                    perform Calculator in Math.add {x = 10, y = 32}
                }
                await {promise = promise}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    @Test
    fun testConcurrentEffectCalls() {
        val code = """
            $setupCode

            effect Math where {
                mul: {x: Int, y: Int} -> Int <Math>
            }

            impl Math for Calculator where {
                mul: {x: Int, y: Int} -> Int = {x, y} -> x * y
            }

            let test = {} -> do
                let p1 = spawn {computation = {} ->
                    perform Calculator in Math.mul {x = 5, y = 2}
                }
                let p2 = spawn {computation = {} ->
                    perform Calculator in Math.mul {x = 3, y = 4}
                }

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(22), result)
    }

    // =============================================================================
    // Pattern Matching with Concurrent Data
    // =============================================================================

    @Test
    fun testPatternMatchingInConcurrency() {
        val code = """
            $setupCode

            let processValue = {v} ->
                match v is
                    Red -> 1
                    Blue {val} -> val * 2
                    _ -> 0

            let test = {} -> do
                let p1 = spawn {computation = {} -> processValue {v = Red}}
                let p2 = spawn {computation = {} -> processValue {v = Blue {val = 10}}}

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(21), result)
    }

    @Test
    fun testConcurrentDataProcessingWithPatternMatch() {
        val code = """
            $setupCode

            let process = {data} ->
                array::fold {xs = data, init = 0, f = {acc, x} -> acc + x}

            let test = {} -> do
                let data1 = [1, 2, 3]
                let data2 = [4, 5, 6]

                let p1 = spawn {computation = {} -> process {data = data1}}
                let p2 = spawn {computation = {} -> process {data = data2}}

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(21), result)
    }

    // =============================================================================
    // Record Operations with Concurrency
    // =============================================================================

    @Test
    fun testRecordAccessInConcurrency() {
        val code = """
            $setupCode

            let test = {} -> do
                let point = {x = 10, y = 20}

                let p1 = spawn {computation = {} -> point.x}
                let p2 = spawn {computation = {} -> point.y}

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(30), result)
    }

    @Test
    fun testRecordUpdateWithConcurrency() {
        val code = """
            $setupCode

            let test = {} -> do
                let point = {x = 5, y = 10}

                let p1 = spawn {computation = {} -> do
                    let updated = {...point, x = 20}
                    updated.x + updated.y
                }

                let p2 = spawn {computation = {} -> do
                    let updated = {...point, y = 30}
                    updated.x + updated.y
                }

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(65), result)
    }

    // =============================================================================
    // Complex Real-World Scenarios
    // =============================================================================

    @Test
    fun testParallelDataPipeline() {
        val code = """
            $setupCode

            let pipeline: {data: [Int]} -> Int = {data} -> do
                let promises = data |> array::map {xs = @, f = {x} -> spawn {computation = {} -> x * 2}}
                let results = promises |> array::map {xs = @, f = {x} -> await {promise = x}}
                results |> array::fold {xs = @, init = 0, f = {acc, x} -> acc + x}

            pipeline {data = [1, 2, 3, 4, 5]}
        """
        val result = eval(code)
        // 1*2 + 2*2 + 3*2 + 4*2 + 5*2 = 2 + 4 + 6 + 8 + 10 = 30
        assertEquals(FunkInt(30), result)
    }

    @Test
    fun testConcurrentMapReduce() {
        val code = """
            $setupCode

            let map_phase: {data: [Int]} -> [Int] = {data} -> do
                let promises = data |> array::map {xs = @, f = {x} -> spawn {computation = {} -> x * x}}
                promises |> array::map {xs = @, f = {x} -> await {promise = x}}

            let reduce_phase: {data: [Int]} -> Int = {data} ->
                data |> array::fold {xs = @, init = 0, f = {acc, x} -> acc + x}

            let test = {} -> do
                let data = [1, 2, 3, 4, 5]
                let mapped = map_phase {data = data}

                let reduce_promise = spawn {computation = {} -> reduce_phase {data = mapped}}
                await {promise = reduce_promise}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(55), result) // 1+4+9+16+25 = 55
    }

    @Test
    fun testNestedConcurrencyWithClosures() {
        val code = """
            $setupCode

            let test = {} -> do
                let outer_value = 100

                let outer_promise = spawn {computation = {} -> do
                    let inner_value = 10

                    let inner_promise = spawn {computation = {} ->
                        outer_value + inner_value
                    }

                    await {promise = inner_promise}
                }

                await {promise = outer_promise}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(110), result)
    }

    @Test
    fun testConcurrentEffectsWithClosures() {
        val code = """
            $setupCode

            effect Compute where {
                calculate: {x: Int, y: Int} -> Int <Compute>
            }

            impl Compute for SimpleCalc where {
                calculate: {x: Int, y: Int} -> Int = {x, y} -> x + y
            }

            let test = {} -> do
                let base = 100

                let p1 = spawn {computation = {} ->
                    perform SimpleCalc in Compute.calculate {x = base, y = 10}
                }

                let p2 = spawn {computation = {} ->
                    perform SimpleCalc in Compute.calculate {x = base, y = 20}
                }

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(230), result)
    }

    @Test
    fun testComplexDataFlowWithAllFeatures() {
        val code = """
            $setupCode

            effect Logger where {
                log: {msg: String} -> {} <Logger>
            }

            impl Logger for NoOpLogger where {
                log: {msg: String} -> {} = {msg} -> {}
            }

            let processItem = {item} ->
                match item is
                    Red -> 1
                    Blue {val} -> val
                    _ -> 0

            let test = {} -> do
                let items = [Red, Blue {val = 10}, Blue {val = 20}]

                let promises = items |> array::map {xs = @, f = {x} ->
                    spawn {computation = {} -> processItem {item = x}}
                }

                let results = promises |> array::map {xs = @, f = {x} -> await {promise = x}}

                results |> array::fold {xs = @, init = 0, f = {acc, x} -> acc + x}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(31), result) // 1 + 10 + 20
    }

    @Test
    fun testConcurrentStateManipulation() {
        // Tests park/unpark/current_thread with true multi-threaded execution.
        // Worker threads unpark the main thread, demonstrating proper thread coordination.
        val code = """
            $setupCode

            let test = {} -> do
                let main_thread = current_thread {}

                let worker = {id} -> do
                    unpark {thread = main_thread}
                    id * 2

                let p1 = spawn {computation = {} -> worker {id = 10}}
                let p2 = spawn {computation = {} -> worker {id = 20}}
                let p3 = spawn {computation = {} -> worker {id = 30}}

                park {}
                park {}
                park {}

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}
                let r3 = await {promise = p3}

                r1 + r2 + r3

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(120), result) // 20 + 40 + 60 = 120
    }

    @Test
    fun testRecursiveConcurrentProcessing() {
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

    @Test
    fun testComplexClosureChainWithConcurrency() {
        val code = """
            $setupCode

            let makeAdder = {x} -> {y} -> {z} -> x + y + z

            let test = {} -> do
                let add10 = makeAdder {x = 10}
                let add10_20 = add10 {y = 20}

                let p1 = spawn {computation = {} -> add10_20 {z = 5}}
                let p2 = spawn {computation = {} -> add10_20 {z = 15}}

                let r1 = await {promise = p1}
                let r2 = await {promise = p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(80), result) // (10+20+5) + (10+20+15) = 35 + 45 = 80
    }

    @Test
    fun testFullStackIntegration() {
        val code = """
            $setupCode

            effect Math where {
                square: {x: Int} -> Int <Math>
            }

            impl Math for Calculator where {
                square: {x: Int} -> Int = {x} -> x * x
            }

            let processInParallel = {items} -> do
                let promises = items |> array::map {xs = @, f = {x} ->
                    spawn {computation = {} ->
                        perform Calculator in Math.square {x = x}
                    }}

                promises |> array::map {xs = @, f = {x} -> await {promise = x}}

            let test = {} -> do
                let data = [1, 2, 3, 4, 5]
                let results = processInParallel {items = data}
                results |> array::fold {xs = @, init = 0, f = {acc, x} -> acc + x}

            test {}
        """
        val result = eval(code)
        assertEquals(FunkInt(55), result) // 1+4+9+16+25 = 55
    }
}
