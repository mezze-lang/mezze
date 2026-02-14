package com.funk.language.interpreter.concurrency

import com.funk.language.interpreter.*
import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for the Parallel effect - data parallelism operations.
 *
 * Tests cover:
 * - VirtualParallel: Default virtual thread handler
 * - VirtualParallelWith: Configurable parallelism and chunk_size handler
 * - Sequential: Single-threaded handler
 * - All 12 operations: map, for_each, filter, reduce, fold, both, both3, find, any, all, partition, group_by
 * - Helper functions: sum, product, count, flatten, flat_map
 */
@Execution(ExecutionMode.SAME_THREAD)
class ParallelEffectTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        com.funk.language.typechecker.ModuleResolver.clearCache()
        com.funk.language.typechecker.TypeChecker.clearCache()
    }

    @AfterEach
    fun teardown() {
        Thread.sleep(50)
        TruffleContextHolder.clearEnv()
    }

    private fun eval(source: String): FunkValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = FunkTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // =============================================================================
    // VirtualParallel - map
    // =============================================================================

    @Test
    fun `test parallel map with VirtualParallel`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.map {xs = xs, f = {x} -> x * 2}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(2), FunkInt(4), FunkInt(6), FunkInt(8), FunkInt(10))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test parallel map preserves order`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [5, 4, 3, 2, 1]
                let result = Parallel.map {xs = xs, f = {x} -> x + 10}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(15), FunkInt(14), FunkInt(13), FunkInt(12), FunkInt(11))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test parallel map with empty list`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs: [Int] = []
                let result = Parallel.map {xs = xs, f = {x} -> x * 2}
                result
        """
        val result = eval(code) as FunkArrayList
        assertTrue(result.toList().isEmpty())
    }

    // =============================================================================
    // VirtualParallel - filter
    // =============================================================================

    @Test
    fun `test parallel filter`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5, 6]
                let result = Parallel.filter {xs = xs, f = {x} -> x % 2 == 0}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(2), FunkInt(4), FunkInt(6))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test parallel filter preserves order`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [10, 5, 20, 3, 15]
                let result = Parallel.filter {xs = xs, f = {x} -> x >= 10}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(10), FunkInt(20), FunkInt(15))
        assertEquals(expected, result.toList())
    }

    // =============================================================================
    // VirtualParallel - for_each
    // =============================================================================

    @Test
    fun `test parallel for_each completes without error`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3]
                let result = Parallel.for_each {xs = xs, f = {x} -> {}}
                result
        """
        val result = eval(code)
        assertEquals(FunkRecord(emptyMap()), result)
    }

    // =============================================================================
    // VirtualParallel - reduce
    // =============================================================================

    @Test
    fun `test parallel reduce sum`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.reduce {xs = xs, f = {left, right} -> left + right}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(15), result)
    }

    @Test
    fun `test parallel reduce with single element`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [42]
                let result = Parallel.reduce {xs = xs, f = {left, right} -> left + right}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    @Test
    fun `test parallel reduce with empty list returns None`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs: [Int] = []
                let result = Parallel.reduce {xs = xs, f = {left, right} -> left + right}
                match result is
                    Some {val} -> 1
                    None -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(0), result)
    }

    // =============================================================================
    // VirtualParallel - fold
    // =============================================================================

    @Test
    fun `test parallel fold`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.fold {
                    xs = xs,
                    zero = 0,
                    map_fn = {x} -> x * 2,
                    combine = {left, right} -> left + right
                }
                result
        """
        val result = eval(code)
        assertEquals(FunkInt(30), result)  // (1+2+3+4+5) * 2 = 30
    }

    @Test
    fun `test parallel fold with empty list returns zero`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs: [Int] = []
                let result = Parallel.fold {
                    xs = xs,
                    zero = 100,
                    map_fn = {x} -> x,
                    combine = {left, right} -> left + right
                }
                result
        """
        val result = eval(code)
        assertEquals(FunkInt(100), result)
    }

    // =============================================================================
    // VirtualParallel - both
    // =============================================================================

    @Test
    fun `test parallel both runs two computations`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let result = Parallel.both {
                    left = {} -> 10,
                    right = {} -> 20
                }
                result.left + result.right
        """
        val result = eval(code)
        assertEquals(FunkInt(30), result)
    }

    // =============================================================================
    // VirtualParallel - both3
    // =============================================================================

    @Test
    fun `test parallel both3 runs three computations`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let result = Parallel.both3 {
                    a = {} -> 1,
                    b = {} -> 2,
                    c = {} -> 3
                }
                result.a + result.b + result.c
        """
        val result = eval(code)
        assertEquals(FunkInt(6), result)
    }

    // =============================================================================
    // VirtualParallel - find
    // =============================================================================

    @Test
    fun `test parallel find returns first match`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.find {xs = xs, f = {x} -> x > 3}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code) as FunkInt
        // find returns some element > 3 (could be 4 or 5 due to parallel execution)
        assertTrue(result.value > 3)
    }

    @Test
    fun `test parallel find returns None when no match`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3]
                let result = Parallel.find {xs = xs, f = {x} -> x > 10}
                match result is
                    Some {val} -> 1
                    None -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(0), result)
    }

    // =============================================================================
    // VirtualParallel - any
    // =============================================================================

    @Test
    fun `test parallel any returns true when match exists`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.any {xs = xs, f = {x} -> x == 3}
                result
        """
        val result = eval(code)
        assertEquals(FunkBool(true), result)
    }

    @Test
    fun `test parallel any returns false when no match`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3]
                let result = Parallel.any {xs = xs, f = {x} -> x > 10}
                result
        """
        val result = eval(code)
        assertEquals(FunkBool(false), result)
    }

    // =============================================================================
    // VirtualParallel - all
    // =============================================================================

    @Test
    fun `test parallel all returns true when all match`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [2, 4, 6, 8]
                let result = Parallel.all {xs = xs, f = {x} -> x % 2 == 0}
                result
        """
        val result = eval(code)
        assertEquals(FunkBool(true), result)
    }

    @Test
    fun `test parallel all returns false when one fails`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [2, 4, 5, 8]
                let result = Parallel.all {xs = xs, f = {x} -> x % 2 == 0}
                result
        """
        val result = eval(code)
        assertEquals(FunkBool(false), result)
    }

    @Test
    fun `test parallel all returns true for empty list`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs: [Int] = []
                let result = Parallel.all {xs = xs, f = {x} -> x > 0}
                result
        """
        val result = eval(code)
        assertEquals(FunkBool(true), result)
    }

    // =============================================================================
    // VirtualParallel - partition
    // =============================================================================

    @Test
    fun `test parallel partition`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5, 6]
                let result = Parallel.partition {xs = xs, f = {x} -> x % 2 == 0}
                match result.matching is
                    [_, _, _] -> match result.not_matching is
                        [_, _, _] -> 6
                        _ -> 0
                    _ -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(6), result)
    }

    // =============================================================================
    // VirtualParallel - group_by
    // =============================================================================

    @Test
    fun `test parallel group_by`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5, 6]
                let result = Parallel.group_by {xs = xs, f = {x} -> x % 2}
                # Should have 2 groups: key 0 (evens) and key 1 (odds)
                match result is
                    [_, _] -> 2
                    _ -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(2), result)
    }

    // =============================================================================
    // Sequential handler tests
    // =============================================================================

    @Test
    fun `test sequential map`() {
        val code = """
            use std::parallel::{Parallel, Sequential}

            perform Sequential in do
                let xs = [1, 2, 3]
                let result = Parallel.map {xs = xs, f = {x} -> x * 10}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(10), FunkInt(20), FunkInt(30))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test sequential filter`() {
        val code = """
            use std::parallel::{Parallel, Sequential}

            perform Sequential in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.filter {xs = xs, f = {x} -> x > 2}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(3), FunkInt(4), FunkInt(5))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test sequential reduce`() {
        val code = """
            use std::parallel::{Parallel, Sequential}

            perform Sequential in do
                let xs = [1, 2, 3, 4]
                let result = Parallel.reduce {xs = xs, f = {left, right} -> left * right}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(24), result)  // 1 * 2 * 3 * 4 = 24
    }

    @Test
    fun `test sequential both runs sequentially`() {
        val code = """
            use std::parallel::{Parallel, Sequential}

            perform Sequential in do
                let result = Parallel.both {
                    left = {} -> 5,
                    right = {} -> 10
                }
                result.left + result.right
        """
        val result = eval(code)
        assertEquals(FunkInt(15), result)
    }

    // =============================================================================
    // VirtualParallelWith - configurable parallelism and chunking
    // =============================================================================

    @Test
    fun `test VirtualParallelWith map with parallelism limit`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallelWith}

            perform VirtualParallelWith {parallelism = 2, chunk_size = 1} in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.map {xs = xs, f = {x} -> x * 2}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(2), FunkInt(4), FunkInt(6), FunkInt(8), FunkInt(10))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test VirtualParallelWith map with chunking`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallelWith}

            perform VirtualParallelWith {parallelism = 0, chunk_size = 2} in do
                let xs = [1, 2, 3, 4, 5, 6]
                let result = Parallel.map {xs = xs, f = {x} -> x * 3}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(3), FunkInt(6), FunkInt(9), FunkInt(12), FunkInt(15), FunkInt(18))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test VirtualParallelWith reduce with chunking`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallelWith}

            perform VirtualParallelWith {parallelism = 4, chunk_size = 3} in do
                let xs = [1, 2, 3, 4, 5, 6, 7, 8, 9]
                let result = Parallel.reduce {xs = xs, f = {left, right} -> left + right}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(45), result)  // Sum of 1 to 9
    }

    @Test
    fun `test VirtualParallelWith filter with parallelism and chunking`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallelWith}

            perform VirtualParallelWith {parallelism = 2, chunk_size = 3} in do
                let xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
                let result = Parallel.filter {xs = xs, f = {x} -> x % 2 == 0}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(2), FunkInt(4), FunkInt(6), FunkInt(8), FunkInt(10))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test VirtualParallelWith fold with chunking`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallelWith}

            perform VirtualParallelWith {parallelism = 2, chunk_size = 2} in do
                let xs = [1, 2, 3, 4]
                let result = Parallel.fold {
                    xs = xs,
                    zero = 0,
                    map_fn = {x} -> x * x,
                    combine = {left, right} -> left + right
                }
                result
        """
        val result = eval(code)
        assertEquals(FunkInt(30), result)  // 1 + 4 + 9 + 16 = 30
    }

    // =============================================================================
    // Helper function tests
    // =============================================================================

    @Test
    fun `test sum helper function`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel, sum}

            perform VirtualParallel in do
                let result = sum {xs = [1, 2, 3, 4, 5]}
                result
        """
        val result = eval(code)
        assertEquals(FunkInt(15), result)
    }

    @Test
    fun `test product helper function`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel, product}

            perform VirtualParallel in do
                let result = product {xs = [1, 2, 3, 4]}
                result
        """
        val result = eval(code)
        assertEquals(FunkInt(24), result)
    }

    @Test
    fun `test count helper function`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel, count}

            perform VirtualParallel in do
                let result = count {xs = [1, 2, 3, 4, 5, 6], f = {x} -> x % 2 == 0}
                result
        """
        val result = eval(code)
        assertEquals(FunkInt(3), result)
    }

    // =============================================================================
    // Edge cases and integration tests
    // =============================================================================

    @Test
    fun `test combining map and reduce`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5]
                # Double each element, then sum
                let doubled = Parallel.map {xs = xs, f = {x} -> x * 2}
                let sum = Parallel.reduce {xs = doubled, f = {left, right} -> left + right}
                match sum is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(30), result)
    }

    @Test
    fun `test filter then map`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5, 6]
                # Filter evens, then square
                let evens = Parallel.filter {xs = xs, f = {x} -> x % 2 == 0}
                let squared = Parallel.map {xs = evens, f = {x} -> x * x}
                squared
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(4), FunkInt(16), FunkInt(36))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test large list parallel processing`() {
        // Test with a larger list to exercise parallelism
        val code = """
            use std::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                # Create a list of 100 elements
                let xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                          11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                          21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
                          31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
                          41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
                          51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
                          61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
                          71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
                          81, 82, 83, 84, 85, 86, 87, 88, 89, 90,
                          91, 92, 93, 94, 95, 96, 97, 98, 99, 100]
                let result = Parallel.reduce {xs = xs, f = {left, right} -> left + right}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(5050), result)  // Sum of 1 to 100
    }

    @Test
    fun `test large list with chunking for efficiency`() {
        val code = """
            use std::parallel::{Parallel, VirtualParallelWith}

            perform VirtualParallelWith {parallelism = 4, chunk_size = 25} in do
                let xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                          11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                          21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
                          31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
                          41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
                          51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
                          61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
                          71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
                          81, 82, 83, 84, 85, 86, 87, 88, 89, 90,
                          91, 92, 93, 94, 95, 96, 97, 98, 99, 100]
                let result = Parallel.reduce {xs = xs, f = {left, right} -> left + right}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(5050), result)  // Sum of 1 to 100
    }

    // =============================================================================
    // ForkJoinParallel - True work-stealing parallelism
    // =============================================================================

    @Test
    fun `test ForkJoinParallel map`() {
        val code = """
            use std::parallel::{Parallel, ForkJoinParallel}

            perform ForkJoinParallel in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.map {xs = xs, f = {x} -> x * 2}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(2), FunkInt(4), FunkInt(6), FunkInt(8), FunkInt(10))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test ForkJoinParallel filter`() {
        val code = """
            use std::parallel::{Parallel, ForkJoinParallel}

            perform ForkJoinParallel in do
                let xs = [1, 2, 3, 4, 5, 6, 7, 8]
                let result = Parallel.filter {xs = xs, f = {x} -> x % 2 == 0}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(2), FunkInt(4), FunkInt(6), FunkInt(8))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test ForkJoinParallel reduce`() {
        val code = """
            use std::parallel::{Parallel, ForkJoinParallel}

            perform ForkJoinParallel in do
                let xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
                let result = Parallel.reduce {xs = xs, f = {left, right} -> left + right}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(55), result)
    }

    @Test
    fun `test ForkJoinParallel fold`() {
        val code = """
            use std::parallel::{Parallel, ForkJoinParallel}

            perform ForkJoinParallel in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.fold {
                    xs = xs,
                    zero = 0,
                    map_fn = {x} -> x * x,
                    combine = {left, right} -> left + right
                }
                result
        """
        val result = eval(code)
        assertEquals(FunkInt(55), result)  // 1 + 4 + 9 + 16 + 25 = 55
    }

    @Test
    fun `test ForkJoinParallel both`() {
        val code = """
            use std::parallel::{Parallel, ForkJoinParallel}

            perform ForkJoinParallel in do
                let result = Parallel.both {
                    left = {} -> 100,
                    right = {} -> 200
                }
                result.left + result.right
        """
        val result = eval(code)
        assertEquals(FunkInt(300), result)
    }

    @Test
    fun `test ForkJoinParallel any`() {
        val code = """
            use std::parallel::{Parallel, ForkJoinParallel}

            perform ForkJoinParallel in do
                let xs = [1, 2, 3, 4, 5]
                Parallel.any {xs = xs, f = {x} -> x == 3}
        """
        val result = eval(code)
        assertEquals(FunkBool(true), result)
    }

    @Test
    fun `test ForkJoinParallel all`() {
        val code = """
            use std::parallel::{Parallel, ForkJoinParallel}

            perform ForkJoinParallel in do
                let xs = [2, 4, 6, 8, 10]
                Parallel.all {xs = xs, f = {x} -> x % 2 == 0}
        """
        val result = eval(code)
        assertEquals(FunkBool(true), result)
    }

    @Test
    fun `test ForkJoinParallelWith with custom threshold`() {
        val code = """
            use std::parallel::{Parallel, ForkJoinParallelWith}

            perform ForkJoinParallelWith {threshold = 2, parallelism = 4} in do
                let xs = [1, 2, 3, 4, 5, 6, 7, 8]
                let result = Parallel.map {xs = xs, f = {x} -> x * 10}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(10), FunkInt(20), FunkInt(30), FunkInt(40),
                              FunkInt(50), FunkInt(60), FunkInt(70), FunkInt(80))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test ForkJoinParallel large list work-stealing`() {
        val code = """
            use std::parallel::{Parallel, ForkJoinParallel}

            perform ForkJoinParallel in do
                let xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
                          11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                          21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
                          31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
                          41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
                          51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
                          61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
                          71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
                          81, 82, 83, 84, 85, 86, 87, 88, 89, 90,
                          91, 92, 93, 94, 95, 96, 97, 98, 99, 100]
                let result = Parallel.reduce {xs = xs, f = {left, right} -> left + right}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(5050), result)  // Sum of 1 to 100
    }
}
