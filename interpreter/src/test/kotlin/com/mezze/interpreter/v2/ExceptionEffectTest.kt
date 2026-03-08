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

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

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

}
