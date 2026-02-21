package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/async module.
 * Tests asynchronous computation using CoopAsync (cooperative scheduling) for deterministic testing.
 */
class AsyncModuleTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return result
    }

    // === run_coop Helper Tests ===

    @Test
    fun `test run_coop executes computation`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    // === spawn and await Tests ===

    @Test
    fun `test spawn and await returns result`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `test spawn multiple tasks`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                r1 + r2
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(30L, (result as MezzeInt).value)
    }

    // === is_done Tests ===

    @Test
    fun `test is_done on completed promise`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                let p = Async.spawn {f = {} -> 42}
                let _ = Async.await {p = p}
                Async.is_done {p = p}
            }
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    // === all Tests ===

    @Test
    fun `test all waits for all promises`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}
                let p3 = Async.spawn {f = {} -> 3}
                let results = Async.all {ps = [p1, p2, p3]}
                __native_arraylist_length {xs = results}
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(3L, (result as MezzeInt).value)
    }

    @Test
    fun `test all returns results in order`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let results = Async.all {ps = [p1, p2]}
                match __native_arraylist_get {xs = results, i = 0} is
                    Some {val} -> val
                    None -> 0
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(10L, (result as MezzeInt).value)
    }

    // === race Tests ===

    @Test
    fun `test race returns first completed`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}
                Async.race {ps = [p1, p2]}
            }
        """)
        assertTrue(result is MezzeInt)
        // In coop scheduler, first one wins
        assertEquals(1L, (result as MezzeInt).value)
    }

    // === join Tests ===

    @Test
    fun `test join runs two computations`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                let r = Async.join {
                    left = {} -> 10,
                    right = {} -> 20
                }
                r.left + r.right
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(30L, (result as MezzeInt).value)
    }

    // === join3 Tests ===

    @Test
    fun `test join3 runs three computations`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                let r = Async.join3 {
                    a = {} -> 1,
                    b = {} -> 2,
                    c = {} -> 3
                }
                r.a + r.b + r.c
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(6L, (result as MezzeInt).value)
    }

    // === map Tests ===

    @Test
    fun `test map applies function concurrently`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                let results = Async.map {xs = [1, 2, 3], f = {x} -> x * 2}
                __native_arraylist_length {xs = results}
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(3L, (result as MezzeInt).value)
    }

    @Test
    fun `test map preserves order`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                let results = Async.map {xs = [10, 20, 30], f = {x} -> x + 1}
                match __native_arraylist_get {xs = results, i = 0} is
                    Some {val} -> val
                    None -> 0
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(11L, (result as MezzeInt).value)
    }

    // === for_each Tests ===

    @Test
    fun `test for_each returns unit`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                Async.for_each {xs = [1, 2, 3], f = {x} -> {}}
                42
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    // === filter Tests ===

    @Test
    fun `test filter keeps matching elements`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                let results = Async.filter {xs = [1, 2, 3, 4, 5], f = {x} -> x % 2 == 0}
                __native_arraylist_length {xs = results}
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(2L, (result as MezzeInt).value)
    }

    // === timeout Tests ===

    @Test
    fun `test timeout returns Some when fast`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                let p = Async.spawn {f = {} -> 42}
                match Async.timeout {ms = 1000, p = p} is
                    Some {val} -> val
                    None -> 0
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    // === run Helper Tests ===

    @Test
    fun `test run helper spawns and awaits`() {
        val result = eval("""
            use std::async::{Async, run_coop, run}

            run_coop {computation = {} -> do
                run {f = {} -> 42}
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    // === go Helper Tests ===

    @Test
    fun `test go helper fires and forgets`() {
        val result = eval("""
            use std::async::{Async, run_coop, go}

            run_coop {computation = {} -> do
                go {f = {} -> 42}
                100
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(100L, (result as MezzeInt).value)
    }

    // === AsyncError Type Tests ===

    @Test
    fun `test AsyncFailed construction`() {
        val result = eval("""
            use std::async::{AsyncError}

            let err: AsyncError = AsyncFailed {msg = "test error"}
            match err is
                AsyncFailed {msg} -> msg
                _ -> ""
        """)
        assertTrue(result is MezzeString)
        assertEquals("test error", (result as MezzeString).value)
    }

    @Test
    fun `test AsyncCancelled construction`() {
        val result = eval("""
            use std::async::{AsyncError}

            let err: AsyncError = AsyncCancelled
            match err is
                AsyncCancelled -> True
                _ -> False
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test AsyncTimedOut construction`() {
        val result = eval("""
            use std::async::{AsyncError}

            let err: AsyncError = AsyncTimedOut {ms = 5000}
            match err is
                AsyncTimedOut {ms} -> ms
                _ -> 0
        """)
        assertTrue(result is MezzeInt)
        assertEquals(5000L, (result as MezzeInt).value)
    }

    // === Nested spawn/await Tests ===

    @Test
    fun `test nested spawn and await`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                let outer = Async.spawn {f = {} -> do
                    let inner = Async.spawn {f = {} -> 42}
                    Async.await {p = inner}
                }
                Async.await {p = outer}
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    // === settle Tests ===

    @Test
    fun `test settle returns Results`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                let p1 = Async.spawn {f = {} -> 1}
                let p2 = Async.spawn {f = {} -> 2}
                let results = Async.settle {ps = [p1, p2]}
                __native_arraylist_length {xs = results}
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(2L, (result as MezzeInt).value)
    }

    // === Complex workflow Tests ===

    @Test
    fun `test complex async workflow`() {
        val result = eval("""
            use std::async::{Async, run_coop}

            run_coop {computation = {} -> do
                # Spawn multiple tasks
                let tasks = Async.map {
                    xs = [1, 2, 3, 4, 5],
                    f = {x} -> x * x
                }
                # Sum the results
                let sum = __native_arraylist_fold {
                    xs = tasks,
                    init = 0,
                    f = {acc, x} -> acc + x
                }
                sum
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(55L, (result as MezzeInt).value) // 1 + 4 + 9 + 16 + 25
    }
}
