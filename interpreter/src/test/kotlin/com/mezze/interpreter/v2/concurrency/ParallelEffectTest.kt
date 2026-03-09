package com.mezze.interpreter.v2.concurrency

import com.mezze.interpreter.v2.MezzeTestHelper
import com.mezze.interpreter.v2.values.MezzeList
import com.mezze.interpreter.v2.values.MezzeSeq
import com.mezze.interpreter.v2.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
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
 *
 * Migrated from V1 to V2 with unboxed values.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ParallelEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // =============================================================================
    // VirtualParallel - map
    // =============================================================================

    @Test
    fun `test parallel map with VirtualParallel`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.map {xs = xs, f = {x} -> x * 2}
                result
        """
        val result = eval(code) as MezzeSeq
        val elements = result.toArray()
        assertEquals(5, elements.size)
        assertEquals(2L, elements[0])
        assertEquals(4L, elements[1])
        assertEquals(6L, elements[2])
        assertEquals(8L, elements[3])
        assertEquals(10L, elements[4])
    }

    @Test
    fun `test parallel map preserves order`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [5, 4, 3, 2, 1]
                let result = Parallel.map {xs = xs, f = {x} -> x + 10}
                result
        """
        val result = eval(code) as MezzeSeq
        val elements = result.toArray()
        assertEquals(5, elements.size)
        assertEquals(15L, elements[0])
        assertEquals(14L, elements[1])
        assertEquals(13L, elements[2])
        assertEquals(12L, elements[3])
        assertEquals(11L, elements[4])
    }

    @Test
    fun `test parallel map with empty list`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs: [Int] = []
                let result = Parallel.map {xs = xs, f = {x} -> x * 2}
                result
        """
        val result = eval(code) as MezzeSeq
        assertEquals(0, result.size())
    }

    // =============================================================================
    // VirtualParallel - filter
    // =============================================================================

    @Test
    fun `test parallel filter`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5, 6]
                let result = Parallel.filter {xs = xs, f = {x} -> x % 2 == 0}
                result
        """
        val result = eval(code) as MezzeSeq
        val elements = result.toArray()
        assertEquals(3, elements.size)
        assertEquals(2L, elements[0])
        assertEquals(4L, elements[1])
        assertEquals(6L, elements[2])
    }

    @Test
    fun `test parallel filter preserves order`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [10, 5, 20, 3, 15]
                let result = Parallel.filter {xs = xs, f = {x} -> x >= 10}
                result
        """
        val result = eval(code) as MezzeSeq
        val elements = result.toArray()
        assertEquals(3, elements.size)
        assertEquals(10L, elements[0])
        assertEquals(20L, elements[1])
        assertEquals(15L, elements[2])
    }

    // =============================================================================
    // VirtualParallel - for_each
    // =============================================================================

    @Test
    fun `test parallel for_each completes without error`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3]
                let result = Parallel.for_each {xs = xs, f = {x} -> {}}
                result
        """
        val result = eval(code)
        assertTrue(result is MezzeRecord)
        assertEquals(0, (result as MezzeRecord).getFieldCount())
    }

    // =============================================================================
    // VirtualParallel - reduce
    // =============================================================================

    @Test
    fun `test parallel reduce sum`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.reduce {xs = xs, f = {left, right} -> left + right}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun `test parallel reduce with single element`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [42]
                let result = Parallel.reduce {xs = xs, f = {left, right} -> left + right}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test parallel reduce with empty list returns None`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs: [Int] = []
                let result = Parallel.reduce {xs = xs, f = {left, right} -> left + right}
                match result is
                    Some {val} -> 1
                    None -> 0
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    // =============================================================================
    // VirtualParallel - fold
    // =============================================================================

    @Test
    fun `test parallel fold`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

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
        assertEquals(30L, result)  // (1+2+3+4+5) * 2 = 30
    }

    @Test
    fun `test parallel fold with empty list returns zero`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

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
        assertEquals(100L, result)
    }

    // =============================================================================
    // VirtualParallel - both
    // =============================================================================

    @Test
    fun `test parallel both runs two computations`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let result = Parallel.both {
                    left = {} -> 10,
                    right = {} -> 20
                }
                result.left + result.right
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    // =============================================================================
    // VirtualParallel - both3
    // =============================================================================

    @Test
    fun `test parallel both3 runs three computations`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let result = Parallel.both3 {
                    a = {} -> 1,
                    b = {} -> 2,
                    c = {} -> 3
                }
                result.a + result.b + result.c
        """
        val result = eval(code)
        assertEquals(6L, result)
    }

    // =============================================================================
    // VirtualParallel - find
    // =============================================================================

    @Test
    fun `test parallel find returns first match`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.find {xs = xs, f = {x} -> x > 3}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code) as Long
        // find returns some element > 3 (could be 4 or 5 due to parallel execution)
        assertTrue(result > 3)
    }

    @Test
    fun `test parallel find returns None when no match`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3]
                let result = Parallel.find {xs = xs, f = {x} -> x > 10}
                match result is
                    Some {val} -> 1
                    None -> 0
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    // =============================================================================
    // VirtualParallel - any
    // =============================================================================

    @Test
    fun `test parallel any returns true when match exists`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.any {xs = xs, f = {x} -> x == 3}
                result
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test parallel any returns false when no match`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3]
                let result = Parallel.any {xs = xs, f = {x} -> x > 10}
                result
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    // =============================================================================
    // VirtualParallel - all
    // =============================================================================

    @Test
    fun `test parallel all returns true when all match`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [2, 4, 6, 8]
                let result = Parallel.all {xs = xs, f = {x} -> x % 2 == 0}
                result
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test parallel all returns false when one fails`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [2, 4, 5, 8]
                let result = Parallel.all {xs = xs, f = {x} -> x % 2 == 0}
                result
        """
        val result = eval(code)
        assertEquals(false, result)
    }

    @Test
    fun `test parallel all returns true for empty list`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs: [Int] = []
                let result = Parallel.all {xs = xs, f = {x} -> x > 0}
                result
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    // =============================================================================
    // VirtualParallel - partition
    // =============================================================================

    @Test
    fun `test parallel partition`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

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
        assertEquals(6L, result)
    }

    // =============================================================================
    // VirtualParallel - group_by
    // =============================================================================

    @Test
    fun `test parallel group_by`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5, 6]
                let result = Parallel.group_by {xs = xs, f = {x} -> x % 2}
                # Should have 2 groups: key 0 (evens) and key 1 (odds)
                match result is
                    [_, _] -> 2
                    _ -> 0
        """
        val result = eval(code)
        assertEquals(2L, result)
    }

    // =============================================================================
    // Sequential handler tests
    // =============================================================================

    @Test
    fun `test sequential map`() {
        val code = """
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                let xs = [1, 2, 3]
                let result = Parallel.map {xs = xs, f = {x} -> x * 10}
                result
        """
        val result = eval(code) as MezzeSeq
        val elements = result.toArray()
        assertEquals(3, elements.size)
        assertEquals(10L, elements[0])
        assertEquals(20L, elements[1])
        assertEquals(30L, elements[2])
    }

    @Test
    fun `test sequential filter`() {
        val code = """
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.filter {xs = xs, f = {x} -> x > 2}
                result
        """
        val result = eval(code) as MezzeSeq
        val elements = result.toArray()
        assertEquals(3, elements.size)
        assertEquals(3L, elements[0])
        assertEquals(4L, elements[1])
        assertEquals(5L, elements[2])
    }

    @Test
    fun `test sequential reduce`() {
        val code = """
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                let xs = [1, 2, 3, 4]
                let result = Parallel.reduce {xs = xs, f = {left, right} -> left * right}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(24L, result)  // 1 * 2 * 3 * 4 = 24
    }

    @Test
    fun `test sequential both runs sequentially`() {
        val code = """
            use std::effects::parallel::{Parallel, Sequential}

            perform Sequential in do
                let result = Parallel.both {
                    left = {} -> 5,
                    right = {} -> 10
                }
                result.left + result.right
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    // =============================================================================
    // VirtualParallelWith - configurable parallelism and chunking
    // =============================================================================

    @Test
    fun `test VirtualParallelWith map with parallelism limit`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallelWith}

            perform VirtualParallelWith {parallelism = 2, chunk_size = 1} in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.map {xs = xs, f = {x} -> x * 2}
                result
        """
        val result = eval(code) as MezzeSeq
        val elements = result.toArray()
        assertEquals(5, elements.size)
        assertEquals(2L, elements[0])
        assertEquals(4L, elements[1])
        assertEquals(6L, elements[2])
        assertEquals(8L, elements[3])
        assertEquals(10L, elements[4])
    }

    @Test
    fun `test VirtualParallelWith map with chunking`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallelWith}

            perform VirtualParallelWith {parallelism = 0, chunk_size = 2} in do
                let xs = [1, 2, 3, 4, 5, 6]
                let result = Parallel.map {xs = xs, f = {x} -> x * 3}
                result
        """
        val result = eval(code) as MezzeSeq
        val elements = result.toArray()
        assertEquals(6, elements.size)
        assertEquals(3L, elements[0])
        assertEquals(6L, elements[1])
        assertEquals(9L, elements[2])
        assertEquals(12L, elements[3])
        assertEquals(15L, elements[4])
        assertEquals(18L, elements[5])
    }

    @Test
    fun `test VirtualParallelWith reduce with chunking`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallelWith}

            perform VirtualParallelWith {parallelism = 4, chunk_size = 3} in do
                let xs = [1, 2, 3, 4, 5, 6, 7, 8, 9]
                let result = Parallel.reduce {xs = xs, f = {left, right} -> left + right}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(45L, result)  // Sum of 1 to 9
    }

    @Test
    fun `test VirtualParallelWith filter with parallelism and chunking`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallelWith}

            perform VirtualParallelWith {parallelism = 2, chunk_size = 3} in do
                let xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
                let result = Parallel.filter {xs = xs, f = {x} -> x % 2 == 0}
                result
        """
        val result = eval(code) as MezzeSeq
        val elements = result.toArray()
        assertEquals(5, elements.size)
        assertEquals(2L, elements[0])
        assertEquals(4L, elements[1])
        assertEquals(6L, elements[2])
        assertEquals(8L, elements[3])
        assertEquals(10L, elements[4])
    }

    @Test
    fun `test VirtualParallelWith fold with chunking`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallelWith}

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
        assertEquals(30L, result)  // 1 + 4 + 9 + 16 = 30
    }

    // =============================================================================
    // Helper function tests
    // =============================================================================

    @Test
    fun `test sum helper function`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel, sum}

            perform VirtualParallel in do
                let result = sum {xs = [1, 2, 3, 4, 5]}
                result
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun `test product helper function`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel, product}

            perform VirtualParallel in do
                let result = product {xs = [1, 2, 3, 4]}
                result
        """
        val result = eval(code)
        assertEquals(24L, result)
    }

    @Test
    fun `test count helper function`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel, count}

            perform VirtualParallel in do
                let result = count {xs = [1, 2, 3, 4, 5, 6], f = {x} -> x % 2 == 0}
                result
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    // =============================================================================
    // Edge cases and integration tests
    // =============================================================================

    @Test
    fun `test combining map and reduce`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

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
        assertEquals(30L, result)
    }

    @Test
    fun `test filter then map`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

            perform VirtualParallel in do
                let xs = [1, 2, 3, 4, 5, 6]
                # Filter evens, then square
                let evens = Parallel.filter {xs = xs, f = {x} -> x % 2 == 0}
                let squared = Parallel.map {xs = evens, f = {x} -> x * x}
                squared
        """
        val result = eval(code) as MezzeSeq
        val elements = result.toArray()
        assertEquals(3, elements.size)
        assertEquals(4L, elements[0])
        assertEquals(16L, elements[1])
        assertEquals(36L, elements[2])
    }

    @Test
    fun `test large list parallel processing`() {
        // Test with a larger list to exercise parallelism
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallel}

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
        assertEquals(5050L, result)  // Sum of 1 to 100
    }

    @Test
    fun `test large list with chunking for efficiency`() {
        val code = """
            use std::effects::parallel::{Parallel, VirtualParallelWith}

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
        assertEquals(5050L, result)  // Sum of 1 to 100
    }

    // =============================================================================
    // ForkJoinParallel - True work-stealing parallelism
    // =============================================================================

    @Test
    fun `test ForkJoinParallel map`() {
        val code = """
            use std::effects::parallel::{Parallel, ForkJoinParallel}

            perform ForkJoinParallel in do
                let xs = [1, 2, 3, 4, 5]
                let result = Parallel.map {xs = xs, f = {x} -> x * 2}
                result
        """
        val result = eval(code) as MezzeSeq
        val elements = result.toArray()
        assertEquals(5, elements.size)
        assertEquals(2L, elements[0])
        assertEquals(4L, elements[1])
        assertEquals(6L, elements[2])
        assertEquals(8L, elements[3])
        assertEquals(10L, elements[4])
    }

    @Test
    fun `test ForkJoinParallel filter`() {
        val code = """
            use std::effects::parallel::{Parallel, ForkJoinParallel}

            perform ForkJoinParallel in do
                let xs = [1, 2, 3, 4, 5, 6, 7, 8]
                let result = Parallel.filter {xs = xs, f = {x} -> x % 2 == 0}
                result
        """
        val result = eval(code) as MezzeSeq
        val elements = result.toArray()
        assertEquals(4, elements.size)
        assertEquals(2L, elements[0])
        assertEquals(4L, elements[1])
        assertEquals(6L, elements[2])
        assertEquals(8L, elements[3])
    }

    @Test
    fun `test ForkJoinParallel reduce`() {
        val code = """
            use std::effects::parallel::{Parallel, ForkJoinParallel}

            perform ForkJoinParallel in do
                let xs = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
                let result = Parallel.reduce {xs = xs, f = {left, right} -> left + right}
                match result is
                    Some {val} -> val
                    None -> 0
        """
        val result = eval(code)
        assertEquals(55L, result)
    }

    @Test
    fun `test ForkJoinParallel fold`() {
        val code = """
            use std::effects::parallel::{Parallel, ForkJoinParallel}

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
        assertEquals(55L, result)  // 1 + 4 + 9 + 16 + 25 = 55
    }

    @Test
    fun `test ForkJoinParallel both`() {
        val code = """
            use std::effects::parallel::{Parallel, ForkJoinParallel}

            perform ForkJoinParallel in do
                let result = Parallel.both {
                    left = {} -> 100,
                    right = {} -> 200
                }
                result.left + result.right
        """
        val result = eval(code)
        assertEquals(300L, result)
    }

    @Test
    fun `test ForkJoinParallel any`() {
        val code = """
            use std::effects::parallel::{Parallel, ForkJoinParallel}

            perform ForkJoinParallel in do
                let xs = [1, 2, 3, 4, 5]
                Parallel.any {xs = xs, f = {x} -> x == 3}
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test ForkJoinParallel all`() {
        val code = """
            use std::effects::parallel::{Parallel, ForkJoinParallel}

            perform ForkJoinParallel in do
                let xs = [2, 4, 6, 8, 10]
                Parallel.all {xs = xs, f = {x} -> x % 2 == 0}
        """
        val result = eval(code)
        assertEquals(true, result)
    }

    @Test
    fun `test ForkJoinParallelWith with custom threshold`() {
        val code = """
            use std::effects::parallel::{Parallel, ForkJoinParallelWith}

            perform ForkJoinParallelWith {threshold = 2, parallelism = 4} in do
                let xs = [1, 2, 3, 4, 5, 6, 7, 8]
                let result = Parallel.map {xs = xs, f = {x} -> x * 10}
                result
        """
        val result = eval(code) as MezzeSeq
        val elements = result.toArray()
        assertEquals(8, elements.size)
        assertEquals(10L, elements[0])
        assertEquals(20L, elements[1])
        assertEquals(30L, elements[2])
        assertEquals(40L, elements[3])
        assertEquals(50L, elements[4])
        assertEquals(60L, elements[5])
        assertEquals(70L, elements[6])
        assertEquals(80L, elements[7])
    }

    @Test
    fun `test ForkJoinParallel large list work-stealing`() {
        val code = """
            use std::effects::parallel::{Parallel, ForkJoinParallel}

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
        assertEquals(5050L, result)  // Sum of 1 to 100
    }
}
