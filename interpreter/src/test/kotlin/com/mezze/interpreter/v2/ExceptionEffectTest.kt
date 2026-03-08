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
 * - ToResult handler (converts exceptions to Result type)
 * - ToOption handler (converts exceptions to Option type)
 * - Ignore handler (ignores all errors)
 * - Custom handlers with specific error types
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
    // Test 1: Basic Exception with ToResult Handler
    // ========================================================================

    @Test
    fun `test basic exception with ToResult handler`() {
        val source = """
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let divide = {a, b} ->
                if b == 0 then
                    Exception.throw {error = "Division by zero"}
                else
                    Ok {val = a / b}

            perform ToResult {cont = continuation::new {}} in divide {a = 10, b = 2}
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
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let divide = {a, b} ->
                if b == 0 then
                    Exception.throw {error = "Division by zero"}
                else
                    Ok {val = a / b}

            perform ToResult {cont = continuation::new {}} in divide {a = 10, b = 0}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val fields = result.payloadValue as MezzeRecord
        val error = fields.getField("error") as String
        assertEquals("Division by zero", error)
    }

    // ========================================================================
    // Test 3: ToOption Handler Returns None on Exception
    // ========================================================================

    @Test
    fun `test ToOption handler returns None on exception`() {
        val source = """
            use std::effects::exception::{Exception, ToOption}
            use std::control::continuation

            let divide = {a, b} ->
                if b == 0 then
                    Exception.throw {error = "Division by zero"}
                else
                    Some {val = a / b}

            perform ToOption {cont = continuation::new {}} in divide {a = 10, b = 0}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("None", (result as MezzeVariant).constructorName)
    }

    // ========================================================================
    // Test 4: Ignore Handler Returns Unit on Exception
    // ========================================================================

    @Test
    fun `test Ignore handler returns unit on exception`() {
        val source = """
            use std::effects::exception::{Exception, Ignore}
            use std::control::continuation

            let may_fail = {x} ->
                if x == 0 then
                    Exception.throw {error = "Failed!"}
                else
                    {}

            perform Ignore {cont = continuation::new {}} in may_fail {x = 0}
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
            use std::effects::exception::{throw, ToResult}
            use std::control::continuation

            let check = {x} ->
                if x < 0 then
                    throw {error = "negative"}
                else
                    Ok {val = x}

            perform ToResult {cont = continuation::new {}} in check {x = -5}
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
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let check_value = {x} ->
                if x > 10 then
                    Exception.throw {error = "Too large"}
                else
                    Ok {val = x}

            perform ToResult {cont = continuation::new {}} in check_value {x = 15}
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
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let multi_throw = {} -> do
                Exception.throw {error = "First error"}
                Exception.throw {error = "Second error"}
                Ok {val = "unreachable"}

            perform ToResult {cont = continuation::new {}} in multi_throw {}
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
            use std::effects::exception::{unwrap, ToResult}
            use std::control::continuation

            let good_result = Ok {val = 42}
            perform ToResult {cont = continuation::new {}} in Ok {val = unwrap {result = good_result}}
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
        // Test that unwrap throws when given Err, and ToResult catches it
        // Note: We use a let binding because unwrap can throw, and we need the throw
        // to happen at the top-level of the perform body, not nested in a variant constructor
        val source = """
            use std::effects::exception::{unwrap, ToResult}
            use std::control::continuation

            let bad_result = Err {error = "Something failed"}
            let try_unwrap = {} ->
                perform ToResult {cont = continuation::new {}} in do
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
