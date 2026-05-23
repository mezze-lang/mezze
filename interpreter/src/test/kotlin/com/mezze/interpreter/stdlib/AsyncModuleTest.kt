package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/async module.
 * Tests asynchronous computation using CoopAsync (cooperative scheduling) for deterministic testing.
 * Migrated from V1 to V2 with unboxed values.
 */
class AsyncModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Helper to wrap code in CoopAsync handler
    private fun withCoopAsync(body: String): String =
        "use std::effects::async::{Async, CoopAsync, race, with_timeout, join, join3, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter, is_done }\n" +
        "use std::collections::seq\n" +
        "use std::result\n" +
        "\n" +
        "perform CoopAsync in do\n" +
        body.trimIndent().lines().joinToString("\n") { "    $it" }

    // === CoopAsync Handler Tests ===

    @Test
    fun `test CoopAsync executes computation`() {
        val result = eval(withCoopAsync("""
            let p = Async.spawn {f = {} -> 42}
            result::get_or_else { result = Async.await {p = p}, def = -1 }
        """))
        assertEquals(42L, result)
    }

    // === spawn and await Tests ===

    @Test
    fun `test spawn and await returns result`() {
        val result = eval(withCoopAsync("""
            let p = Async.spawn {f = {} -> 42}
            result::get_or_else { result = Async.await {p = p}, def = -1 }
        """))
        assertEquals(42L, result)
    }

    @Test
    fun `test spawn multiple tasks`() {
        val result = eval(withCoopAsync("""
            let p1 = Async.spawn {f = {} -> 10}
            let p2 = Async.spawn {f = {} -> 20}
            let r1 = result::get_or_else { result = Async.await {p = p1}, def = -1 }
            let r2 = result::get_or_else { result = Async.await {p = p2}, def = -1 }
            r1 + r2
        """))
        assertEquals(30L, result)
    }

    // === is_done Tests ===

    @Test
    fun `test is_done on completed promise`() {
        val result = eval(withCoopAsync("""
            let p = Async.spawn {f = {} -> 42}
            let _ = result::get_or_else { result = Async.await {p = p}, def = -1 }
            is_done {p = p}
        """))
        assertEquals(true, result)
    }

    // === all Tests ===

    @Test
    fun `test all waits for all promises`() {
        val result = eval(withCoopAsync("""
            let p1 = Async.spawn {f = {} -> 1}
            let p2 = Async.spawn {f = {} -> 2}
            let p3 = Async.spawn {f = {} -> 3}
            let results = result::get_or_else { result = try_all {ps = [p1, p2, p3]}, def = [] }
            seq::size {xs = results}
        """))
        assertEquals(3L, result)
    }

    @Test
    fun `test all returns results in order`() {
        val result = eval(withCoopAsync("""
            let p1 = Async.spawn {f = {} -> 10}
            let p2 = Async.spawn {f = {} -> 20}
            let results = result::get_or_else { result = try_all {ps = [p1, p2]}, def = [] }
            match seq::get {xs = results, i = 0} is
                Some {val} -> val
                None -> 0
        """))
        assertEquals(10L, result)
    }

    // === race Tests ===

    @Test
    fun `test race returns first completed`() {
        val result = eval(withCoopAsync("""
            let p1 = Async.spawn {f = {} -> 1}
            let p2 = Async.spawn {f = {} -> 2}
            match race {ps = [p1, p2]} is
              Ok { val } -> val
              Err { error = _ } -> -1
        """))
        // In coop scheduler, first one wins
        assertEquals(1L, result)
    }

    // === join Tests ===

    @Test
    fun `test join runs two computations`() {
        val result = eval(withCoopAsync("""
            let r = join {
                l = {} -> 10,
                r = {} -> 20
            }
            result::get_or_else { result = r.l, def = 0 } + result::get_or_else { result = r.r, def = 0 }
        """))
        assertEquals(30L, result)
    }

    // === join3 Tests ===

    @Test
    fun `test join3 runs three computations`() {
        val result = eval(withCoopAsync("""
            let r = join3 {
                a = {} -> 1,
                b = {} -> 2,
                c = {} -> 3
            }
            result::get_or_else { result = r.a, def = 0 } + result::get_or_else { result = r.b, def = 0 } + result::get_or_else { result = r.c, def = 0 }
        """))
        assertEquals(6L, result)
    }

    // === map Tests ===

    @Test
    fun `test map applies function concurrently`() {
        val result = eval(withCoopAsync("""
            let results = result::get_or_else { result = try_par_map {xs = [1, 2, 3], f = {x} -> x * 2}, def = [] }
            seq::size {xs = results}
        """))
        assertEquals(3L, result)
    }

    @Test
    fun `test map preserves order`() {
        val result = eval(withCoopAsync("""
            let results = result::get_or_else { result = try_par_map {xs = [10, 20, 30], f = {x} -> x + 1}, def = [] }
            match seq::get {xs = results, i = 0} is
                Some {val} -> val
                None -> 0
        """))
        assertEquals(11L, result)
    }

    // === for_each Tests ===

    @Test
    fun `test for_each returns unit`() {
        val result = eval(withCoopAsync("""
            result::get_or_else { result = try_par_for_each {xs = [1, 2, 3], f = {x} -> {}}, def = {} }
            42
        """))
        assertEquals(42L, result)
    }

    // === filter Tests ===

    @Test
    fun `test filter keeps matching elements`() {
        val result = eval(withCoopAsync("""
            let results = result::get_or_else { result = try_par_filter {xs = [1, 2, 3, 4, 5], f = {x} -> x % 2 == 0}, def = [] }
            seq::size {xs = results}
        """))
        assertEquals(2L, result)
    }

    // === timeout Tests ===

    @Test
    fun `test timeout returns Some when fast`() {
        val result = eval(withCoopAsync("""
            let p = Async.spawn {f = {} -> 42}
            match with_timeout {ms = 1000, p = p} is
                Some {val} -> val
                None -> 0
        """))
        assertEquals(42L, result)
    }

    // === run Helper Tests ===

    @Test
    fun `test run helper spawns and awaits`() {
        val result = eval("""
            use std::effects::async::{Async, CoopAsync, run, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter, is_done }
            use std::result

            perform CoopAsync in do
                result::get_or_else { result = run {f = {} -> 42}, def = -1 }
        """)
        assertEquals(42L, result)
    }

    // === go Helper Tests ===

    @Test
    fun `test go helper fires and forgets`() {
        val result = eval("""
            use std::effects::async::{Async, CoopAsync, go, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter, is_done }

            perform CoopAsync in do
                go {f = {} -> 42}
                100
        """)
        assertEquals(100L, result)
    }

    // === AsyncError Type Tests ===

    @Test
    fun `test AsyncFailed construction`() {
        val result = eval("""
            use std::effects::async::{AsyncError, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter, is_done }
            use std::result

            let err: AsyncError = AsyncFailed {msg = "test error"}
            match err is
                AsyncFailed {msg} -> msg
                _ -> ""
        """)
        assertEquals("test error", result)
    }

    @Test
    fun `test AsyncCancelled construction`() {
        val result = eval("""
            use std::effects::async::{AsyncError, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter, is_done }
            use std::result

            let err: AsyncError = AsyncCancelled
            match err is
                AsyncCancelled -> True
                _ -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test AsyncTimedOut construction`() {
        val result = eval("""
            use std::effects::async::{AsyncError, par_map, par_for_each, par_filter, all, settle, try_all, try_any, try_par_map, try_par_for_each, try_par_filter, is_done }
            use std::result

            let err: AsyncError = AsyncTimedOut {ms = 5000}
            match err is
                AsyncTimedOut {ms} -> ms
                _ -> 0
        """)
        assertEquals(5000L, result)
    }

    // === Nested spawn/await Tests ===

    @Test
    fun `test nested spawn and await`() {
        val result = eval(withCoopAsync("""
            let outer = Async.spawn {f = {} -> do
                let inner = Async.spawn {f = {} -> 42}
                result::get_or_else { result = Async.await {p = inner}, def = -1 }
            }
            result::get_or_else { result = Async.await {p = outer}, def = -1 }
        """))
        assertEquals(42L, result)
    }

    // === settle Tests ===

    @Test
    fun `test settle returns Results`() {
        val result = eval(withCoopAsync("""
            let p1 = Async.spawn {f = {} -> 1}
            let p2 = Async.spawn {f = {} -> 2}
            let results = settle {ps = [p1, p2]}
            seq::size {xs = results}
        """))
        assertEquals(2L, result)
    }

    // === Complex workflow Tests ===

    @Test
    fun `test complex async workflow`() {
        val result = eval(withCoopAsync("""
            # Spawn multiple tasks
            let tasks = result::get_or_else { result = try_par_map {
                xs = [1, 2, 3, 4, 5],
                f = {x} -> x * x
            }, def = [] }
            # Sum the results
            let sum = seq::fold {
                xs = tasks,
                init = 0,
                f = {acc, x} -> acc + x
            }
            sum
        """))
        assertEquals(55L, result) // 1 + 4 + 9 + 16 + 25
    }
}
