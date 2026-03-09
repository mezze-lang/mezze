package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
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
    private fun withCoopAsync(body: String): String = """
        use std::effects::async::{Async, CoopAsync}
        use std::control::continuation
        use std::collections::seq

        perform CoopAsync {cont = continuation::new {}} in do
            $body
    """

    // === CoopAsync Handler Tests ===

    @Test
    fun `test CoopAsync executes computation`() {
        val result = eval(withCoopAsync("""
            let p = Async.spawn {f = {} -> 42}
            Async.await {p = p}
        """))
        assertEquals(42L, result)
    }

    // === spawn and await Tests ===

    @Test
    fun `test spawn and await returns result`() {
        val result = eval(withCoopAsync("""
            let p = Async.spawn {f = {} -> 42}
            Async.await {p = p}
        """))
        assertEquals(42L, result)
    }

    @Test
    fun `test spawn multiple tasks`() {
        val result = eval(withCoopAsync("""
            let p1 = Async.spawn {f = {} -> 10}
            let p2 = Async.spawn {f = {} -> 20}
            let r1 = Async.await {p = p1}
            let r2 = Async.await {p = p2}
            r1 + r2
        """))
        assertEquals(30L, result)
    }

    // === is_done Tests ===

    @Test
    fun `test is_done on completed promise`() {
        val result = eval(withCoopAsync("""
            let p = Async.spawn {f = {} -> 42}
            let _ = Async.await {p = p}
            Async.is_done {p = p}
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
            let results = Async.all {ps = [p1, p2, p3]}
            seq::length {xs = results}
        """))
        assertEquals(3L, result)
    }

    @Test
    fun `test all returns results in order`() {
        val result = eval(withCoopAsync("""
            let p1 = Async.spawn {f = {} -> 10}
            let p2 = Async.spawn {f = {} -> 20}
            let results = Async.all {ps = [p1, p2]}
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
            Async.race {ps = [p1, p2]}
        """))
        // In coop scheduler, first one wins
        assertEquals(1L, result)
    }

    // === join Tests ===

    @Test
    fun `test join runs two computations`() {
        val result = eval(withCoopAsync("""
            let r = Async.join {
                left = {} -> 10,
                right = {} -> 20
            }
            r.left + r.right
        """))
        assertEquals(30L, result)
    }

    // === join3 Tests ===

    @Test
    fun `test join3 runs three computations`() {
        val result = eval(withCoopAsync("""
            let r = Async.join3 {
                a = {} -> 1,
                b = {} -> 2,
                c = {} -> 3
            }
            r.a + r.b + r.c
        """))
        assertEquals(6L, result)
    }

    // === map Tests ===

    @Test
    fun `test map applies function concurrently`() {
        val result = eval(withCoopAsync("""
            let results = Async.map {xs = [1, 2, 3], f = {x} -> x * 2}
            seq::length {xs = results}
        """))
        assertEquals(3L, result)
    }

    @Test
    fun `test map preserves order`() {
        val result = eval(withCoopAsync("""
            let results = Async.map {xs = [10, 20, 30], f = {x} -> x + 1}
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
            Async.for_each {xs = [1, 2, 3], f = {x} -> {}}
            42
        """))
        assertEquals(42L, result)
    }

    // === filter Tests ===

    @Test
    fun `test filter keeps matching elements`() {
        val result = eval(withCoopAsync("""
            let results = Async.filter {xs = [1, 2, 3, 4, 5], f = {x} -> x % 2 == 0}
            seq::length {xs = results}
        """))
        assertEquals(2L, result)
    }

    // === timeout Tests ===

    @Test
    fun `test timeout returns Some when fast`() {
        val result = eval(withCoopAsync("""
            let p = Async.spawn {f = {} -> 42}
            match Async.timeout {ms = 1000, p = p} is
                Some {val} -> val
                None -> 0
        """))
        assertEquals(42L, result)
    }

    // === run Helper Tests ===

    @Test
    fun `test run helper spawns and awaits`() {
        val result = eval("""
            use std::effects::async::{Async, CoopAsync, run}
            use std::control::continuation

            perform CoopAsync {cont = continuation::new {}} in do
                run {f = {} -> 42}
        """)
        assertEquals(42L, result)
    }

    // === go Helper Tests ===

    @Test
    fun `test go helper fires and forgets`() {
        val result = eval("""
            use std::effects::async::{Async, CoopAsync, go}
            use std::control::continuation

            perform CoopAsync {cont = continuation::new {}} in do
                go {f = {} -> 42}
                100
        """)
        assertEquals(100L, result)
    }

    // === AsyncError Type Tests ===

    @Test
    fun `test AsyncFailed construction`() {
        val result = eval("""
            use std::effects::async::{AsyncError}

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
            use std::effects::async::{AsyncError}

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
            use std::effects::async::{AsyncError}

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
                Async.await {p = inner}
            }
            Async.await {p = outer}
        """))
        assertEquals(42L, result)
    }

    // === settle Tests ===

    @Test
    fun `test settle returns Results`() {
        val result = eval(withCoopAsync("""
            let p1 = Async.spawn {f = {} -> 1}
            let p2 = Async.spawn {f = {} -> 2}
            let results = Async.settle {ps = [p1, p2]}
            seq::length {xs = results}
        """))
        assertEquals(2L, result)
    }

    // === Complex workflow Tests ===

    @Test
    fun `test complex async workflow`() {
        val result = eval(withCoopAsync("""
            # Spawn multiple tasks
            let tasks = Async.map {
                xs = [1, 2, 3, 4, 5],
                f = {x} -> x * x
            }
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
