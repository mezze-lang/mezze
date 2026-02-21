package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/effects/log module.
 * Uses QuietConsole handler for testing without actual console output.
 */
class LogEffectTest {

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

    // === QuietConsole Handler Tests ===

    @Test
    fun `test log info with QuietConsole`() {
        val result = eval("""
            use std::effects::log::{Log, QuietConsole}

            perform QuietConsole in do
                Log.info { msg = "Test info message" }
                42
        """)
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test log warn with QuietConsole`() {
        val result = eval("""
            use std::effects::log::{Log, QuietConsole}

            perform QuietConsole in do
                Log.warn { msg = "Test warning" }
                "done"
        """)
        assertEquals(MezzeString("done"), result)
    }

    @Test
    fun `test log error with QuietConsole`() {
        val result = eval("""
            use std::effects::log::{Log, QuietConsole}

            perform QuietConsole in do
                Log.error { msg = "Test error" }
                True
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test log debug with QuietConsole`() {
        val result = eval("""
            use std::effects::log::{Log, QuietConsole}

            perform QuietConsole in do
                Log.debug { msg = "Debug info" }
                100
        """)
        assertEquals(MezzeInt(100), result)
    }

    @Test
    fun `test log trace with QuietConsole`() {
        val result = eval("""
            use std::effects::log::{Log, QuietConsole}

            perform QuietConsole in do
                Log.trace { msg = "Trace message" }
                "traced"
        """)
        assertEquals(MezzeString("traced"), result)
    }

    @Test
    fun `test log print with QuietConsole`() {
        val result = eval("""
            use std::effects::log::{Log, QuietConsole}

            perform QuietConsole in do
                Log.print { msg = "Print without newline" }
                1
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test log println with QuietConsole`() {
        val result = eval("""
            use std::effects::log::{Log, QuietConsole}

            perform QuietConsole in do
                Log.println { msg = "Print with newline" }
                2
        """)
        assertEquals(MezzeInt(2), result)
    }

    // === Multiple Log Calls ===

    @Test
    fun `test multiple log calls in sequence`() {
        val result = eval("""
            use std::effects::log::{Log, QuietConsole}

            perform QuietConsole in do
                Log.info { msg = "Step 1" }
                Log.debug { msg = "Step 2" }
                Log.warn { msg = "Step 3" }
                Log.error { msg = "Step 4" }
                "completed"
        """)
        assertEquals(MezzeString("completed"), result)
    }

    // === Convenience Functions ===

    @Test
    fun `test convenience info function`() {
        val result = eval("""
            use std::effects::log::{QuietConsole, info}

            perform QuietConsole in do
                info { msg = "Using convenience function" }
                99
        """)
        assertEquals(MezzeInt(99), result)
    }

    @Test
    fun `test convenience println function`() {
        val result = eval("""
            use std::effects::log::{QuietConsole, println}

            perform QuietConsole in do
                println { msg = "Hello from convenience" }
                "printed"
        """)
        assertEquals(MezzeString("printed"), result)
    }

    // === Log with Computation ===

    @Test
    fun `test logging with computation`() {
        val result = eval("""
            use std::effects::log::{Log, QuietConsole}

            let compute = { x, y } -> perform QuietConsole in do
                Log.info { msg = "Computing..." }
                let result = x + y
                Log.debug { msg = "Done" }
                result

            compute { x = 10, y = 20 }
        """)
        assertEquals(MezzeInt(30), result)
    }

    @Test
    fun `test logging in conditional`() {
        val result = eval("""
            use std::effects::log::{Log, QuietConsole}

            let check = { value } -> perform QuietConsole in do
                if value > 0 then do
                    Log.info { msg = "Positive" }
                    "positive"
                else do
                    Log.warn { msg = "Non-positive" }
                    "non-positive"

            check { value = 5 }
        """)
        assertEquals(MezzeString("positive"), result)
    }

    // === Console Handler (actual output) ===

    @Test
    fun `test Console handler executes without error`() {
        // This test verifies Console works but produces actual output
        val result = eval("""
            use std::effects::log::{Log, Console}

            perform Console in do
                Log.debug { msg = "Test from LogEffectTest" }
                "success"
        """)
        assertEquals(MezzeString("success"), result)
    }
}
