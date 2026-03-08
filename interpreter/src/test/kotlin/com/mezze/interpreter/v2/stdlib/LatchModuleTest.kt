package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/concurrent/latch module.
 * Tests countdown latches for thread synchronization.
 * Migrated from V1 to V2 with unboxed values.
 */
class LatchModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === Basic Operations ===

    @Test
    fun `test latch creates with count`() {
        val result = eval("""
            use std::effects::concurrent::latch::{latch, get_count}

            let l = latch {count = 5}
            get_count {latch = l}
        """)
        assertEquals(5L, result)
    }

    @Test
    fun `test count_down decreases count`() {
        val result = eval("""
            use std::effects::concurrent::latch::{latch, count_down, get_count}

            let l = latch {count = 3}
            let _ = count_down {latch = l}
            get_count {latch = l}
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test multiple count_downs`() {
        val result = eval("""
            use std::effects::concurrent::latch::{latch, count_down, get_count}

            let l = latch {count = 5}
            let _ = count_down {latch = l}
            let _ = count_down {latch = l}
            let _ = count_down {latch = l}
            get_count {latch = l}
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test count_down to zero`() {
        val result = eval("""
            use std::effects::concurrent::latch::{latch, count_down, get_count}

            let l = latch {count = 2}
            let _ = count_down {latch = l}
            let _ = count_down {latch = l}
            get_count {latch = l}
        """)
        assertEquals(0L, result)
    }

    @Test
    fun `test count_down below zero has no effect`() {
        val result = eval("""
            use std::effects::concurrent::latch::{latch, count_down, get_count}

            let l = latch {count = 1}
            let _ = count_down {latch = l}
            let _ = count_down {latch = l}
            let _ = count_down {latch = l}
            get_count {latch = l}
        """)
        assertEquals(0L, result)
    }

    // === await_latch ===

    @Test
    fun `test await_latch returns immediately when zero`() {
        val result = eval("""
            use std::effects::concurrent::latch::{latch, count_down, await_latch}

            let l = latch {count = 1}
            let _ = count_down {latch = l}
            let _ = await_latch {latch = l}
            42
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test await_latch on zero count latch`() {
        val result = eval("""
            use std::effects::concurrent::latch::{latch, await_latch}

            let l = latch {count = 0}
            let _ = await_latch {latch = l}
            "done"
        """)
        assertEquals("done", result)
    }

    // === await_latch_timeout ===

    @Test
    fun `test await_latch_timeout returns true when zero`() {
        val result = eval("""
            use std::effects::concurrent::latch::{latch, count_down, await_latch_timeout}

            let l = latch {count = 1}
            let _ = count_down {latch = l}
            await_latch_timeout {latch = l, ms = 1000}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test await_latch_timeout returns false on timeout`() {
        val result = eval("""
            use std::effects::concurrent::latch::{latch, await_latch_timeout}

            let l = latch {count = 5}
            await_latch_timeout {latch = l, ms = 10}
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test await_latch_timeout on zero count returns true`() {
        val result = eval("""
            use std::effects::concurrent::latch::{latch, await_latch_timeout}

            let l = latch {count = 0}
            await_latch_timeout {latch = l, ms = 10}
        """)
        assertEquals(true, result)
    }

    // === Edge Cases ===

    @Test
    fun `test latch with count one`() {
        val result = eval("""
            use std::effects::concurrent::latch::{latch, count_down, get_count, await_latch}

            let l = latch {count = 1}
            let before = get_count {latch = l}
            let _ = count_down {latch = l}
            let after = get_count {latch = l}
            let _ = await_latch {latch = l}
            before + after
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test multiple latches independent`() {
        val result = eval("""
            use std::effects::concurrent::latch::{latch, count_down, get_count}

            let l1 = latch {count = 5}
            let l2 = latch {count = 10}
            let _ = count_down {latch = l1}
            let _ = count_down {latch = l1}
            get_count {latch = l1} + get_count {latch = l2}
        """)
        assertEquals(13L, result)
    }
}
