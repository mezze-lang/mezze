package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/async module.
 *
 * These run under `LoomAsync`, which is the Project Loom VIRTUAL-THREAD
 * handler with `max_concurrent = 0` — genuinely concurrent, not cooperative.
 * (This file used to claim the opposite, and a test built on that claim
 * failed intermittently in the gate.) So a test here may assert on results
 * that are ordered by their INPUT — `all`, `settle`, `try_all` all keep the
 * caller's order — but never on which task happens to finish first.
 *
 * Migrated from V1 to V2 with unboxed values.
 */
class AsyncModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // Helper to wrap code in LoomAsync handler
    private fun withLoomAsync(body: String): String =
        "use std::effects::async::{ Async, LoomAsync, all, await, is_done, join, join3, par_filter, par_for_each, par_map, promise_from, promise_of, race, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map, with_timeout }\n" +
        "use std::collections::seq\n" +
        "use std::result\n" +
        "\n" +
        "perform LoomAsync in do\n" +
        body.trimIndent().lines().joinToString("\n") { "    $it" }

    // === LoomAsync Handler Tests ===

    @Test
    fun `test LoomAsync executes computation`() {
        val result = eval(withLoomAsync("""
            let p = Async.spawn {f = {} -> 42}
            result::get_or { result = await {p = p}, def = -1 }
        """))
        assertEquals(42L, result)
    }

    // === spawn and await Tests ===

    @Test
    fun `test spawn and await returns result`() {
        val result = eval(withLoomAsync("""
            let p = Async.spawn {f = {} -> 42}
            result::get_or { result = await {p = p}, def = -1 }
        """))
        assertEquals(42L, result)
    }

    @Test
    fun `test spawn multiple tasks`() {
        val result = eval(withLoomAsync("""
            let p1 = Async.spawn {f = {} -> 10}
            let p2 = Async.spawn {f = {} -> 20}
            let r1 = result::get_or { result = await {p = p1}, def = -1 }
            let r2 = result::get_or { result = await {p = p2}, def = -1 }
            r1 + r2
        """))
        assertEquals(30L, result)
    }

    // === is_done Tests ===

    @Test
    fun `test is_done on completed promise`() {
        val result = eval(withLoomAsync("""
            let p = Async.spawn {f = {} -> 42}
            let _ = result::get_or { result = await {p = p}, def = -1 }
            is_done {p = p}
        """))
        assertEquals(true, result)
    }

    // === all Tests ===

    @Test
    fun `test all waits for all promises`() {
        val result = eval(withLoomAsync("""
            let p1 = Async.spawn {f = {} -> 1}
            let p2 = Async.spawn {f = {} -> 2}
            let p3 = Async.spawn {f = {} -> 3}
            let results = result::get_or { result = try_all {ps = [p1, p2, p3]}, def = [] }
            seq::size {xs = results}
        """))
        assertEquals(3L, result)
    }

    @Test
    fun `test all returns results in order`() {
        val result = eval(withLoomAsync("""
            let p1 = Async.spawn {f = {} -> 10}
            let p2 = Async.spawn {f = {} -> 20}
            let results = result::get_or { result = try_all {ps = [p1, p2]}, def = [] }
            match seq::get {xs = results, i = 0} is
                Some {val} -> val
                None -> 0
        """))
        assertEquals(10L, result)
    }

    // === race Tests ===

    @Test
    fun `test race returns one of the racers, not an error`() {
        val result = eval(withLoomAsync("""
            let p1 = Async.spawn {f = {} -> 1}
            let p2 = Async.spawn {f = {} -> 2}
            match race {ps = [p1, p2]} is
              Ok { val } -> val
              Err { error = _ } -> -1
        """))
        // The racers run on virtual threads, so which one finishes first is
        // the scheduler's call. `race` promises Ok with SOME racer's value,
        // and that is what this checks; the old assertEquals(1L) encoded a
        // first-wins guarantee the Loom handler does not make, and failed in
        // the gate whenever p2 won.
        assertTrue(
            result == 1L || result == 2L,
            "race must yield one of the racers' values, got: $result",
        )
    }

    // === join Tests ===

    @Test
    fun `test join runs two computations`() {
        val result = eval(withLoomAsync("""
            let r = join {
                l = {} -> 10,
                r = {} -> 20
            }
            result::get_or { result = r.l, def = 0 } + result::get_or { result = r.r, def = 0 }
        """))
        assertEquals(30L, result)
    }

    // === join3 Tests ===

    @Test
    fun `test join3 runs three computations`() {
        val result = eval(withLoomAsync("""
            let r = join3 {
                a = {} -> 1,
                b = {} -> 2,
                c = {} -> 3
            }
            result::get_or { result = r.a, def = 0 } + result::get_or { result = r.b, def = 0 } + result::get_or { result = r.c, def = 0 }
        """))
        assertEquals(6L, result)
    }

    // === map Tests ===

    @Test
    fun `test map applies function concurrently`() {
        val result = eval(withLoomAsync("""
            let results = result::get_or { result = try_par_map {xs = [1, 2, 3], f = {x} -> x * 2}, def = [] }
            seq::size {xs = results}
        """))
        assertEquals(3L, result)
    }

    @Test
    fun `test map preserves order`() {
        val result = eval(withLoomAsync("""
            let results = result::get_or { result = try_par_map {xs = [10, 20, 30], f = {x} -> x + 1}, def = [] }
            match seq::get {xs = results, i = 0} is
                Some {val} -> val
                None -> 0
        """))
        assertEquals(11L, result)
    }

    // === for_each Tests ===

    @Test
    fun `test for_each returns unit`() {
        val result = eval(withLoomAsync("""
            result::get_or { result = try_par_for_each {xs = [1, 2, 3], f = {x} -> {}}, def = {} }
            42
        """))
        assertEquals(42L, result)
    }

    // === filter Tests ===

    @Test
    fun `test filter keeps matching elements`() {
        val result = eval(withLoomAsync("""
            let results = result::get_or { result = try_par_filter {xs = [1, 2, 3, 4, 5], f = {x} -> x % 2 == 0}, def = [] }
            seq::size {xs = results}
        """))
        assertEquals(2L, result)
    }

    // === timeout Tests ===
    //
    // These used to be a race. `with_timeout` spawned a task that slept for the
    // deadline and parked until either it or the target was done — so the answer
    // depended on which of two promises the scheduler reached first, and on the
    // sleeper SUCCEEDING. `is_done` holds for a failed promise too, and a sleeper
    // performs `Time`, which no test here installs: the sleeper died on an
    // unhandled op, counted as done immediately, and `ms = 1000` against a task
    // returning 42 answered `None` every so often.
    //
    // The deadline belongs to the thread that waits. With one `park_until` there
    // is no second promise, no `Time`, and nothing left to lose a race to.

    @Test
    fun `test timeout returns Some when fast`() {
        val result = eval(withLoomAsync("""
            let p = Async.spawn {f = {} -> 42}
            match with_timeout {ms = 1000, p = p} is
                Some {val} -> val
                None -> 0
        """))
        assertEquals(42L, result)
    }

    @Test
    fun `test timeout needs no Time performer`() {
        // The bite. Nothing in this program installs a `Time` performer, and a
        // thousand runs of a task that is instant must all say 42 — the old
        // implementation lost this perhaps one run in a few hundred, which is
        // exactly the frequency that survives review and fails in CI.
        repeat(50) { attempt ->
            val result = eval(withLoomAsync("""
                let p = Async.spawn {f = {} -> 42}
                match with_timeout {ms = 1000, p = p} is
                    Some {val} -> val
                    None -> 0
            """))
            assertEquals(42L, result, "attempt $attempt answered a timeout that had not elapsed")
        }
    }

    @Test
    fun `test timeout returns None when the promise never settles`() {
        // No sleeping on either side: the promise resolves nothing, ever, so the
        // deadline is the only thing that can end the wait.
        val result = eval(withLoomAsync("""
            let p = promise_from {setup = {complete, fail, on_cancel} -> {}}
            match with_timeout {ms = 30, p = p} is
                Some {val} -> val
                None -> 0 - 7
        """))
        assertEquals(-7L, result)
    }

    @Test
    fun `test timeout with ms zero polls without waiting`() {
        // `ms = 0` asks "is it done yet?" and must answer without parking. An
        // already-resolved promise says yes; one that never resolves says no.
        val settled = eval(withLoomAsync("""
            match with_timeout {ms = 0, p = promise_of {val = 9}} is
                Some {val} -> val
                None -> 0
        """))
        assertEquals(9L, settled)

        val pending = eval(withLoomAsync("""
            let p = promise_from {setup = {complete, fail, on_cancel} -> {}}
            match with_timeout {ms = 0, p = p} is
                Some {val} -> val
                None -> 0 - 7
        """))
        assertEquals(-7L, pending)
    }

    // === run Helper Tests ===

    @Test
    fun `test run helper spawns and awaits`() {
        val result = eval("""
            use std::effects::async::{ Async, LoomAsync, all, await, is_done, par_filter, par_for_each, par_map, run, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map }
            use std::result

            perform LoomAsync in do
                result::get_or { result = run {f = {} -> 42}, def = -1 }
        """)
        assertEquals(42L, result)
    }

    // === go Helper Tests ===

    @Test
    fun `test go helper fires and forgets`() {
        val result = eval("""
            use std::effects::async::{ Async, LoomAsync, all, await, go, is_done, par_filter, par_for_each, par_map, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map }

            perform LoomAsync in do
                go {f = {} -> 42}
                100
        """)
        assertEquals(100L, result)
    }

    // === AsyncError Type Tests ===

    @Test
    fun `test AsyncFailed construction`() {
        val result = eval("""
            use std::effects::async::{ AsyncError, all, await, is_done, par_filter, par_for_each, par_map, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map }
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
            use std::effects::async::{ AsyncError, all, await, is_done, par_filter, par_for_each, par_map, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map }
            use std::result

            let err: AsyncError = AsyncCancelled { reason = UserRequested { msg = "test" } }
            match err is
                AsyncCancelled { reason = _ } -> True
                _ -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test AsyncTimedOut construction`() {
        val result = eval("""
            use std::effects::async::{ AsyncError, all, await, is_done, par_filter, par_for_each, par_map, settle, try_all, try_any, try_par_filter, try_par_for_each, try_par_map }
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
        val result = eval(withLoomAsync("""
            let outer = Async.spawn {f = {} -> do
                let inner = Async.spawn {f = {} -> 42}
                result::get_or { result = await {p = inner}, def = -1 }
            }
            result::get_or { result = await {p = outer}, def = -1 }
        """))
        assertEquals(42L, result)
    }

    // === settle Tests ===

    @Test
    fun `test settle returns Results`() {
        val result = eval(withLoomAsync("""
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
        val result = eval(withLoomAsync("""
            # Spawn multiple tasks
            let tasks = result::get_or { result = try_par_map {
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
