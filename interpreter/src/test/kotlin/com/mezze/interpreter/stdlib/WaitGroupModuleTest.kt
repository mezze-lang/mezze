package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/concurrent/waitgroup module.
 * Tests wait groups for dynamic task synchronization.
 * Migrated from V1 to V2 with unboxed values.
 */
class WaitGroupModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === Basic Operations ===

    @Test
    fun `test wait_group creates with zero count`() {
        val result = eval("""
            use std::effects::concurrent::waitgroup::{wait_group, get_count_wg}

            let wg = wait_group {}
            get_count_wg {wg = wg}
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `test add increases count`() {
        val result = eval("""
            use std::effects::concurrent::waitgroup::{wait_group, add, get_count_wg}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 3}
            get_count_wg {wg = wg}
        """)
        assertEquals(3L, result)
    }

    @Test
    fun `test done decreases count`() {
        val result = eval("""
            use std::effects::concurrent::waitgroup::{wait_group, add, done, get_count_wg}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 3}
            let _ = done {wg = wg}
            get_count_wg {wg = wg}
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test multiple adds and dones`() {
        val result = eval("""
            use std::effects::concurrent::waitgroup::{wait_group, add, done, get_count_wg}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 5}
            let _ = done {wg = wg}
            let _ = done {wg = wg}
            let _ = add {wg = wg, delta = 2}
            let _ = done {wg = wg}
            get_count_wg {wg = wg}
        """)
        assertEquals(4L, result)
    }

    // === wait ===

    @Test
    fun `test wait returns immediately when zero`() {
        val result = eval("""
            use std::effects::concurrent::waitgroup::{wait_group, wait}

            let wg = wait_group {}
            let _ = wait {wg = wg}
            42
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test wait after add and done`() {
        val result = eval("""
            use std::effects::concurrent::waitgroup::{wait_group, add, done, wait}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 2}
            let _ = done {wg = wg}
            let _ = done {wg = wg}
            let _ = wait {wg = wg}
            "done"
        """)
        assertEquals("done", result)
    }

    // === wait_timeout ===

    @Test
    fun `test wait_timeout returns true when zero`() {
        val result = eval("""
            use std::effects::concurrent::waitgroup::{wait_group, wait_timeout}

            let wg = wait_group {}
            wait_timeout {wg = wg, ms = 1000}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test wait_timeout returns false on timeout`() {
        val result = eval("""
            use std::effects::concurrent::waitgroup::{wait_group, add, wait_timeout}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 5}
            wait_timeout {wg = wg, ms = 10}
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test wait_timeout after completion`() {
        val result = eval("""
            use std::effects::concurrent::waitgroup::{wait_group, add, done, wait_timeout}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 1}
            let _ = done {wg = wg}
            wait_timeout {wg = wg, ms = 10}
        """)
        assertEquals(true, result)
    }

    // === Edge Cases ===

    @Test
    fun `test add with delta greater than 1`() {
        val result = eval("""
            use std::effects::concurrent::waitgroup::{wait_group, add, get_count_wg}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 10}
            get_count_wg {wg = wg}
        """)
        assertEquals(10L, result)
    }

    @Test
    fun `test multiple wait_groups independent`() {
        val result = eval("""
            use std::effects::concurrent::waitgroup::{wait_group, add, get_count_wg}

            let wg1 = wait_group {}
            let wg2 = wait_group {}
            let _ = add {wg = wg1, delta = 3}
            let _ = add {wg = wg2, delta = 5}
            get_count_wg {wg = wg1} + get_count_wg {wg = wg2}
        """)
        assertEquals(8L, result)
    }

    @Test
    fun `test reuse after reaching zero`() {
        val result = eval("""
            use std::effects::concurrent::waitgroup::{wait_group, add, done, wait, get_count_wg}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 1}
            let _ = done {wg = wg}
            let _ = wait {wg = wg}
            let _ = add {wg = wg, delta = 2}
            get_count_wg {wg = wg}
        """)
        assertEquals(2L, result)
    }
}
