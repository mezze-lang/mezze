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

    // ==========================================================================
    // Output format tests - verify actual console output
    // ==========================================================================

    @Test
    fun `test info output format with newline`() {
        val runResult = MezzeTestHelper.runWithOutput("""
            use std::effects::log::{Console, info}

            let main = {} -> perform Console in do
                info { msg = "Hello World" }
                {}
            main {}
        """)
        assertEquals("[INFO] Hello World\n", runResult.output())
    }

    @Test
    fun `test error output format with newline`() {
        val runResult = MezzeTestHelper.runWithOutput("""
            use std::effects::log::{Console, error}

            let main = {} -> perform Console in do
                error { msg = "Something went wrong" }
                {}
            main {}
        """)
        assertEquals("[ERROR] Something went wrong\n", runResult.output())
    }

    @Test
    fun `test warn output format with newline`() {
        val runResult = MezzeTestHelper.runWithOutput("""
            use std::effects::log::{Console, warn}

            let main = {} -> perform Console in do
                warn { msg = "Be careful" }
                {}
            main {}
        """)
        assertEquals("[WARN] Be careful\n", runResult.output())
    }

    @Test
    fun `test debug output format with newline`() {
        val runResult = MezzeTestHelper.runWithOutput("""
            use std::effects::log::{Console, debug}

            let main = {} -> perform Console in do
                debug { msg = "Debug info" }
                {}
            main {}
        """)
        assertEquals("[DEBUG] Debug info\n", runResult.output())
    }

    @Test
    fun `test trace output format with newline`() {
        val runResult = MezzeTestHelper.runWithOutput("""
            use std::effects::log::{Console, trace}

            let main = {} -> perform Console in do
                trace { msg = "Trace data" }
                {}
            main {}
        """)
        assertEquals("[TRACE] Trace data\n", runResult.output())
    }

    @Test
    fun `test print has no prefix and no newline`() {
        val runResult = MezzeTestHelper.runWithOutput("""
            use std::effects::log::{Console, print}

            let main = {} -> perform Console in do
                print { msg = "no prefix no newline" }
                {}
            main {}
        """)
        assertEquals("no prefix no newline", runResult.output())
    }

    @Test
    fun `test println has no prefix but has newline`() {
        val runResult = MezzeTestHelper.runWithOutput("""
            use std::effects::log::{Console, println}

            let main = {} -> perform Console in do
                println { msg = "no prefix with newline" }
                {}
            main {}
        """)
        assertEquals("no prefix with newline\n", runResult.output())
    }

    @Test
    fun `test multiple log calls produce multiple lines`() {
        val runResult = MezzeTestHelper.runWithOutput("""
            use std::effects::log::{Console, info, debug, warn}

            let main = {} -> perform Console in do
                info { msg = "Line 1" }
                debug { msg = "Line 2" }
                warn { msg = "Line 3" }
                {}
            main {}
        """)
        assertEquals("[INFO] Line 1\n[DEBUG] Line 2\n[WARN] Line 3\n", runResult.output())
    }

    @Test
    fun `test print and println interleaved`() {
        val runResult = MezzeTestHelper.runWithOutput("""
            use std::effects::log::{Console, print, println}

            let main = {} -> perform Console in do
                print { msg = "Hello " }
                print { msg = "World" }
                println { msg = "!" }
                println { msg = "Done" }
                {}
            main {}
        """)
        assertEquals("Hello World!\nDone\n", runResult.output())
    }

    // ==========================================================================
    // Custom handler tests
    // ==========================================================================

    @Test
    fun `test ConsoleWith custom formatter`() {
        val runResult = MezzeTestHelper.runWithOutput("""
            use std::effects::log::{Log, ConsoleWith}

            let my_formatter = {level, msg} -> match level is
                Info -> ">> $(msg) <<"
                _ -> msg

            let main = {} -> perform ConsoleWith {formatter = my_formatter} in do
                Log.log { level = Info, msg = "Custom formatted" }
                {}
            main {}
        """)
        assertEquals(">> Custom formatted <<", runResult.output())
    }

    @Test
    fun `test nested perform with different log handlers`() {
        val runResult = MezzeTestHelper.runWithOutput("""
            use std::effects::log::{Log, Console, QuietConsole, info}

            let main = {} -> do
                perform Console in info { msg = "Outer" }
                perform QuietConsole in info { msg = "This is silent" }
                perform Console in info { msg = "Back to console" }
                {}
            main {}
        """)
        assertEquals("[INFO] Outer\n[INFO] Back to console\n", runResult.output())
    }
}
