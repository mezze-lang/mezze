package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/concurrent/mutex module.
 * Tests mutual exclusion locks for thread-safe access.
 */
class MutexModuleTest {

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

    // === Basic Operations ===

    @Test
    fun `test mutex creates lock`() {
        val result = eval("""
            use std::concurrent::mutex::{mutex, with_lock}

            let m = mutex {}
            with_lock {mutex = m, f = {} -> 42}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `test with_lock returns function result`() {
        val result = eval("""
            use std::concurrent::mutex::{mutex, with_lock}

            let m = mutex {}
            with_lock {mutex = m, f = {} -> "hello"}
        """)
        assertTrue(result is MezzeString)
        assertEquals("hello", (result as MezzeString).value)
    }

    @Test
    fun `test with_lock with computation`() {
        val result = eval("""
            use std::concurrent::mutex::{mutex, with_lock}

            let m = mutex {}
            with_lock {mutex = m, f = {} -> do
                let a = 10
                let b = 20
                a + b
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(30L, (result as MezzeInt).value)
    }

    @Test
    fun `test multiple with_lock calls`() {
        val result = eval("""
            use std::concurrent::mutex::{mutex, with_lock}

            let m = mutex {}
            let r1 = with_lock {mutex = m, f = {} -> 1}
            let r2 = with_lock {mutex = m, f = {} -> 2}
            let r3 = with_lock {mutex = m, f = {} -> 3}
            r1 + r2 + r3
        """)
        assertTrue(result is MezzeInt)
        assertEquals(6L, (result as MezzeInt).value)
    }

    // === Manual Lock/Unlock ===

    @Test
    fun `test lock and unlock`() {
        val result = eval("""
            use std::concurrent::mutex::{mutex, lock, unlock}

            let m = mutex {}
            let _ = lock {mutex = m}
            let value = 42
            let _ = unlock {mutex = m}
            value
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `test try_lock succeeds on unlocked mutex`() {
        val result = eval("""
            use std::concurrent::mutex::{mutex, try_lock, unlock}

            let m = mutex {}
            let success = try_lock {mutex = m}
            let _ = match success is
                True -> unlock {mutex = m}
                False -> {}
            success
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_held_by_current_thread when locked`() {
        val result = eval("""
            use std::concurrent::mutex::{mutex, lock, unlock, is_held_by_current_thread}

            let m = mutex {}
            let _ = lock {mutex = m}
            let held = is_held_by_current_thread {mutex = m}
            let _ = unlock {mutex = m}
            held
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test is_held_by_current_thread when unlocked`() {
        val result = eval("""
            use std::concurrent::mutex::{mutex, is_held_by_current_thread}

            let m = mutex {}
            is_held_by_current_thread {mutex = m}
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    // === Reentrant Locking ===

    @Test
    fun `test reentrant locking with with_lock`() {
        val result = eval("""
            use std::concurrent::mutex::{mutex, with_lock}

            let m = mutex {}
            with_lock {mutex = m, f = {} ->
                with_lock {mutex = m, f = {} ->
                    with_lock {mutex = m, f = {} -> 42}
                }
            }
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `test reentrant manual lock unlock`() {
        val result = eval("""
            use std::concurrent::mutex::{mutex, lock, unlock}

            let m = mutex {}
            let _ = lock {mutex = m}
            let _ = lock {mutex = m}
            let _ = lock {mutex = m}
            let _ = unlock {mutex = m}
            let _ = unlock {mutex = m}
            let _ = unlock {mutex = m}
            42
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    // === with_lock with Different Types ===

    @Test
    fun `test with_lock with record result`() {
        val result = eval("""
            use std::concurrent::mutex::{mutex, with_lock}

            let m = mutex {}
            let r = with_lock {mutex = m, f = {} -> {x = 10, y = 20}}
            r.x + r.y
        """)
        assertTrue(result is MezzeInt)
        assertEquals(30L, (result as MezzeInt).value)
    }

    @Test
    fun `test with_lock with boolean result`() {
        val result = eval("""
            use std::concurrent::mutex::{mutex, with_lock}

            let m = mutex {}
            with_lock {mutex = m, f = {} -> True}
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test with_lock with list result`() {
        val result = eval("""
            use std::concurrent::mutex::{mutex, with_lock}

            let m = mutex {}
            let list = with_lock {mutex = m, f = {} -> [1, 2, 3]}
            __native_arraylist_length {xs = list}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(3L, (result as MezzeInt).value)
    }

    // === Multiple Mutexes ===

    @Test
    fun `test multiple independent mutexes`() {
        val result = eval("""
            use std::concurrent::mutex::{mutex, with_lock}

            let m1 = mutex {}
            let m2 = mutex {}
            let r1 = with_lock {mutex = m1, f = {} -> 10}
            let r2 = with_lock {mutex = m2, f = {} -> 20}
            r1 + r2
        """)
        assertTrue(result is MezzeInt)
        assertEquals(30L, (result as MezzeInt).value)
    }
}
