package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import com.mezze.interpreter.v2.values.MezzeVariant
import com.mezze.interpreter.v2.values.MezzeRecord

/**
 * Integration tests for the Exception effect from stdlib
 *
 * Tests that the exception handling system works end-to-end with:
 * - ExceptionToResult handler (converts exceptions to Result type)
 * - ExceptionToOption handler (converts exceptions to Option type)
 * - IgnoreException handler (ignores all errors)
 * - Custom handlers with specific error types
 *
 * All handlers use scoped continuations internally - no need to pass
 * continuations as parameters.
 *
 * Migrated from V1 to V2 with unboxed values.
 */
class ExceptionEffectTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================================================
    // Test 1: Basic Exception with ExceptionToResult Handler
    // ========================================================================

    @Test
    fun `test basic exception with ExceptionToResult handler`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let divide = {a, b} ->
                if b == 0 then
                    Exception.throw {error = "Division by zero"}
                else
                    Ok {val = a / b}

            perform ExceptionToResult in divide {a = 10, b = 2}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
    }

    // ========================================================================
    // Test 2: Exception Thrown Returns Err
    // ========================================================================

    @Test
    fun `test exception thrown returns Err`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let divide = {a, b} ->
                if b == 0 then
                    Exception.throw {error = "Division by zero"}
                else
                    Ok {val = a / b}

            perform ExceptionToResult in divide {a = 10, b = 0}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val fields = result.payloadValue as MezzeRecord
        val error = fields.getField("error") as String
        assertEquals("Division by zero", error)
    }

    // ========================================================================
    // Test 3: ExceptionToOption Handler Returns None on Exception
    // ========================================================================

    @Test
    fun `test ExceptionToOption handler returns None on exception`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToOption}

            let divide = {a, b} ->
                if b == 0 then
                    Exception.throw {error = "Division by zero"}
                else
                    Some {val = a / b}

            perform ExceptionToOption in divide {a = 10, b = 0}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("None", (result as MezzeVariant).constructorName)
    }

    // ========================================================================
    // Test 4: IgnoreException Handler Returns Unit on Exception
    // ========================================================================

    @Test
    fun `test IgnoreException handler returns unit on exception`() {
        val source = """
            use std::effects::exception::{Exception, IgnoreException}

            let may_fail = {x} ->
                if x == 0 then
                    Exception.throw {error = "Failed!"}
                else
                    {}

            perform IgnoreException in may_fail {x = 0}
        """.trimIndent()

        val result = eval(source)
        assertTrue(MezzeTestHelper.isUnit(result))
    }

    // ========================================================================
    // Test 5: Helper Function - throw
    // ========================================================================

    @Test
    fun `test throw helper function`() {
        val source = """
            use std::effects::exception::{throw, ExceptionToResult}

            let check = {x} ->
                if x < 0 then
                    throw {error = "negative"}
                else
                    Ok {val = x}

            perform ExceptionToResult in check {x = -5}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val fields = result.payloadValue as MezzeRecord
        val error = fields.getField("error") as String
        assertEquals("negative", error)
    }

    // ========================================================================
    // Test 6: Exception in If Branch
    // ========================================================================

    @Test
    fun `test exception in if branch`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let check_value = {x} ->
                if x > 10 then
                    Exception.throw {error = "Too large"}
                else
                    Ok {val = x}

            perform ExceptionToResult in check_value {x = 15}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    // ========================================================================
    // Test 7: Multiple Exceptions in Sequence
    // ========================================================================

    @Test
    fun `test multiple exceptions in sequence - first one wins`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let multi_throw = {} -> do
                Exception.throw {error = "First error"}
                Exception.throw {error = "Second error"}
                Ok {val = "unreachable"}

            perform ExceptionToResult in multi_throw {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val error = ((result as MezzeVariant).payloadValue as MezzeRecord).getField("error") as String
        assertEquals("First error", error)
    }

    // ========================================================================
    // Test 8: Helper Function - unwrap
    // ========================================================================

    @Test
    fun `test unwrap helper function`() {
        val source = """
            use std::effects::exception::{unwrap, ExceptionToResult}

            let good_result = Ok {val = 42}
            perform ExceptionToResult in Ok {val = unwrap {result = good_result}}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
    }

    // ========================================================================
    // Test 9: unwrap with Err throws
    // ========================================================================

    @Test
    fun `test unwrap with Err throws exception`() {
        val source = """
            use std::effects::exception::{unwrap, ExceptionToResult}

            let bad_result = Err {error = "Something failed"}
            let try_unwrap = {} ->
                perform ExceptionToResult in do
                    let val = unwrap {result = bad_result}
                    Ok {val = val}
            try_unwrap {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val error = ((result as MezzeVariant).payloadValue as MezzeRecord).getField("error") as String
        assertEquals("Something failed", error)
    }

    // ========================================================================
    // Test 10: Exception in nested expression - suspend_with escapes correctly
    // ========================================================================

    @Test
    fun `test exception in nested expression does not wrap result`() {
        // This tests that suspend_with properly escapes to the delimiter boundary
        // and doesn't get wrapped by intermediate expressions like Ok { val = ... }
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let handle = {} -> perform ExceptionToResult in Ok {val = safe_div {a = 4, b = 0}}
            handle {}
        """.trimIndent()

        val result = eval(source)
        // Should be Err, NOT Ok { val = Err { ... } }
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val error = ((result as MezzeVariant).payloadValue as MezzeRecord).getField("error") as String
        assertEquals("Div by zero", error)
    }

    // ========================================================================
    // Test 11: Exception escapes deeply nested expressions
    // ========================================================================

    @Test
    fun `test exception escapes deeply nested record construction`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let fail = {} -> Exception.throw {error = "deep fail"}

            let handle = {} -> perform ExceptionToResult in Ok {val = {
                outer = {
                    inner = {
                        value = fail {}
                    }
                }
            }}
            handle {}
        """.trimIndent()

        val result = eval(source)
        // Should be Err, not Ok with nested record
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    // ========================================================================
    // Test 12: Exception escapes function call chain
    // ========================================================================

    @Test
    fun `test exception escapes function call chain`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let inner = {} -> Exception.throw {error = "inner error"}
            let middle = {} -> inner {}
            let outer = {} -> middle {}

            let handle = {} -> perform ExceptionToResult in Ok {val = outer {}}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val error = ((result as MezzeVariant).payloadValue as MezzeRecord).getField("error") as String
        assertEquals("inner error", error)
    }

    // ========================================================================
    // Test 13: Success path still works - no exception thrown
    // ========================================================================

    @Test
    fun `test success path wraps result correctly`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let handle = {} -> perform ExceptionToResult in Ok {val = safe_div {a = 10, b = 2}}
            handle {}
        """.trimIndent()

        val result = eval(source)
        // Should be Ok { val = 5 }
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = (result as MezzeVariant).payloadValue as MezzeRecord
        assertEquals(5L, payload.getField("val"))
    }

    // ========================================================================
    // Test 14: Multiple perform blocks - each handles independently
    // ========================================================================

    @Test
    fun `test multiple perform blocks handle independently`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else a / b

            let compute = {} -> do
                let r1 = perform ExceptionToResult in Ok {val = safe_div {a = 4, b = 2}}
                let r2 = perform ExceptionToResult in Ok {val = safe_div {a = 4, b = 0}}
                {first = r1, second = r2}
            compute {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord

        val first = record.getField("first") as MezzeVariant
        assertEquals("Ok", first.constructorName)

        val second = record.getField("second") as MezzeVariant
        assertEquals("Err", second.constructorName)
    }

    // ========================================================================
    // Test 15: ExceptionToOption returns Some on success
    // ========================================================================

    @Test
    fun `test ExceptionToOption returns Some on success`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToOption}

            let safe_div = {a, b} ->
                if b == 0 then Exception.throw {error = "Div by zero"} else Some {val = a / b}

            let handle = {} -> perform ExceptionToOption in safe_div {a = 10, b = 2}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Some", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals(5L, payload.getField("val"))
    }

    // ========================================================================
    // Test 16: IgnoreException handler - returns unit on exception
    // ========================================================================

    @Test
    fun `test IgnoreException returns unit and continues`() {
        val source = """
            use std::effects::exception::{Exception, IgnoreException}

            let fail_early = {} -> do
                Exception.throw {error = "ignored"}
                {}

            let handle = {} -> perform IgnoreException in fail_early {}
            handle {}
        """.trimIndent()

        val result = eval(source)
        // IgnoreException returns unit when exception is thrown
        assertTrue(MezzeTestHelper.isUnit(result))
    }

    // ========================================================================
    // Test 17: Custom exception handler with logging
    // ========================================================================

    @Test
    fun `test custom exception handler with custom error type`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            type MyError = | NotFound {key: Str}, InvalidInput {msg: Str} |

            let lookup = {key} ->
                if key == "" then
                    Exception.throw {error = InvalidInput {msg = "empty key"}}
                else if key == "missing" then
                    Exception.throw {error = NotFound {key = key}}
                else
                    Ok {val = "found: $(key)"}

            let handle = {} -> perform ExceptionToResult in lookup {key = "missing"}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)

        val errPayload = result.payloadValue as MezzeRecord
        val error = errPayload.getField("error") as MezzeVariant
        assertEquals("NotFound", error.constructorName)
    }

    // ========================================================================
    // Test 18: Nested exception handlers - inner catches first
    // ========================================================================

    @Test
    fun `test nested exception handlers - inner catches first`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult, ExceptionToOption}

            let fail = {} -> do
                Exception.throw {error = "inner fail"}
                Some {val = 0}

            let handle = {} -> do
                let inner_result = perform ExceptionToOption in fail {}
                inner_result
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        // Inner handler catches and returns None
        assertEquals("None", (result as MezzeVariant).constructorName)
    }

    // ========================================================================
    // Test 19: Exception in match expression
    // ========================================================================

    @Test
    fun `test exception in match expression`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let process = {opt} -> match opt is
                Some {val} -> Ok {val = val}
                None -> Exception.throw {error = "no value"}

            let handle = {} -> perform ExceptionToResult in process {opt = None}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    // ========================================================================
    // Test 20: Exception with structured error data
    // ========================================================================

    @Test
    fun `test exception with structured error record`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let validate = {x} ->
                if x < 0 then
                    Exception.throw {error = {code = 400, message = "negative value", value = x}}
                else
                    Ok {val = x}

            let handle = {} -> perform ExceptionToResult in validate {x = -5}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)

        val errPayload = result.payloadValue as MezzeRecord
        val error = errPayload.getField("error") as MezzeRecord
        assertEquals(400L, error.getField("code"))
        assertEquals("negative value", error.getField("message"))
        assertEquals(-5L, error.getField("value"))
    }

    // ========================================================================
    // Test 21: Chaining multiple fallible operations
    // ========================================================================

    @Test
    fun `test chaining multiple fallible operations with unwrap`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult, unwrap}

            let step1 = {x} -> if x > 0 then Ok {val = x * 2} else Err {error = "step1 failed"}
            let step2 = {x} -> if x < 100 then Ok {val = x + 10} else Err {error = "step2 failed"}

            let pipeline = {x} -> perform ExceptionToResult in do
                let a = unwrap {result = step1 {x = x}}
                let b = unwrap {result = step2 {x = a}}
                Ok {val = b}

            pipeline {x = 5}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = (result as MezzeVariant).payloadValue as MezzeRecord
        assertEquals(20L, payload.getField("val")) // 5 * 2 + 10 = 20
    }

    @Test
    fun `test chaining fails at first error`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult, unwrap}

            let step1 = {x} -> if x > 0 then Ok {val = x * 2} else Err {error = "step1 failed"}
            let step2 = {x} -> if x < 100 then Ok {val = x + 10} else Err {error = "step2 failed"}

            let pipeline = {x} -> perform ExceptionToResult in do
                let a = unwrap {result = step1 {x = x}}
                let b = unwrap {result = step2 {x = a}}
                Ok {val = b}

            pipeline {x = -5}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = (result as MezzeVariant).payloadValue as MezzeRecord
        assertEquals("step1 failed", payload.getField("error"))
    }

    // ========================================================================
    // Test 22: Exception in loop/recursion
    // ========================================================================

    @Test
    fun `test exception in recursive function`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let countdown = {n} ->
                if n < 0 then
                    Exception.throw {error = "negative countdown"}
                else if n == 0 then
                    Ok {val = "done"}
                else
                    countdown {n = n - 1}

            let handle = {} -> perform ExceptionToResult in countdown {n = -1}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    // ========================================================================
    // Test 23: Return value preserved on success path
    // ========================================================================

    @Test
    fun `test complex success path preserves structure`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let validate = {x} -> if x < 0 then Exception.throw {error = "neg"} else x

            let compute = {x, y} -> perform ExceptionToResult in do
                let vx = validate {x = x}
                let vy = validate {x = y}
                let sum = vx + vy
                let product = vx * vy
                Ok {val = {sum = sum, product = product, inputs = {x = vx, y = vy}}}

            compute {x = 3, y = 4}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)

        val payload = (result as MezzeVariant).payloadValue as MezzeRecord
        val val_ = payload.getField("val") as MezzeRecord
        assertEquals(7L, val_.getField("sum"))
        assertEquals(12L, val_.getField("product"))

        val inputs = val_.getField("inputs") as MezzeRecord
        assertEquals(3L, inputs.getField("x"))
        assertEquals(4L, inputs.getField("y"))
    }

}
