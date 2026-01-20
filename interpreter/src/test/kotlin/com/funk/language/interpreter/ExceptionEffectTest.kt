package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

/**
 * Integration tests for the Exception effect from stdlib
 *
 * Tests that the exception handling system works end-to-end with:
 * - ToResult handler (converts exceptions to Result type)
 * - ToOption handler (converts exceptions to Option type)
 * - Ignore handler (ignores all errors)
 * - Custom handlers with specific error types
 *
 * ENABLED: Continuation/Delimited now implemented!
 */
class ExceptionEffectTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: FunkInterpreter

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        interpreter = FunkInterpreter()
        // Set project root for stdlib module resolution
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
        // Clear module cache for fresh resolution
        com.funk.language.typechecker.ModuleResolver.clearCache()
        com.funk.language.typechecker.TypeChecker.clearCache()
    }

    private fun executeProgram(source: String): FunkValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val projectRoot = Paths.get(System.getProperty("funk.project.root", "."))
        return FunkTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
    }

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
                    a / b

            perform ToResult {cont = continuation::new {}} in divide {a = 10, b = 2}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkVariant)
        assertEquals("Ok", (result as FunkVariant).constructor)
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
                    a / b

            perform ToResult {cont = continuation::new {}} in divide {a = 10, b = 0}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkVariant)
        assertEquals("Err", (result as FunkVariant).constructor)
        val fields = result.value as FunkRecord
        val error = fields.fields["error"] as FunkString
        assertEquals("Division by zero", error.value)
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
                    a / b

            perform ToOption {cont = continuation::new {}} in divide {a = 10, b = 0}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkVariant)
        assertEquals("None", (result as FunkVariant).constructor)
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
                    x

            perform Ignore {cont = continuation::new {}} in may_fail {x = 0}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkRecord)
        assertEquals(0, (result as FunkRecord).fields.size)
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
                    x

            perform ToResult {cont = continuation::new {}} in check {x = -5}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkVariant)
        assertEquals("Err", (result as FunkVariant).constructor)
        val fields = result.value as FunkRecord
        val error = fields.fields["error"] as FunkString
        assertEquals("negative", error.value)
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
                    x

            perform ToResult {cont = continuation::new {}} in check_value {x = 15}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkVariant)
        assertEquals("Err", (result as FunkVariant).constructor)
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
                "unreachable"

            perform ToResult {cont = continuation::new {}} in multi_throw {}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkVariant)
        assertEquals("Err", (result as FunkVariant).constructor)
        val error = ((result as FunkVariant).value as FunkRecord).fields["error"] as FunkString
        assertEquals("First error", error.value)
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
            perform ToResult {cont = continuation::new {}} in unwrap {result = good_result}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkVariant)
        assertEquals("Ok", (result as FunkVariant).constructor)
    }

    // ========================================================================
    // Test 9: unwrap with Err throws
    // ========================================================================

    @Test
    fun `test unwrap with Err throws exception`() {
        val source = """
            use std::effects::exception::{unwrap, ToResult}
            use std::control::continuation

            let bad_result = Err {error = "Something failed"}
            perform ToResult {cont = continuation::new {}} in unwrap {result = bad_result}
        """.trimIndent()

        val result = executeProgram(source)
        assertTrue(result is FunkVariant)
        assertEquals("Err", (result as FunkVariant).constructor)
        val error = ((result as FunkVariant).value as FunkRecord).fields["error"] as FunkString
        assertEquals("Something failed", error.value)
    }

}
