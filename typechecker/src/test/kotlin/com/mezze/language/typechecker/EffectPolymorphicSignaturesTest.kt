package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for effect-polymorphic signatures.
 *
 * Tests that effects with associated types (like Exception having 'error)
 * propagate their concrete type bindings through function signatures and perform expressions.
 *
 * All handlers use scoped continuations internally - no need to pass continuations as parameters.
 */
class EffectPolymorphicSignaturesTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(code: String) {
        val program = parser.parse(code)
        typeChecker.typeCheck(program)
    }

    // ========================================================================
    // DIRECT PERFORM - ExceptionToResult
    // ========================================================================

    @Nested
    @DisplayName("Direct Perform with ExceptionToResult")
    inner class DirectPerformExceptionToResult {

        @Test
        fun `direct ExceptionToResult with String error`() {
            typeCheck("""
                use std::effects::exception::{Exception, ExceptionToResult}

                let safe_div = {a, b} ->
                    perform ExceptionToResult in
                        if b == 0 then
                            Exception.throw {error = "division by zero"}
                        else
                            Ok { val = a / b }
            """.trimIndent())
        }

        @Test
        fun `direct ExceptionToResult with Int error`() {
            typeCheck("""
                use std::effects::exception::{Exception, ExceptionToResult}

                let safe_div = {a, b} ->
                    perform ExceptionToResult in
                        if b == 0 then
                            Exception.throw {error = 42}
                        else
                            Ok { val = a / b }
            """.trimIndent())
        }

        @Test
        fun `direct ExceptionToResult with record error`() {
            typeCheck("""
                use std::effects::exception::{Exception, ExceptionToResult}

                let safe_div = {a, b} ->
                    perform ExceptionToResult in
                        if b == 0 then
                            Exception.throw {error = {code = 500, msg = "error"}}
                        else
                            Ok { val = a / b }
            """.trimIndent())
        }
    }

    // ========================================================================
    // STEP-BY-STEP PERFORM - ExceptionToResult
    // ========================================================================

    @Nested
    @DisplayName("Step-by-Step Perform with ExceptionToResult")
    inner class StepByStepPerformExceptionToResult {

        @Test
        fun `step-by-step ExceptionToResult with String error`() {
            typeCheck("""
                use std::effects::exception::{Exception, ExceptionToResult}

                let thrower = {a, b} ->
                    if b == 0 then
                        Exception.throw {error = "division by zero"}
                    else
                        a / b

                let calc = thrower {a = 10, b = 2}

                let result = {} ->
                    perform ExceptionToResult in
                        Ok { val = calc }
            """.trimIndent())
        }

        @Test
        fun `step-by-step ExceptionToResult with Int error`() {
            typeCheck("""
                use std::effects::exception::{Exception, ExceptionToResult}

                let thrower = {a, b} ->
                    if b == 0 then
                        Exception.throw {error = 42}
                    else
                        a / b

                let calc = thrower {a = 10, b = 2}

                let result = {} ->
                    perform ExceptionToResult in
                        Ok { val = calc }
            """.trimIndent())
        }

        @Test
        fun `step-by-step ExceptionToResult with record error`() {
            typeCheck("""
                use std::effects::exception::{Exception, ExceptionToResult}

                let thrower = {a, b} ->
                    if b == 0 then
                        Exception.throw {error = {code = 500, msg = "error"}}
                    else
                        a / b

                let calc = thrower {a = 10, b = 2}

                let result = {} ->
                    perform ExceptionToResult in
                        Ok { val = calc }
            """.trimIndent())
        }
    }

    // ========================================================================
    // DIRECT PERFORM - ExceptionToOption
    // ========================================================================

    @Nested
    @DisplayName("Direct Perform with ExceptionToOption")
    inner class DirectPerformExceptionToOption {

        @Test
        fun `direct ExceptionToOption with String error`() {
            typeCheck("""
                use std::effects::exception::{Exception, ExceptionToOption}

                let safe_div = {a, b} ->
                    perform ExceptionToOption in
                        if b == 0 then
                            Exception.throw {error = "division by zero"}
                        else
                            Some { val = a / b }
            """.trimIndent())
        }

        @Test
        fun `direct ExceptionToOption with record error`() {
            typeCheck("""
                use std::effects::exception::{Exception, ExceptionToOption}

                let safe_div = {a, b} ->
                    perform ExceptionToOption in
                        if b == 0 then
                            Exception.throw {error = {code = 500, msg = "error"}}
                        else
                            Some { val = a / b }
            """.trimIndent())
        }
    }

    // ========================================================================
    // STEP-BY-STEP PERFORM - ExceptionToOption
    // ========================================================================

    @Nested
    @DisplayName("Step-by-Step Perform with ExceptionToOption")
    inner class StepByStepPerformExceptionToOption {

        @Test
        fun `step-by-step ExceptionToOption with String error`() {
            typeCheck("""
                use std::effects::exception::{Exception, ExceptionToOption}

                let thrower = {a, b} ->
                    if b == 0 then
                        Exception.throw {error = "division by zero"}
                    else
                        a / b

                let calc = thrower {a = 10, b = 2}

                let result = {} ->
                    perform ExceptionToOption in
                        Some { val = calc }
            """.trimIndent())
        }
    }

    // ========================================================================
    // TYPE ERRORS - Missing Ok/Some wrapper
    // ========================================================================

    @Nested
    @DisplayName("Type Errors for Missing Wrapper")
    inner class TypeErrorsMissingWrapper {

        @Test
        fun `ExceptionToResult without Ok wrapper produces type error`() {
            val error = assertThrows<TypeCheckError> {
                typeCheck("""
                    use std::effects::exception::{Exception, ExceptionToResult}

                    let thrower = {a, b} ->
                        if b == 0 then
                            Exception.throw {error = "division by zero"}
                        else
                            a / b

                    let calc = thrower {a = 10, b = 2}

                    let result = {} ->
                        perform ExceptionToResult in
                            calc
                """.trimIndent())
            }
            assertTrue(error.message?.contains("Result") == true || error.message?.contains("mismatch") == true)
        }

        @Test
        fun `ExceptionToOption without Some wrapper produces type error`() {
            val error = assertThrows<TypeCheckError> {
                typeCheck("""
                    use std::effects::exception::{Exception, ExceptionToOption}

                    let thrower = {a, b} ->
                        if b == 0 then
                            Exception.throw {error = "division by zero"}
                        else
                            a / b

                    let calc = thrower {a = 10, b = 2}

                    let result = {} ->
                        perform ExceptionToOption in
                            calc
                """.trimIndent())
            }
            assertTrue(error.message?.contains("Option") == true || error.message?.contains("mismatch") == true)
        }

        @Test
        fun `direct ExceptionToResult without Ok wrapper produces type error`() {
            val error = assertThrows<TypeCheckError> {
                typeCheck("""
                    use std::effects::exception::{Exception, ExceptionToResult}

                    let safe_div = {a, b} ->
                        perform ExceptionToResult in
                            if b == 0 then
                                Exception.throw {error = "division by zero"}
                            else
                                a / b
                """.trimIndent())
            }
            assertTrue(error.message?.contains("Result") == true || error.message?.contains("mismatch") == true)
        }
    }

    // ========================================================================
    // MULTIPLE THROWS
    // ========================================================================

    @Nested
    @DisplayName("Multiple Throws")
    inner class MultipleThrows {

        @Test
        fun `multiple throws with same String error type`() {
            typeCheck("""
                use std::effects::exception::{Exception, ExceptionToResult}

                let validate = {x} ->
                    if x < 0 then
                        Exception.throw {error = "negative"}
                    else if x > 100 then
                        Exception.throw {error = "too large"}
                    else
                        x

                let result = {} ->
                    perform ExceptionToResult in
                        Ok { val = validate {x = 50} }
            """.trimIndent())
        }

        @Test
        fun `multiple throws with same Int error type`() {
            typeCheck("""
                use std::effects::exception::{Exception, ExceptionToResult}

                let validate = {x} ->
                    if x < 0 then
                        Exception.throw {error = -1}
                    else if x > 100 then
                        Exception.throw {error = 100}
                    else
                        x

                let result = {} ->
                    perform ExceptionToResult in
                        Ok { val = validate {x = 50} }
            """.trimIndent())
        }
    }

    // ========================================================================
    // CHAINED FUNCTION CALLS
    // ========================================================================

    @Nested
    @DisplayName("Chained Function Calls")
    inner class ChainedCalls {

        @Test
        fun `chained calls propagate error type`() {
            typeCheck("""
                use std::effects::exception::{Exception, ExceptionToResult}

                let may_fail = {x} ->
                    if x == 0 then
                        Exception.throw {error = "zero not allowed"}
                    else
                        x

                let double_it = {x} ->
                    may_fail {x = x} * 2

                let triple_it = {x} ->
                    double_it {x = x} * 3

                let result = {} ->
                    perform ExceptionToResult in
                        Ok { val = triple_it {x = 5} }
            """.trimIndent())
        }
    }

    // ========================================================================
    // DIFFERENT RETURN TYPES
    // ========================================================================

    @Nested
    @DisplayName("Different Return Types")
    inner class DifferentReturnTypes {

        @Test
        fun `ExceptionToResult with String value`() {
            typeCheck("""
                use std::effects::exception::{Exception, ExceptionToResult}

                let get_name = {id} ->
                    perform ExceptionToResult in
                        if id == 0 then
                            Exception.throw {error = "not found"}
                        else
                            Ok { val = "Alice" }
            """.trimIndent())
        }

        @Test
        fun `ExceptionToResult with record value`() {
            typeCheck("""
                use std::effects::exception::{Exception, ExceptionToResult}

                let get_user = {id} ->
                    perform ExceptionToResult in
                        if id == 0 then
                            Exception.throw {error = "not found"}
                        else
                            Ok { val = {name = "Alice", age = 30} }
            """.trimIndent())
        }
    }
}
