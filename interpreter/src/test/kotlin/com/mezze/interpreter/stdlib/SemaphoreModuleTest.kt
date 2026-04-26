package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/concurrent/semaphore module.
 * Tests counting semaphores for limiting concurrent access.
 * Migrated from V1 to V2 with unboxed values.
 */
class SemaphoreModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === Basic Operations ===

    @Test
    fun `test semaphore creates with permits`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, available}

            let sem = semaphore {permits = 5}
            available {sem = sem}
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test acquire decreases available permits`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, acquire, available}

            let sem = semaphore {permits = 3}
            let _ = acquire {sem = sem}
            available {sem = sem}
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test release increases available permits`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, acquire, release, available}

            let sem = semaphore {permits = 3}
            let _ = acquire {sem = sem}
            let _ = release {sem = sem}
            available {sem = sem}
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test multiple acquires`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, acquire, available}

            let sem = semaphore {permits = 5}
            let _ = acquire {sem = sem}
            let _ = acquire {sem = sem}
            let _ = acquire {sem = sem}
            available {sem = sem}
        """)
        assertEquals(2L, result)
    }

    // === acquire_n and release_n ===

    @Test
    fun `test acquire_n acquires multiple permits`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, acquire_n, available}

            let sem = semaphore {permits = 10}
            let _ = acquire_n {sem = sem, permits = 4}
            available {sem = sem}
        """)
        assertEquals(6L, result)
    }

    @Test
    fun `test release_n releases multiple permits`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, acquire_n, release_n, available}

            let sem = semaphore {permits = 10}
            let _ = acquire_n {sem = sem, permits = 5}
            let _ = release_n {sem = sem, permits = 3}
            available {sem = sem}
        """)
        assertEquals(8L, result)
    }

    // === try_acquire ===

    @Test
    fun `test try_acquire succeeds with available permits`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, try_acquire, release}

            let sem = semaphore {permits = 1}
            let success = try_acquire {sem = sem}
            let _ = match success is
                True -> release {sem = sem}
                False -> {}
            success
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test try_acquire fails with no permits`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, acquire, try_acquire}

            let sem = semaphore {permits = 1}
            let _ = acquire {sem = sem}
            try_acquire {sem = sem}
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test try_acquire_n succeeds with enough permits`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, try_acquire_n, release_n}

            let sem = semaphore {permits = 5}
            let success = try_acquire_n {sem = sem, permits = 3}
            let _ = match success is
                True -> release_n {sem = sem, permits = 3}
                False -> {}
            success
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test try_acquire_n fails without enough permits`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, acquire_n, try_acquire_n}

            let sem = semaphore {permits = 5}
            let _ = acquire_n {sem = sem, permits = 4}
            try_acquire_n {sem = sem, permits = 3}
        """)
        assertEquals(false, result)
    }

    // === with_permit ===

    @Test
    fun `test with_permit returns function result`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, with_permit}

            let sem = semaphore {permits = 3}
            with_permit {sem = sem, f = {} -> 42}
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test with_permit releases after completion`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, with_permit, available}

            let sem = semaphore {permits = 3}
            let _ = with_permit {sem = sem, f = {} -> "done"}
            available {sem = sem}
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test with_permit with computation`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, with_permit}

            let sem = semaphore {permits = 2}
            with_permit {sem = sem, f = {} -> do
                let a = 10
                let b = 20
                a * b
            }
        """)
        assertEquals(200L, result)
    }

    // === Edge Cases ===

    @Test
    fun `test semaphore with single permit like mutex`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, with_permit, available}

            let binary = semaphore {permits = 1}
            let r = with_permit {sem = binary, f = {} -> 42}
            let avail = available {sem = binary}
            avail
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test multiple semaphores independent`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, acquire, available}

            let sem1 = semaphore {permits = 5}
            let sem2 = semaphore {permits = 10}
            let _ = acquire {sem = sem1}
            let _ = acquire {sem = sem1}
            available {sem = sem1} + available {sem = sem2}
        """)
        assertEquals(13L, result)
    }

    @Test
    fun `test release can exceed initial permits`() {
        val result = eval("""
            use std::effects::concurrent::semaphore::{semaphore, release, available}

            let sem = semaphore {permits = 1}
            let _ = release {sem = sem}
            let _ = release {sem = sem}
            available {sem = sem}
        """)
        assertEquals(3L, result)
    }
}
