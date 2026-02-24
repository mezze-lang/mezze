package com.mezze.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
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

    private val parser = com.mezze.language.parser.MezzeParser()
    private val typeChecker = com.mezze.language.typechecker.TypeChecker()

    @BeforeEach
    fun setup() {
        // Clear caches to prevent state pollution between tests
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val projectRoot = java.nio.file.Paths.get(System.getProperty("mezze.project.root", TestUtils.findProjectRoot().toString()))
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val result = MezzeTestHelper.execute(
            typeCheckResult.program,
            typeCheckResult.monomorphicInstances,
            projectRoot
        )
        return result
    }

    private val setupCode = """
        use std::collections::array

        newtype Promise 'a = Promise __NativePromise

        let spawn: {f: {} -> 'a} -> Promise 'a = {f} -> __native_loom_spawn {f = f}
        let await: {p: Promise 'a} -> 'a = {p} -> __native_loom_await {p = p}
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
                let promise = spawn {f = {} -> factorial {n = 5}}
                await {p =promise}

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(120), result)
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
                let p1 = spawn {f = {} -> fib {n = 7}}
                let p2 = spawn {f = {} -> fib {n = 6}}
                let p3 = spawn {f = {} -> fib {n = 5}}

                let r1 = await {p =p1}
                let r2 = await {p =p2}
                let r3 = await {p =p3}

                r1 + r2 + r3

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(26), result) // fib(7)=13, fib(6)=8, fib(5)=5, sum=13+8+5=26
    }

    @Test
    fun testHigherOrderFunctionsConcurrent() {
        val code = """
            $setupCode

            let test: {} -> [Int] = {} -> do
                let double = {x} -> x * 2
                let data = [1, 2, 3, 4, 5]

                let promise = spawn {f = {} -> array::map {f = double, xs = data}}
                await {p =promise}

            test {}
        """
        val result = eval(code)
        assertTrue(result is MezzeArrayList)
        val list = result as MezzeArrayList
        assertEquals(5, list.size())
        assertEquals(2L, (list.get(0) as MezzeInt).value)
        assertEquals(10L, (list.get(4) as MezzeInt).value)
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
                let promise = spawn {f = {} -> x * 2}
                await {p =promise}

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(200), result)
    }

    @Test
    fun testNestedClosuresWithConcurrency() {
        val code = """
            $setupCode

            let makeAdder = {x} -> {y} -> x + y

            let test = {} -> do
                let add10 = makeAdder {x = 10}

                let p1 = spawn {f = {} -> add10 {y = 5}}
                let p2 = spawn {f = {} -> add10 {y = 15}}

                let r1 = await {p =p1}
                let r2 = await {p =p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(40), result)
    }

    @Test
    fun testClosureFactoryWithConcurrency() {
        val code = """
            $setupCode

            let makeMultiplier = {factor} -> {value} -> value * factor

            let test = {} -> do
                let double = makeMultiplier {factor = 2}
                let triple = makeMultiplier {factor = 3}

                let p1 = spawn {f = {} -> double {value = 10}}
                let p2 = spawn {f = {} -> triple {value = 10}}

                let r1 = await {p =p1}
                let r2 = await {p =p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(50), result)
    }

    @Test
    fun testSharedClosureState() {
        val code = """
            $setupCode

            let test = {} -> do
                let base_value = 100
                let compute = {offset} -> base_value + offset

                let p1 = spawn {f = {} -> compute {offset = 1}}
                let p2 = spawn {f = {} -> compute {offset = 2}}
                let p3 = spawn {f = {} -> compute {offset = 3}}

                let r1 = await {p =p1}
                let r2 = await {p =p2}
                let r3 = await {p =p3}

                r1 + r2 + r3

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(306), result) // 101 + 102 + 103 = 306
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
                let promise = spawn {f = {} ->
                    perform Calculator in Math.add {x = 10, y = 32}
                }
                await {p =promise}

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(42), result)
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
                let p1 = spawn {f = {} ->
                    perform Calculator in Math.mul {x = 5, y = 2}
                }
                let p2 = spawn {f = {} ->
                    perform Calculator in Math.mul {x = 3, y = 4}
                }

                let r1 = await {p =p1}
                let r2 = await {p =p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(22), result)
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
                let p1 = spawn {f = {} -> processValue {v = Red}}
                let p2 = spawn {f = {} -> processValue {v = Blue {val = 10}}}

                let r1 = await {p =p1}
                let r2 = await {p =p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(21), result)
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

                let p1 = spawn {f = {} -> process {data = data1}}
                let p2 = spawn {f = {} -> process {data = data2}}

                let r1 = await {p =p1}
                let r2 = await {p =p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(21), result)
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

                let p1 = spawn {f = {} -> point.x}
                let p2 = spawn {f = {} -> point.y}

                let r1 = await {p =p1}
                let r2 = await {p =p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(30), result)
    }

    @Test
    fun testRecordUpdateWithConcurrency() {
        val code = """
            $setupCode

            let test = {} -> do
                let point = {x = 5, y = 10}

                let p1 = spawn {f = {} -> do
                    let updated = {...point, x = 20}
                    updated.x + updated.y
                }

                let p2 = spawn {f = {} -> do
                    let updated = {...point, y = 30}
                    updated.x + updated.y
                }

                let r1 = await {p =p1}
                let r2 = await {p =p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(65), result)
    }

    // =============================================================================
    // Complex Real-World Scenarios
    // =============================================================================

    @Test
    fun testParallelDataPipeline() {
        val code = """
            $setupCode

            let pipeline: {data: [Int]} -> Int = {data} -> do
                let promises = data |> array::map {xs = @, f = {x} -> spawn {f = {} -> x * 2}}
                let results = promises |> array::map {xs = @, f = {x} -> await {p =x}}
                results |> array::fold {xs = @, init = 0, f = {acc, x} -> acc + x}

            pipeline {data = [1, 2, 3, 4, 5]}
        """
        val result = eval(code)
        // 1*2 + 2*2 + 3*2 + 4*2 + 5*2 = 2 + 4 + 6 + 8 + 10 = 30
        assertEquals(MezzeInt(30), result)
    }

    @Test
    fun testConcurrentMapReduce() {
        val code = """
            $setupCode

            let map_phase: {data: [Int]} -> [Int] = {data} -> do
                let promises = data |> array::map {xs = @, f = {x} -> spawn {f = {} -> x * x}}
                promises |> array::map {xs = @, f = {x} -> await {p =x}}

            let reduce_phase: {data: [Int]} -> Int = {data} ->
                data |> array::fold {xs = @, init = 0, f = {acc, x} -> acc + x}

            let test = {} -> do
                let data = [1, 2, 3, 4, 5]
                let mapped = map_phase {data = data}

                let reduce_promise = spawn {f = {} -> reduce_phase {data = mapped}}
                await {p =reduce_promise}

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(55), result) // 1+4+9+16+25 = 55
    }

    @Test
    fun testNestedConcurrencyWithClosures() {
        val code = """
            $setupCode

            let test = {} -> do
                let outer_value = 100

                let outer_promise = spawn {f = {} -> do
                    let inner_value = 10

                    let inner_promise = spawn {f = {} ->
                        outer_value + inner_value
                    }

                    await {p =inner_promise}
                }

                await {p =outer_promise}

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(110), result)
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

                let p1 = spawn {f = {} ->
                    perform SimpleCalc in Compute.calculate {x = base, y = 10}
                }

                let p2 = spawn {f = {} ->
                    perform SimpleCalc in Compute.calculate {x = base, y = 20}
                }

                let r1 = await {p =p1}
                let r2 = await {p =p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(230), result)
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
                    spawn {f = {} -> processItem {item = x}}
                }

                let results = promises |> array::map {xs = @, f = {x} -> await {p =x}}

                results |> array::fold {xs = @, init = 0, f = {acc, x} -> acc + x}

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(31), result) // 1 + 10 + 20
    }

    @Test
    fun testConcurrentStateManipulation() {
        // Tests concurrent computations with closures and shared state.
        val code = """
            $setupCode

            let test = {} -> do
                let shared_value = 100

                # Workers access the shared value (demonstrating closure capture)
                # and compute results based on their IDs
                let worker = {id} -> do
                    let result = shared_value + id * 2
                    result

                let p1 = spawn {f = {} -> worker {id = 10}}
                let p2 = spawn {f = {} -> worker {id = 20}}
                let p3 = spawn {f = {} -> worker {id = 30}}

                # Await ensures proper synchronization - workers complete before we proceed
                let r1 = await {p =p1}
                let r2 = await {p =p2}
                let r3 = await {p =p3}

                r1 + r2 + r3

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(420), result) // (100+20) + (100+40) + (100+60) = 120 + 140 + 160 = 420
    }

    @Test
    fun testRecursiveConcurrentProcessing() {
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
        assertEquals(MezzeInt(55), result)
    }

    @Test
    fun testComplexClosureChainWithConcurrency() {
        val code = """
            $setupCode

            let makeAdder = {x} -> {y} -> {z} -> x + y + z

            let test = {} -> do
                let add10 = makeAdder {x = 10}
                let add10_20 = add10 {y = 20}

                let p1 = spawn {f = {} -> add10_20 {z = 5}}
                let p2 = spawn {f = {} -> add10_20 {z = 15}}

                let r1 = await {p =p1}
                let r2 = await {p =p2}

                r1 + r2

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(80), result) // (10+20+5) + (10+20+15) = 35 + 45 = 80
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
                    spawn {f = {} ->
                        perform Calculator in Math.square {x = x}
                    }}

                promises |> array::map {xs = @, f = {x} -> await {p =x}}

            let test = {} -> do
                let data = [1, 2, 3, 4, 5]
                let results = processInParallel {items = data}
                results |> array::fold {xs = @, init = 0, f = {acc, x} -> acc + x}

            test {}
        """
        val result = eval(code)
        assertEquals(MezzeInt(55), result) // 1+4+9+16+25 = 55
    }
}
