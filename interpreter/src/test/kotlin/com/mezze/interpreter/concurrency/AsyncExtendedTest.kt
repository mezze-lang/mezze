package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeUnit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for the extended Async effect operations:
 * - Structured Concurrency: join, join3
 * - Collection Operations: map, for_each, filter
 * - Error Handling: any, settle
 *
 * Migrated from V1 to V2 with unboxed values.
 */
@Execution(ExecutionMode.SAME_THREAD)
class AsyncExtendedTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // =============================================================================
    // Structured Concurrency - join
    // =============================================================================

    @Test
    fun `test join runs two computations concurrently with LoomAsync`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let r = join {
                    l = {} -> 10,
                    r = {} -> 20
                }
                result::get_or_else { result = r.l, def = 0 } + result::get_or_else { result = r.r, def = 0 }
        """
        val result = eval(code)
        assertEquals(30L, result)
    }

    @Test
    fun `test join runs two computations concurrently with OSThreadAsync`() {
        val code = """
            use std::effects::async::{Async, OSThreadAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform OSThreadAsync in do
                let r = join {
                    l = {} -> 100,
                    r = {} -> 200
                }
                result::get_or_else { result = r.l, def = 0 } + result::get_or_else { result = r.r, def = 0 }
        """
        val result = eval(code)
        assertEquals(300L, result)
    }

    @Test
    fun `test join with different return types`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let r = join {
                    l = {} -> "hello",
                    r = {} -> 42
                }
                result::get_or_else { result = r.r, def = 0 }
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Structured Concurrency - join3
    // =============================================================================

    @Test
    fun `test join3 runs three computations concurrently`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let r = join3 {
                    a = {} -> 1,
                    b = {} -> 2,
                    c = {} -> 3
                }
                result::get_or_else { result = r.a, def = 0 } + result::get_or_else { result = r.b, def = 0 } + result::get_or_else { result = r.c, def = 0 }
        """
        val result = eval(code)
        assertEquals(6L, result)
    }

    @Test
    fun `test join3 with different types`() {
        // Use CoopAsync for deterministic behavior (avoids Truffle thread issues)
        val code = """
            use std::effects::async::{Async, CoopAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform CoopAsync in do
                let r = join3 {
                    a = {} -> "first",
                    b = {} -> 2,
                    c = {} -> True
                }
                result::get_or_else { result = r.b, def = 0 }
        """
        val result = eval(code)
        assertEquals(2L, result)
    }

    // =============================================================================
    // Collection Operations - map
    // =============================================================================

    @Test
    fun `test map applies function to all elements concurrently`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result
            use std::collections::seq

            perform LoomAsync in do
                let xs = [1, 2, 3, 4, 5]
                let result = result::get_or_else { result = try_par_map {xs = xs, f = {x} -> x * 2}, def = [] }
                # Verify the result array has correct length and values
                match result is
                    [a, b, c, d, e] -> a + b + c + d + e
                    _ -> -1
        """
        val result = eval(code)
        assertEquals(30L, result) // 2 + 4 + 6 + 8 + 10 = 30
    }

    @Test
    fun `test map preserves order`() {
        // Use CoopAsync for deterministic ordering
        val code = """
            use std::effects::async::{Async, CoopAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform CoopAsync in do
                let xs = [5, 4, 3, 2, 1]
                let result = result::get_or_else { result = try_par_map {xs = xs, f = {x} -> x + 10}, def = [] }
                # Verify order is preserved by checking first element is 15
                match result is
                    [a, b, c, d, e] ->
                        if a == 15 && b == 14 && c == 13 && d == 12 && e == 11
                        then 1
                        else 0
                    _ -> -1
        """
        val result = eval(code)
        assertEquals(1L, result)
    }

    @Test
    fun `test map with empty list`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result
            use std::collections::seq

            perform LoomAsync in do
                let xs: [Int] = []
                let result = result::get_or_else { result = try_par_map {xs = xs, f = {x} -> x * 2}, def = [] }
                seq::size {xs = result}
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `test map with OSThreadAsync`() {
        val code = """
            use std::effects::async::{Async, OSThreadAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform OSThreadAsync in do
                let xs = [10, 20, 30]
                let result = result::get_or_else { result = try_par_map {xs = xs, f = {x} -> x / 10}, def = [] }
                match result is
                    [a, b, c] -> a + b + c
                    _ -> -1
        """
        val result = eval(code)
        assertEquals(6L, result) // 1 + 2 + 3 = 6
    }

    // =============================================================================
    // Collection Operations - for_each
    // =============================================================================

    @Test
    fun `test for_each executes function for each element`() {
        // for_each is for side effects, test it completes without error
        // We test by verifying it returns {} and doesn't throw
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let xs = [1, 2, 3]
                let _ = result::get_or_else { result = try_par_for_each {xs = xs, f = {x} -> {}}, def = {} }
                # Return a known value to verify completion
                42
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test for_each with empty list returns unit`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let xs: [Int] = []
                let _ = result::get_or_else { result = try_par_for_each {xs = xs, f = {x} -> {}}, def = {} }
                # Return a known value to verify completion
                42
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Collection Operations - filter
    // =============================================================================

    @Test
    fun `test filter keeps elements matching predicate`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let xs = [1, 2, 3, 4, 5, 6]
                let result = result::get_or_else { result = try_par_filter {xs = xs, f = {x} -> x % 2 == 0}, def = [] }
                match result is
                    [a, b, c] -> a + b + c
                    _ -> -1
        """
        val result = eval(code)
        assertEquals(12L, result) // 2 + 4 + 6 = 12
    }

    @Test
    fun `test filter preserves order`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let xs = [10, 5, 20, 3, 15]
                let result = result::get_or_else { result = try_par_filter {xs = xs, f = {x} -> x >= 10}, def = [] }
                match result is
                    [a, b, c] -> a * 10000 + b * 100 + c
                    _ -> -1
        """
        val result = eval(code)
        assertEquals(102015L, result) // 10, 20, 15
    }

    @Test
    fun `test filter with no matches returns empty list`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result
            use std::collections::seq

            perform LoomAsync in do
                let xs = [1, 2, 3]
                let result = result::get_or_else { result = try_par_filter {xs = xs, f = {x} -> x > 100}, def = [] }
                seq::size {xs = result}
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    @Test
    fun `test filter with all matches returns all elements`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let xs = [1, 2, 3]
                let result = result::get_or_else { result = try_par_filter {xs = xs, f = {x} -> x > 0}, def = [] }
                match result is
                    [a, b, c] -> a + b + c
                    _ -> -1
        """
        val result = eval(code)
        assertEquals(6L, result) // 1 + 2 + 3 = 6
    }

    // =============================================================================
    // Error Handling - any
    // =============================================================================

    @Test
    fun `test any returns first successful result`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}
                let p3 = Async.spawn {f = {} -> 3}
                result::get_or_else { result = try_any {ps = [p1, p2, p3]}, def = -1 }
        """
        val result = eval(code) as Long
        // any returns the first completed result - could be 1, 2, or 3
        assertTrue(result in listOf(1L, 2L, 3L))
    }

    @Test
    fun `test any with single promise`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let p = Async.spawn {f = {} -> 42}
                result::get_or_else { result = try_any {ps = [p]}, def = -1 }
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Error Handling - settle
    // =============================================================================

    @Test
    fun `test settle collects all results as Ok`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let p3 = Async.spawn {f = {} -> 30}
                let results = settle {ps = [p1, p2, p3]}
                # Match on results array to verify we have 3 items
                match results is
                    [_, _, _] -> 3
                    _ -> 0
        """
        val result = eval(code)
        assertEquals(3L, result)
    }

    @Test
    fun `test settle returns Ok variants for successful promises`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let p = Async.spawn {f = {} -> 42}
                let results = settle {ps = [p]}
                match results is
                    [Ok {val}] -> val
                    _ -> 0
        """
        val result = eval(code)
        assertEquals(42L, result)
    }

    @Test
    fun `test settle with empty list returns empty list`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let results = settle {ps = []}
                # Match on results array to verify it's empty
                match results is
                    [] -> 0
                    _ -> 1
        """
        val result = eval(code)
        assertEquals(0L, result)
    }

    // =============================================================================
    // CoopAsync Tests (single-threaded cooperative scheduling)
    // =============================================================================

    @Test
    fun `test CoopAsync join executes sequentially`() {
        val code = """
            use std::effects::async::{Async, CoopAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform CoopAsync in do
                let r = join {
                    l = {} -> 5,
                    r = {} -> 10
                }
                result::get_or_else { result = r.l, def = 0 } + result::get_or_else { result = r.r, def = 0 }
        """
        val result = eval(code)
        assertEquals(15L, result)
    }

    @Test
    fun `test CoopAsync map executes sequentially`() {
        val code = """
            use std::effects::async::{Async, CoopAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform CoopAsync in do
                let xs = [1, 2, 3]
                let result = result::get_or_else { result = try_par_map {xs = xs, f = {x} -> x * 10}, def = [] }
                match result is
                    [a, b, c] -> a + b + c
                    _ -> -1
        """
        val result = eval(code)
        assertEquals(60L, result) // 10 + 20 + 30 = 60
    }

    @Test
    fun `test CoopAsync filter works correctly`() {
        val code = """
            use std::effects::async::{Async, CoopAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform CoopAsync in do
                let xs = [1, 2, 3, 4, 5]
                let result = result::get_or_else { result = try_par_filter {xs = xs, f = {x} -> x > 2}, def = [] }
                match result is
                    [a, b, c] -> a + b + c
                    _ -> -1
        """
        val result = eval(code)
        assertEquals(12L, result) // 3 + 4 + 5 = 12
    }

    // =============================================================================
    // Integration Tests
    // =============================================================================
    @Test
    fun `debug join with two simple thunks`() {
        // No try_par_map. Just join two simple thunks returning Int.
        val code = """
            use std::effects::async::{Async, LoomAsync, join}

            perform LoomAsync in do
                let r = join { l = {} -> 10, r = {} -> 20 }
                match r.l is
                    Ok { val = lv } -> match r.r is
                        Ok { val = rv } -> lv + rv
                        _ -> -1
                    _ -> -2
        """
        assertEquals(30L, eval(code))
    }

    @org.junit.jupiter.api.Disabled("Tracked as #173 — concurrent spawn loses closure isolation across join thunks. " +
            "Confirmed: when both join sides call try_par_map with different `f` captures, the second-spawned " +
            "promise from one side uses the OTHER side's `f`. Single-side and same-`f`-different-`xs` work; " +
            "only different-`f`-across-sides breaks. Root cause is in Truffle frame/closure interaction.")
    @Test
    fun `test combining join and map`() {
        // join now returns {l: Result [Int] AsyncError, r: Result ...}.
        // The spawned thunks themselves call Async.map.
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let r = join {
                    l = {} -> result::get_or_else { result = try_par_map {xs = [1, 2], f = {x} -> x * 2}, def = [] },
                    r = {} -> result::get_or_else { result = try_par_map {xs = [3, 4], f = {x} -> x * 3}, def = [] }
                }
                # r.l = Ok { val = [2, 4] }, r.r = Ok { val = [9, 12] }
                match r.l is
                    Ok { val = lxs } -> match r.r is
                        Ok { val = rxs } -> match lxs is
                            [a, b] -> match rxs is
                                [c, d] -> a + b + c + d
                                _ -> -2
                            _ -> -3
                        _ -> -4
                    _ -> -5
        """
        val result = eval(code)
        assertEquals(27L, result) // 2 + 4 + 9 + 12 = 27
    }

    @Test
    fun `test nested spawn with await in map`() {
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let xs = [1, 2, 3]
                # Each element spawns a task that doubles it
                let results = result::get_or_else { result = try_par_map {xs = xs, f = {x} -> do
                    let p = Async.spawn {f = {} -> x * 2}
                    match Async.await {p = p} is
                        Ok { val } -> val
                        Err { error = _ } -> -1
                }, def = [] }
                match results is
                    [a, b, c] -> a + b + c
                    _ -> -1
        """
        val result = eval(code)
        assertEquals(12L, result) // 2 + 4 + 6 = 12
    }

    @Test
    fun `test filter combined with for_each`() {
        // Test that filter and for_each can be chained together
        val code = """
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter }
            use std::result

            perform LoomAsync in do
                let xs = [1, 2, 3, 4, 5, 6]
                # Filter evens
                let evens = result::get_or_else { result = try_par_filter {xs = xs, f = {x} -> x % 2 == 0}, def = [] }
                # For each should process 3 items (2, 4, 6)
                let _ = result::get_or_else { result = try_par_for_each {xs = evens, f = {x} -> {}}, def = {} }
                # Return count of evens by pattern matching
                match evens is
                    [_, _, _] -> 3
                    _ -> 0
        """
        val result = eval(code)
        assertEquals(3L, result)
    }
}
