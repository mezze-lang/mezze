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
 * Tests for the extended Async effect operations:
 * - Structured Concurrency: join, join3
 * - Collection Operations: map, for_each, filter
 * - Error Handling: any, settle
 */
@Execution(ExecutionMode.SAME_THREAD)
class AsyncExtendedTest {

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
    // Structured Concurrency - join
    // =============================================================================

    @Test
    fun `test join runs two computations concurrently with LoomAsync`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let result = Async.join {
                    left = {} -> 10,
                    right = {} -> 20
                }
                result.left + result.right
        """
        val result = eval(code)
        assertEquals(FunkInt(30), result)
    }

    @Test
    fun `test join runs two computations concurrently with OSThreadAsync`() {
        val code = """
            use std::async::{Async, OSThreadAsync}

            perform OSThreadAsync in do
                let result = Async.join {
                    left = {} -> 100,
                    right = {} -> 200
                }
                result.left + result.right
        """
        val result = eval(code)
        assertEquals(FunkInt(300), result)
    }

    @Test
    fun `test join with different return types`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let result = Async.join {
                    left = {} -> "hello",
                    right = {} -> 42
                }
                result.right
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    // =============================================================================
    // Structured Concurrency - join3
    // =============================================================================

    @Test
    fun `test join3 runs three computations concurrently`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let result = Async.join3 {
                    a = {} -> 1,
                    b = {} -> 2,
                    c = {} -> 3
                }
                result.a + result.b + result.c
        """
        val result = eval(code)
        assertEquals(FunkInt(6), result)
    }

    @Test
    fun `test join3 with different types`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let result = Async.join3 {
                    a = {} -> "first",
                    b = {} -> 2,
                    c = {} -> True
                }
                result.b
        """
        val result = eval(code)
        assertEquals(FunkInt(2), result)
    }

    // =============================================================================
    // Collection Operations - map
    // =============================================================================

    @Test
    fun `test map applies function to all elements concurrently`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let xs = [1, 2, 3, 4, 5]
                let result = Async.map {xs = xs, f = {x} -> x * 2}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(2), FunkInt(4), FunkInt(6), FunkInt(8), FunkInt(10))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test map preserves order`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let xs = [5, 4, 3, 2, 1]
                let result = Async.map {xs = xs, f = {x} -> x + 10}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(15), FunkInt(14), FunkInt(13), FunkInt(12), FunkInt(11))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test map with empty list`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let xs: [Int] = []
                let result = Async.map {xs = xs, f = {x} -> x * 2}
                result
        """
        val result = eval(code) as FunkArrayList
        assertTrue(result.toList().isEmpty())
    }

    @Test
    fun `test map with OSThreadAsync`() {
        val code = """
            use std::async::{Async, OSThreadAsync}

            perform OSThreadAsync in do
                let xs = [10, 20, 30]
                let result = Async.map {xs = xs, f = {x} -> x / 10}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(1), FunkInt(2), FunkInt(3))
        assertEquals(expected, result.toList())
    }

    // =============================================================================
    // Collection Operations - for_each
    // =============================================================================

    @Test
    fun `test for_each executes function for each element`() {
        // for_each is for side effects, test it completes without error
        // We test by verifying it returns {} and doesn't throw
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let xs = [1, 2, 3]
                let result = Async.for_each {xs = xs, f = {x} -> {}}
                result
        """
        val result = eval(code)
        assertEquals(FunkRecord(emptyMap()), result)
    }

    @Test
    fun `test for_each with empty list returns unit`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let xs: [Int] = []
                let result = Async.for_each {xs = xs, f = {x} -> {}}
                result
        """
        val result = eval(code)
        assertEquals(FunkRecord(emptyMap()), result)
    }

    // =============================================================================
    // Collection Operations - filter
    // =============================================================================

    @Test
    fun `test filter keeps elements matching predicate`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let xs = [1, 2, 3, 4, 5, 6]
                let result = Async.filter {xs = xs, f = {x} -> x % 2 == 0}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(2), FunkInt(4), FunkInt(6))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test filter preserves order`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let xs = [10, 5, 20, 3, 15]
                let result = Async.filter {xs = xs, f = {x} -> x >= 10}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(10), FunkInt(20), FunkInt(15))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test filter with no matches returns empty list`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let xs = [1, 2, 3]
                let result = Async.filter {xs = xs, f = {x} -> x > 100}
                result
        """
        val result = eval(code) as FunkArrayList
        assertTrue(result.toList().isEmpty())
    }

    @Test
    fun `test filter with all matches returns all elements`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let xs = [1, 2, 3]
                let result = Async.filter {xs = xs, f = {x} -> x > 0}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(1), FunkInt(2), FunkInt(3))
        assertEquals(expected, result.toList())
    }

    // =============================================================================
    // Error Handling - any
    // =============================================================================

    @Test
    fun `test any returns first successful result`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}
                let p3 = Async.spawn {f = {} -> 3}
                let result = Async.any {ps = [p1, p2, p3]}
                result
        """
        val result = eval(code) as FunkInt
        // any returns the first completed result - could be 1, 2, or 3
        assertTrue(result.value in listOf(1L, 2L, 3L))
    }

    @Test
    fun `test any with single promise`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let p = Async.spawn {f = {} -> 42}
                let result = Async.any {ps = [p]}
                result
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    // =============================================================================
    // Error Handling - settle
    // =============================================================================

    @Test
    fun `test settle collects all results as Ok`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let p3 = Async.spawn {f = {} -> 30}
                let results = Async.settle {ps = [p1, p2, p3]}
                # Match on results array to verify we have 3 items
                match results is
                    [_, _, _] -> 3
                    _ -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(3), result)
    }

    @Test
    fun `test settle returns Ok variants for successful promises`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let p = Async.spawn {f = {} -> 42}
                let results = Async.settle {ps = [p]}
                match results is
                    [Ok {val}] -> val
                    _ -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(42), result)
    }

    @Test
    fun `test settle with empty list returns empty list`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let results = Async.settle {ps = []}
                # Match on results array to verify it's empty
                match results is
                    [] -> 0
                    _ -> 1
        """
        val result = eval(code)
        assertEquals(FunkInt(0), result)
    }

    // =============================================================================
    // CoopAsync Tests (single-threaded cooperative scheduling)
    // =============================================================================

    @Test
    fun `test CoopAsync join executes sequentially`() {
        val code = """
            use std::async::{Async, CoopAsync}
            use std::control::continuation

            perform CoopAsync {cont = continuation::new {}} in do
                let result = Async.join {
                    left = {} -> 5,
                    right = {} -> 10
                }
                result.left + result.right
        """
        val result = eval(code)
        assertEquals(FunkInt(15), result)
    }

    @Test
    fun `test CoopAsync map executes sequentially`() {
        val code = """
            use std::async::{Async, CoopAsync}
            use std::control::continuation

            perform CoopAsync {cont = continuation::new {}} in do
                let xs = [1, 2, 3]
                let result = Async.map {xs = xs, f = {x} -> x * 10}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(10), FunkInt(20), FunkInt(30))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test CoopAsync filter works correctly`() {
        val code = """
            use std::async::{Async, CoopAsync}
            use std::control::continuation

            perform CoopAsync {cont = continuation::new {}} in do
                let xs = [1, 2, 3, 4, 5]
                let result = Async.filter {xs = xs, f = {x} -> x > 2}
                result
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(3), FunkInt(4), FunkInt(5))
        assertEquals(expected, result.toList())
    }

    // =============================================================================
    // Integration Tests
    // =============================================================================

    @Test
    fun `test combining join and map`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let result = Async.join {
                    left = {} -> Async.map {xs = [1, 2], f = {x} -> x * 2},
                    right = {} -> Async.map {xs = [3, 4], f = {x} -> x * 3}
                }
                # result.left = [2, 4], result.right = [9, 12]
                match result.left is
                    [a, b] -> match result.right is
                        [c, d] -> a + b + c + d
                        _ -> 0
                    _ -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(27), result) // 2 + 4 + 9 + 12 = 27
    }

    @Test
    fun `test nested spawn with await in map`() {
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let xs = [1, 2, 3]
                # Each element spawns a task that doubles it
                let results = Async.map {xs = xs, f = {x} -> do
                    let p = Async.spawn {f = {} -> x * 2}
                    let r = Async.await {p = p}
                    r
                }
                results
        """
        val result = eval(code) as FunkArrayList
        val expected = listOf(FunkInt(2), FunkInt(4), FunkInt(6))
        assertEquals(expected, result.toList())
    }

    @Test
    fun `test filter combined with for_each`() {
        // Test that filter and for_each can be chained together
        val code = """
            use std::async::{Async, LoomAsync}

            perform LoomAsync in do
                let xs = [1, 2, 3, 4, 5, 6]
                # Filter evens
                let evens = Async.filter {xs = xs, f = {x} -> x % 2 == 0}
                # For each should process 3 items (2, 4, 6)
                let _ = Async.for_each {xs = evens, f = {x} -> {}}
                # Return count of evens by pattern matching
                match evens is
                    [_, _, _] -> 3
                    _ -> 0
        """
        val result = eval(code)
        assertEquals(FunkInt(3), result)
    }
}
