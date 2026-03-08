package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/effects/log module.
 * Uses QuietConsole handler for testing without actual console output.
 * Migrated from V1 to V2 with unboxed values.
 */
class LogEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === QuietConsole Handler Tests ===

    @Test
    fun `test log info with QuietConsole`() {
        val result = eval("""
            use std::effects::log::{QuietConsole, info}

            perform QuietConsole in do
                info { msg = "Test info message" }
                42
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test log warn with QuietConsole`() {
        val result = eval("""
            use std::effects::log::{QuietConsole, warn}

            perform QuietConsole in do
                warn { msg = "Test warning" }
                "done"
        """)
        assertEquals("done", result)
    }

    @Test
    fun `test log error with QuietConsole`() {
        val result = eval("""
            use std::effects::log::{QuietConsole, error}

            perform QuietConsole in do
                error { msg = "Test error" }
                True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test log debug with QuietConsole`() {
        val result = eval("""
            use std::effects::log::{QuietConsole, debug}

            perform QuietConsole in do
                debug { msg = "Debug info" }
                100
        """)
        assertEquals(100L, result)
    }

    @Test
    fun `test log trace with QuietConsole`() {
        val result = eval("""
            use std::effects::log::{QuietConsole, trace}

            perform QuietConsole in do
                trace { msg = "Trace message" }
                "traced"
        """)
        assertEquals("traced", result)
    }

    @Test
    fun `test log print with QuietConsole`() {
        val result = eval("""
            use std::effects::log::{QuietConsole, print}

            perform QuietConsole in do
                print { msg = "Print without newline" }
                1
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test log println with QuietConsole`() {
        val result = eval("""
            use std::effects::log::{QuietConsole, println}

            perform QuietConsole in do
                println { msg = "Print with newline" }
                2
        """)
        assertEquals(2L, result)
    }

    // === Multiple Log Calls ===

    @Test
    fun `test multiple log calls in sequence`() {
        val result = eval("""
            use std::effects::log::{QuietConsole, info, debug, warn, error}

            perform QuietConsole in do
                info { msg = "Step 1" }
                debug { msg = "Step 2" }
                warn { msg = "Step 3" }
                error { msg = "Step 4" }
                "completed"
        """)
        assertEquals("completed", result)
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
        assertEquals(99L, result)
    }

    @Test
    fun `test convenience println function`() {
        val result = eval("""
            use std::effects::log::{QuietConsole, println}

            perform QuietConsole in do
                println { msg = "Hello from convenience" }
                "printed"
        """)
        assertEquals("printed", result)
    }

    // === Log with Computation ===

    @Test
    fun `test logging with computation`() {
        val result = eval("""
            use std::effects::log::{QuietConsole, info, debug}

            let compute = { x, y } -> perform QuietConsole in do
                info { msg = "Computing..." }
                let result = x + y
                debug { msg = "Done" }
                result

            compute { x = 10, y = 20 }
        """)
        assertEquals(30L, result)
    }

    @Test
    fun `test logging in conditional`() {
        val result = eval("""
            use std::effects::log::{QuietConsole, info, warn}

            let check = { value } -> perform QuietConsole in do
                if value > 0 then do
                    info { msg = "Positive" }
                    "positive"
                else do
                    warn { msg = "Non-positive" }
                    "non-positive"

            check { value = 5 }
        """)
        assertEquals("positive", result)
    }

    // === Console Handler (actual output) ===

    @Test
    fun `test Console handler executes without error`() {
        // This test verifies Console works but produces actual output
        val result = eval("""
            use std::effects::log::{Console, debug}

            perform Console in do
                debug { msg = "Test from LogEffectTest" }
                "success"
        """)
        assertEquals("success", result)
    }

    // === Direct Log.log usage ===

    @Test
    fun `test direct Log log with level`() {
        val result = eval("""
            use std::effects::log::{Log, QuietConsole}

            perform QuietConsole in do
                Log.log { level = Info, msg = "Info via Log.log" }
                Log.log { level = Debug, msg = "Debug via Log.log" }
                "direct"
        """)
        assertEquals("direct", result)
    }

    // === Custom log level ===

    @Test
    fun `test custom log level`() {
        val result = eval("""
            use std::effects::log::{Log, QuietConsole}

            perform QuietConsole in do
                Log.log { level = MyCustomLevel, msg = "Custom level message" }
                "custom"
        """)
        assertEquals("custom", result)
    }
}
