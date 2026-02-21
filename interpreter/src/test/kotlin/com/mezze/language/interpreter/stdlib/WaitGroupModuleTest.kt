package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/concurrent/waitgroup module.
 * Tests wait groups for dynamic task synchronization.
 */
class WaitGroupModuleTest {

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
    fun `test wait_group creates with zero count`() {
        val result = eval("""
            use std::concurrent::waitgroup::{wait_group, get_count_wg}

            let wg = wait_group {}
            get_count_wg {wg = wg}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(0L, (result as MezzeInt).value)
    }

    @Test
    fun `test add increases count`() {
        val result = eval("""
            use std::concurrent::waitgroup::{wait_group, add, get_count_wg}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 3}
            get_count_wg {wg = wg}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(3L, (result as MezzeInt).value)
    }

    @Test
    fun `test done decreases count`() {
        val result = eval("""
            use std::concurrent::waitgroup::{wait_group, add, done, get_count_wg}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 3}
            let _ = done {wg = wg}
            get_count_wg {wg = wg}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(2L, (result as MezzeInt).value)
    }

    @Test
    fun `test multiple adds and dones`() {
        val result = eval("""
            use std::concurrent::waitgroup::{wait_group, add, done, get_count_wg}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 5}
            let _ = done {wg = wg}
            let _ = done {wg = wg}
            let _ = add {wg = wg, delta = 2}
            let _ = done {wg = wg}
            get_count_wg {wg = wg}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(4L, (result as MezzeInt).value)
    }

    // === wait ===

    @Test
    fun `test wait returns immediately when zero`() {
        val result = eval("""
            use std::concurrent::waitgroup::{wait_group, wait}

            let wg = wait_group {}
            let _ = wait {wg = wg}
            42
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `test wait after add and done`() {
        val result = eval("""
            use std::concurrent::waitgroup::{wait_group, add, done, wait}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 2}
            let _ = done {wg = wg}
            let _ = done {wg = wg}
            let _ = wait {wg = wg}
            "done"
        """)
        assertTrue(result is MezzeString)
        assertEquals("done", (result as MezzeString).value)
    }

    // === wait_timeout ===

    @Test
    fun `test wait_timeout returns true when zero`() {
        val result = eval("""
            use std::concurrent::waitgroup::{wait_group, wait_timeout}

            let wg = wait_group {}
            wait_timeout {wg = wg, ms = 1000}
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test wait_timeout returns false on timeout`() {
        val result = eval("""
            use std::concurrent::waitgroup::{wait_group, add, wait_timeout}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 5}
            wait_timeout {wg = wg, ms = 10}
        """)
        assertTrue(result is MezzeBool)
        assertFalse((result as MezzeBool).value)
    }

    @Test
    fun `test wait_timeout after completion`() {
        val result = eval("""
            use std::concurrent::waitgroup::{wait_group, add, done, wait_timeout}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 1}
            let _ = done {wg = wg}
            wait_timeout {wg = wg, ms = 10}
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    // === Edge Cases ===

    @Test
    fun `test add with delta greater than 1`() {
        val result = eval("""
            use std::concurrent::waitgroup::{wait_group, add, get_count_wg}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 10}
            get_count_wg {wg = wg}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(10L, (result as MezzeInt).value)
    }

    @Test
    fun `test multiple wait_groups independent`() {
        val result = eval("""
            use std::concurrent::waitgroup::{wait_group, add, get_count_wg}

            let wg1 = wait_group {}
            let wg2 = wait_group {}
            let _ = add {wg = wg1, delta = 3}
            let _ = add {wg = wg2, delta = 5}
            get_count_wg {wg = wg1} + get_count_wg {wg = wg2}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(8L, (result as MezzeInt).value)
    }

    @Test
    fun `test reuse after reaching zero`() {
        val result = eval("""
            use std::concurrent::waitgroup::{wait_group, add, done, wait, get_count_wg}

            let wg = wait_group {}
            let _ = add {wg = wg, delta = 1}
            let _ = done {wg = wg}
            let _ = wait {wg = wg}
            let _ = add {wg = wg, delta = 2}
            get_count_wg {wg = wg}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(2L, (result as MezzeInt).value)
    }
}
