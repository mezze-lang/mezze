package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/concurrent/mutex module.
 * Tests mutual exclusion locks for thread-safe access.
 * Migrated from V1 to V2 with unboxed values.
 */
class MutexModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === Basic Operations ===

    @Test
    fun `test mutex creates lock`() {
        val result = eval("""
            use std::effects::concurrent::mutex::{mutex, with_lock}

            let m = mutex {}
            with_lock {mutex = m, f = {} -> 42}
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test with_lock returns function result`() {
        val result = eval("""
            use std::effects::concurrent::mutex::{mutex, with_lock}

            let m = mutex {}
            with_lock {mutex = m, f = {} -> "hello"}
        """)
        assertEquals("hello", result)
    }

    @Test
    fun `test with_lock with computation`() {
        val result = eval("""
            use std::effects::concurrent::mutex::{mutex, with_lock}

            let m = mutex {}
            with_lock {mutex = m, f = {} -> do
                let a = 10
                let b = 20
                a + b
            }
        """)
        assertEquals(30L, result)
    }

    @Test
    fun `test multiple with_lock calls`() {
        val result = eval("""
            use std::effects::concurrent::mutex::{mutex, with_lock}

            let m = mutex {}
            let r1 = with_lock {mutex = m, f = {} -> 1}
            let r2 = with_lock {mutex = m, f = {} -> 2}
            let r3 = with_lock {mutex = m, f = {} -> 3}
            r1 + r2 + r3
        """)
        assertEquals(6L, result)
    }

    // === Manual Lock/Unlock ===

    @Test
    fun `test lock and unlock`() {
        val result = eval("""
            use std::effects::concurrent::mutex::{mutex, lock, unlock}

            let m = mutex {}
            let _ = lock {mutex = m}
            let value = 42
            let _ = unlock {mutex = m}
            value
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test try_lock succeeds on unlocked mutex`() {
        val result = eval("""
            use std::effects::concurrent::mutex::{mutex, try_lock, unlock}

            let m = mutex {}
            let success = try_lock {mutex = m}
            let _ = match success is
                True -> unlock {mutex = m}
                False -> {}
            success
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test is_held_by_current_thread when locked`() {
        val result = eval("""
            use std::effects::concurrent::mutex::{mutex, lock, unlock, is_held_by_current_thread}

            let m = mutex {}
            let _ = lock {mutex = m}
            let held = is_held_by_current_thread {mutex = m}
            let _ = unlock {mutex = m}
            held
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test is_held_by_current_thread when unlocked`() {
        val result = eval("""
            use std::effects::concurrent::mutex::{mutex, is_held_by_current_thread}

            let m = mutex {}
            is_held_by_current_thread {mutex = m}
        """)
        assertEquals(false, result)
    }

    // === Reentrant Locking ===

    @Test
    fun `test reentrant locking with with_lock`() {
        val result = eval("""
            use std::effects::concurrent::mutex::{mutex, with_lock}

            let m = mutex {}
            with_lock {mutex = m, f = {} ->
                with_lock {mutex = m, f = {} ->
                    with_lock {mutex = m, f = {} -> 42}
                }
            }
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test reentrant manual lock unlock`() {
        val result = eval("""
            use std::effects::concurrent::mutex::{mutex, lock, unlock}

            let m = mutex {}
            let _ = lock {mutex = m}
            let _ = lock {mutex = m}
            let _ = lock {mutex = m}
            let _ = unlock {mutex = m}
            let _ = unlock {mutex = m}
            let _ = unlock {mutex = m}
            42
        """)
        assertEquals(42L, result)
    }

    // === with_lock with Different Types ===

    @Test
    fun `test with_lock with record result`() {
        val result = eval("""
            use std::effects::concurrent::mutex::{mutex, with_lock}

            let m = mutex {}
            let r = with_lock {mutex = m, f = {} -> {x = 10, y = 20}}
            r.x + r.y
        """)
        assertEquals(30L, result)
    }

    @Test
    fun `test with_lock with boolean result`() {
        val result = eval("""
            use std::effects::concurrent::mutex::{mutex, with_lock}

            let m = mutex {}
            with_lock {mutex = m, f = {} -> True}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test with_lock with list result`() {
        val result = eval("""
            use std::effects::concurrent::mutex::{mutex, with_lock}
            use std::collections::seq

            let m = mutex {}
            let list = with_lock {mutex = m, f = {} -> [1, 2, 3]}
            seq::length {xs = list}
        """)
        assertEquals(3L, result)
    }

    // === Multiple Mutexes ===

    @Test
    fun `test multiple independent mutexes`() {
        val result = eval("""
            use std::effects::concurrent::mutex::{mutex, with_lock}

            let m1 = mutex {}
            let m2 = mutex {}
            let r1 = with_lock {mutex = m1, f = {} -> 10}
            let r2 = with_lock {mutex = m2, f = {} -> 20}
            r1 + r2
        """)
        assertEquals(30L, result)
    }
}
