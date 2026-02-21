package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/concurrent/latch module.
 * Tests countdown latches for thread synchronization.
 */
class LatchModuleTest {

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
    fun `test latch creates with count`() {
        val result = eval("""
            use std::concurrent::latch::{latch, get_count}

            let l = latch {count = 5}
            get_count {latch = l}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(5L, (result as MezzeInt).value)
    }

    @Test
    fun `test count_down decreases count`() {
        val result = eval("""
            use std::concurrent::latch::{latch, count_down, get_count}

            let l = latch {count = 3}
            let _ = count_down {latch = l}
            get_count {latch = l}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(2L, (result as MezzeInt).value)
    }

    @Test
    fun `test multiple count_downs`() {
        val result = eval("""
            use std::concurrent::latch::{latch, count_down, get_count}

            let l = latch {count = 5}
            let _ = count_down {latch = l}
            let _ = count_down {latch = l}
            let _ = count_down {latch = l}
            get_count {latch = l}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(2L, (result as MezzeInt).value)
    }

    @Test
    fun `test count_down to zero`() {
        val result = eval("""
            use std::concurrent::latch::{latch, count_down, get_count}

            let l = latch {count = 2}
            let _ = count_down {latch = l}
            let _ = count_down {latch = l}
            get_count {latch = l}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(0L, (result as MezzeInt).value)
    }

    @Test
    fun `test count_down below zero has no effect`() {
        val result = eval("""
            use std::concurrent::latch::{latch, count_down, get_count}

            let l = latch {count = 1}
            let _ = count_down {latch = l}
            let _ = count_down {latch = l}
            let _ = count_down {latch = l}
            get_count {latch = l}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(0L, (result as MezzeInt).value)
    }

    // === await_latch ===

    @Test
    fun `test await_latch returns immediately when zero`() {
        val result = eval("""
            use std::concurrent::latch::{latch, count_down, await_latch}

            let l = latch {count = 1}
            let _ = count_down {latch = l}
            let _ = await_latch {latch = l}
            42
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `test await_latch on zero count latch`() {
        val result = eval("""
            use std::concurrent::latch::{latch, await_latch}

            let l = latch {count = 0}
            let _ = await_latch {latch = l}
            "done"
        """)
        assertTrue(result is MezzeString)
        assertEquals("done", (result as MezzeString).value)
    }

    // === await_latch_timeout ===

    @Test
    fun `test await_latch_timeout returns true when zero`() {
        val result = eval("""
            use std::concurrent::latch::{latch, count_down, await_latch_timeout}

            let l = latch {count = 1}
            let _ = count_down {latch = l}
            await_latch_timeout {latch = l, ms = 1000}
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test await_latch_timeout returns false on timeout`() {
        val result = eval("""
            use std::concurrent::latch::{latch, await_latch_timeout}

            let l = latch {count = 5}
            await_latch_timeout {latch = l, ms = 10}
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test await_latch_timeout on zero count returns true`() {
        val result = eval("""
            use std::concurrent::latch::{latch, await_latch_timeout}

            let l = latch {count = 0}
            await_latch_timeout {latch = l, ms = 10}
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    // === Edge Cases ===

    @Test
    fun `test latch with count one`() {
        val result = eval("""
            use std::concurrent::latch::{latch, count_down, get_count, await_latch}

            let l = latch {count = 1}
            let before = get_count {latch = l}
            let _ = count_down {latch = l}
            let after = get_count {latch = l}
            let _ = await_latch {latch = l}
            before + after
        """)
        assertTrue(result is MezzeInt)
        assertEquals(1L, (result as MezzeInt).value)
    }

    @Test
    fun `test multiple latches independent`() {
        val result = eval("""
            use std::concurrent::latch::{latch, count_down, get_count}

            let l1 = latch {count = 5}
            let l2 = latch {count = 10}
            let _ = count_down {latch = l1}
            let _ = count_down {latch = l1}
            get_count {latch = l1} + get_count {latch = l2}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(13L, (result as MezzeInt).value)
    }
}
