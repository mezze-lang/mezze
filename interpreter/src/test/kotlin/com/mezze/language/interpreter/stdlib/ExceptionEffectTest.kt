package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/effects/exception module.
 * Tests exception handling with ToResult, ToOption, Ignore, Abort, and Crash handlers.
 */
class ExceptionEffectTest {

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

    // === ToResult Handler Tests ===

    @Test
    fun `test ToResult returns Ok on success`() {
        val result = eval("""
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let divide = {a, b} ->
                if b == 0 then
                    Exception.throw {error = "Division by zero"}
                else
                    a / b

            perform ToResult {cont = continuation::new {}} in divide {a = 10, b = 2}
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructor)
    }

    @Test
    fun `test ToResult returns Err on exception`() {
        val result = eval("""
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let divide = {a, b} ->
                if b == 0 then
                    Exception.throw {error = "Division by zero"}
                else
                    a / b

            perform ToResult {cont = continuation::new {}} in divide {a = 10, b = 0}
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructor)
        val fields = result.value as MezzeRecord
        val error = fields.fields["error"] as MezzeString
        assertEquals("Division by zero", error.value)
    }

    // === ToOption Handler Tests ===

    @Test
    fun `test ToOption returns None on exception`() {
        val result = eval("""
            use std::effects::exception::{Exception, ToOption}
            use std::control::continuation

            let divide = {a, b} ->
                if b == 0 then
                    Exception.throw {error = "Division by zero"}
                else
                    a / b

            perform ToOption {cont = continuation::new {}} in divide {a = 10, b = 0}
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("None", (result as MezzeVariant).constructor)
    }

    // === Ignore Handler Tests ===

    @Test
    fun `test Ignore returns unit on exception`() {
        val result = eval("""
            use std::effects::exception::{Exception, Ignore}
            use std::control::continuation

            let may_fail = {x} ->
                if x == 0 then
                    Exception.throw {error = "Failed!"}
                else
                    x

            perform Ignore {cont = continuation::new {}} in may_fail {x = 0}
        """)
        assertTrue(result is MezzeRecord)
        assertEquals(0, (result as MezzeRecord).fields.size)
    }

    // === throw Helper Function Tests ===

    @Test
    fun `test throw helper function`() {
        val result = eval("""
            use std::effects::exception::{throw, ToResult}
            use std::control::continuation

            let check = {x} ->
                if x < 0 then
                    throw {error = "negative"}
                else
                    x

            perform ToResult {cont = continuation::new {}} in check {x = -5}
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructor)
        val fields = result.value as MezzeRecord
        val error = fields.fields["error"] as MezzeString
        assertEquals("negative", error.value)
    }

    // === unwrap Helper Function Tests ===

    @Test
    fun `test unwrap with Ok value`() {
        val result = eval("""
            use std::effects::exception::{unwrap, ToResult}
            use std::control::continuation

            let good_result = Ok {val = 42}
            perform ToResult {cont = continuation::new {}} in unwrap {result = good_result}
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructor)
    }

    @Test
    fun `test unwrap with Err throws exception`() {
        val result = eval("""
            use std::effects::exception::{unwrap, ToResult}
            use std::control::continuation

            let bad_result = Err {error = "Something failed"}
            perform ToResult {cont = continuation::new {}} in unwrap {result = bad_result}
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructor)
        val error = ((result as MezzeVariant).value as MezzeRecord).fields["error"] as MezzeString
        assertEquals("Something failed", error.value)
    }

    // === Multiple Exceptions Tests ===

    @Test
    fun `test first exception wins`() {
        val result = eval("""
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let multi_throw = {} -> do
                Exception.throw {error = "First error"}
                Exception.throw {error = "Second error"}
                "unreachable"

            perform ToResult {cont = continuation::new {}} in multi_throw {}
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructor)
        val error = ((result as MezzeVariant).value as MezzeRecord).fields["error"] as MezzeString
        assertEquals("First error", error.value)
    }

    // === Exception in Conditional Tests ===

    @Test
    fun `test exception in if branch`() {
        val result = eval("""
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let check_value = {x} ->
                if x > 10 then
                    Exception.throw {error = "Too large"}
                else
                    x

            perform ToResult {cont = continuation::new {}} in check_value {x = 15}
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructor)
    }

    // === Exception with Different Error Types ===

    @Test
    fun `test exception with integer error`() {
        val result = eval("""
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let check = {x} ->
                if x < 0 then
                    Exception.throw {error = -1}
                else
                    x

            perform ToResult {cont = continuation::new {}} in check {x = -5}
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructor)
        val error = ((result as MezzeVariant).value as MezzeRecord).fields["error"] as MezzeInt
        assertEquals(-1L, error.value)
    }

    @Test
    fun `test exception with record error`() {
        val result = eval("""
            use std::effects::exception::{Exception, ToResult}
            use std::control::continuation

            let check = {x} ->
                if x < 0 then
                    Exception.throw {error = {code = 400, message = "Bad request"}}
                else
                    x

            perform ToResult {cont = continuation::new {}} in check {x = -5}
        """)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructor)
        val error = (result as MezzeVariant).value as MezzeRecord
        val errorValue = error.fields["error"] as MezzeRecord
        assertEquals(400L, (errorValue.fields["code"] as MezzeInt).value)
    }
}
