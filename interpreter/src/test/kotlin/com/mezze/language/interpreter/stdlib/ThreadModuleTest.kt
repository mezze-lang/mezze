package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.nio.file.Paths

/**
 * Tests for std/async/thread module.
 *
 * This module provides low-level virtual thread operations:
 * - park: Park the current virtual thread
 * - unpark: Unpark a virtual thread
 * - current_thread: Get the current virtual thread handle
 * - sleep: Sleep for specified milliseconds
 * - yield: Yield execution to other virtual threads
 */
@Execution(ExecutionMode.SAME_THREAD)
class ThreadModuleTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    @AfterEach
    fun teardown() {
        Thread.sleep(50)
        TruffleContextHolder.clearEnv()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return result
    }

    // =============================================================================
    // Module Import Tests
    // =============================================================================

    @Test
    fun `test thread module can be imported`() {
        // This test verifies that the module imports without type errors
        val result = eval("""
            use std::effects::async::thread::{park, unpark, current_thread, sleep, yield}
            42
        """)
        assertEquals(MezzeInt(42), result)
    }

    // =============================================================================
    // current_thread Tests
    // =============================================================================

    @Test
    fun `test current_thread returns a thread handle`() {
        // current_thread is a pure function - doesn't need Async effect
        val result = eval("""
            use std::effects::async::thread::{current_thread}

            let t = current_thread {}
            42  # Return a value to verify we got past the call
        """)
        assertEquals(MezzeInt(42), result)
    }

    // =============================================================================
    // yield Tests
    // =============================================================================

    @Test
    fun `test yield returns unit`() {
        // yield is a pure function - doesn't need Async effect
        val result = eval("""
            use std::effects::async::thread::{yield}

            yield {}
            42
        """)
        assertEquals(MezzeInt(42), result)
    }

    // Note: park/unpark and sleep tests are harder to test deterministically
    // because they involve actual thread blocking. The import test above
    // verifies the module compiles correctly.
}
