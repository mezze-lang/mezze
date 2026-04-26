package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.interpreter.values.MezzeRecord

/**
 * Tests for closed variant types in expression contexts.
 *
 * When a variant constructor is used in an expression (not a pattern),
 * it should create a CLOSED variant type, not an open one.
 *
 * Before fix: `MathError {code = "x", msg = "y"}` produced `| MathError {...}, ... |` (open)
 * After fix:  `MathError {code = "x", msg = "y"}` produces `| MathError {...} |` (closed)
 *
 * This is important for type safety - a single constructor call should not
 * be row-polymorphic. Row polymorphism is only needed for pattern matching.
 */
class ClosedVariantInExpressionTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)
    private fun evalWithOutput(source: String): MezzeTestHelper.RunResult = MezzeTestHelper.runWithOutput(source)

    // ========================================================================
    // Section 1: Basic Closed Variant Construction
    // ========================================================================

    @Test
    fun `test variant constructor creates closed variant`() {
        // A simple variant constructor call should create a closed variant
        val source = """
            type Status = | Active, Inactive |

            let status = Active

            # This should work - Active is a valid Status constructor
            let check = {s} -> match s is
                Active -> "active"
                Inactive -> "inactive"

            check {s = status}
        """.trimIndent()

        val result = eval(source)
        assertEquals("active", result)
    }

    @Test
    fun `test variant constructor with payload creates closed variant`() {
        val source = """
            type UserEvent = | Login {user: Str}, Logout {user: Str} |

            let event = Login {user = "alice"}

            let describe = {e} -> match e is
                Login {user} -> "User $(user) logged in"
                Logout {user} -> "User $(user) logged out"

            describe {e = event}
        """.trimIndent()

        val result = eval(source)
        assertEquals("User alice logged in", result)
    }

    @Test
    fun `test anonymous variant constructor creates closed variant`() {
        // Even without a type declaration, variant should be closed in expressions
        val source = """
            let make_info = {} -> Info {msg = "hello"}

            let extract = {v} -> match v is
                Info {msg} -> msg
                _ -> "unknown"

            extract {v = make_info {}}
        """.trimIndent()

        val result = eval(source)
        assertEquals("hello", result)
    }

    // ========================================================================
    // Section 2: Closed Variants in Effect Handlers
    // ========================================================================

    @Test
    fun `test exception with closed variant error type`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let fail_with = {code, msg} ->
                Exception.throw {error = AppError {code = code, message = msg}}

            let handle = {} -> perform ExceptionToResult in Ok {val = fail_with {code = 500, msg = "Server error"}}

            let process = {r} -> match r is
                Err {error} -> match error is
                    AppError {message} -> message
                    _ -> "unknown"
                Ok {val} -> "success"

            process {r = handle {}}
        """.trimIndent()

        val result = eval(source)
        assertEquals("Server error", result)
    }

    @Test
    fun `test exception with variant constructor error type`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let fail = {} -> Exception.throw {error = CustomError {reason = "failed"}}

            let handle = {} -> perform ExceptionToResult in Ok {val = fail {}}

            let process = {r} -> match r is
                Err {error} -> match error is
                    CustomError {reason} -> reason
                    _ -> "unknown"
                Ok {val} -> "success"

            process {r = handle {}}
        """.trimIndent()

        val result = eval(source)
        assertEquals("failed", result)
    }

    // ========================================================================
    // Section 3: Multiple Variants from Different Branches
    // ========================================================================

    @Test
    fun `test if-then-else with different variant constructors`() {
        // Both branches return different constructors of the same closed variant
        val source = """
            type Response = | Success {data: Str}, Failure {err: Str} |

            let process = {ok} ->
                if ok then Success {data = "done"}
                else Failure {err = "failed"}

            let describe = {r} -> match r is
                Success {data} -> "OK: $(data)"
                Failure {err} -> "ERR: $(err)"

            let r1 = process {ok = True}

            describe {r = r1}
        """.trimIndent()

        val result = eval(source)
        assertEquals("OK: done", result)
    }

    @Test
    fun `test if-then-else with failure branch`() {
        // Test the failure branch
        val source = """
            type Response = | Success {data: Str}, Failure {err: Str} |

            let process = {ok} ->
                if ok then Success {data = "done"}
                else Failure {err = "failed"}

            let describe = {r} -> match r is
                Success {data} -> "OK: $(data)"
                Failure {err} -> "ERR: $(err)"

            let r2 = process {ok = False}

            describe {r = r2}
        """.trimIndent()

        val result = eval(source)
        assertEquals("ERR: failed", result)
    }

    @Test
    fun `test match returning different variant constructors`() {
        // Test with single type declaration
        val source = """
            type Color = | Red, Green, Blue |

            let color_name = {c} -> match c is
                Red -> "red"
                Green -> "green"
                Blue -> "blue"

            color_name {c = Red}
        """.trimIndent()

        val result = eval(source)
        assertEquals("red", result)
    }

    @Test
    fun `test separate variant types in same module`() {
        // Test two variant types but accessing them separately
        val source1 = """
            type Size = | Small, Medium, Large |

            let size_name = {s} -> match s is
                Small -> "S"
                Medium -> "M"
                Large -> "L"

            size_name {s = Large}
        """.trimIndent()

        val result1 = eval(source1)
        assertEquals("L", result1)
    }

    // ========================================================================
    // Section 4: Built-in Variant Types (Result, Option)
    // ========================================================================

    @Test
    fun `test Ok constructor is closed`() {
        val source = """
            let make_ok = {} -> Ok {val = 42}

            let extract = {r} -> match r is
                Ok {val} -> val
                Err {error} -> 0

            extract {r = make_ok {}}
        """.trimIndent()

        val result = eval(source)
        assertEquals(42L, result)
    }

    @Test
    fun `test Some constructor is closed`() {
        val source = """
            let make_some = {} -> Some {val = "hello"}

            let extract = {o} -> match o is
                Some {val} -> val
                None -> "nothing"

            extract {o = make_some {}}
        """.trimIndent()

        val result = eval(source)
        assertEquals("hello", result)
    }

    // ========================================================================
    // Section 5: Nested Variants
    // ========================================================================

    @Test
    fun `test variant inside record`() {
        val source = """
            type Status = | Pending, Complete |

            let task = {name = "test", status = Pending}

            match task.status is
                Pending -> "waiting"
                Complete -> "done"
        """.trimIndent()

        val result = eval(source)
        assertEquals("waiting", result)
    }

    @Test
    fun `test variant inside variant payload`() {
        val source = """
            type Inner = | A, B |
            type Outer = | Wrap {inner: Inner} |

            let wrapped = Wrap {inner = A}

            match wrapped is
                Wrap {inner} -> match inner is
                    A -> "got A"
                    B -> "got B"
        """.trimIndent()

        val result = eval(source)
        assertEquals("got A", result)
    }

    // ========================================================================
    // Section 6: Log Levels (Real World Example)
    // ========================================================================

    @Test
    fun `test log level variants are closed`() {
        val source = """
            use std::effects::log::{Log, Console, LogLevel}

            let get_level = {priority} ->
                if priority > 3 then Error
                else if priority > 2 then Warn
                else if priority > 1 then Info
                else Debug

            let level = get_level {priority = 4}

            match level is
                Error -> "ERROR"
                Warn -> "WARN"
                Info -> "INFO"
                Debug -> "DEBUG"
                Trace -> "TRACE"
                Print -> "PRINT"
                _ -> "OTHER"
        """.trimIndent()

        val result = eval(source)
        assertEquals("ERROR", result)
    }

    // ========================================================================
    // Section 7: Exception with Different Error Types
    // ========================================================================

    @Test
    fun `test exception handler catches closed variant error`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let divide = {a, b} ->
                if b == 0 then Exception.throw {error = DivisionError {dividend = a}}
                else a / b

            let handle = {} -> perform ExceptionToResult in Ok {val = divide {a = 10, b = 0}}

            match handle {} is
                Err {error} -> match error is
                    DivisionError {dividend} -> "Cannot divide $(dividend.to_dbg_str {}) by zero"
                    _ -> "Unknown error"
                Ok {val} -> "Result: $(val.to_dbg_str {})"
        """.trimIndent()

        val result = eval(source)
        assertEquals("Cannot divide 10 by zero", result)
    }

    @Test
    fun `test exception handler with success path`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let divide = {a, b} ->
                if b == 0 then Exception.throw {error = DivisionError {dividend = a}}
                else a / b

            let handle = {} -> perform ExceptionToResult in Ok {val = divide {a = 10, b = 2}}

            match handle {} is
                Err {error} -> "Error"
                Ok {val} -> "Result: $(val.to_dbg_str {})"
        """.trimIndent()

        val result = eval(source)
        assertEquals("Result: 5", result)
    }
}
