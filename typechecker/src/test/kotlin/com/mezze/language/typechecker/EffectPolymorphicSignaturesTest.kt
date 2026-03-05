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
    // DIRECT PERFORM - ToResult
    // ========================================================================

    @Nested
    @DisplayName("Direct Perform with ToResult")
    inner class DirectPerformToResult {

        @Test
        fun `direct ToResult with String error`() {
            typeCheck("""
                use std::control::continuation
                use std::control::continuation::{Continuation}
                use std::effects::exception::{Exception, ToResult}

                let safe_div = {a, b} ->
                    perform ToResult {cont = continuation::new {}} in
                        if b == 0 then
                            Exception.throw {error = "division by zero"}
                        else
                            Ok { val = a / b }
            """.trimIndent())
        }

        @Test
        fun `direct ToResult with Int error`() {
            typeCheck("""
                use std::control::continuation
                use std::control::continuation::{Continuation}
                use std::effects::exception::{Exception, ToResult}

                let safe_div = {a, b} ->
                    perform ToResult {cont = continuation::new {}} in
                        if b == 0 then
                            Exception.throw {error = 42}
                        else
                            Ok { val = a / b }
            """.trimIndent())
        }

        @Test
        fun `direct ToResult with record error`() {
            typeCheck("""
                use std::control::continuation
                use std::control::continuation::{Continuation}
                use std::effects::exception::{Exception, ToResult}

                let safe_div = {a, b} ->
                    perform ToResult {cont = continuation::new {}} in
                        if b == 0 then
                            Exception.throw {error = {code = 500, msg = "error"}}
                        else
                            Ok { val = a / b }
            """.trimIndent())
        }
    }

    // ========================================================================
    // STEP-BY-STEP PERFORM - ToResult
    // ========================================================================

    @Nested
    @DisplayName("Step-by-Step Perform with ToResult")
    inner class StepByStepPerformToResult {

        @Test
        fun `step-by-step ToResult with String error`() {
            typeCheck("""
                use std::control::continuation
                use std::control::continuation::{Continuation}
                use std::effects::exception::{Exception, ToResult}

                let thrower = {a, b} ->
                    if b == 0 then
                        Exception.throw {error = "division by zero"}
                    else
                        a / b

                let calc = thrower {a = 10, b = 2}

                let result = {} ->
                    perform ToResult {cont = continuation::new {}} in
                        Ok { val = calc }
            """.trimIndent())
        }

        @Test
        fun `step-by-step ToResult with Int error`() {
            typeCheck("""
                use std::control::continuation
                use std::control::continuation::{Continuation}
                use std::effects::exception::{Exception, ToResult}

                let thrower = {a, b} ->
                    if b == 0 then
                        Exception.throw {error = 42}
                    else
                        a / b

                let calc = thrower {a = 10, b = 2}

                let result = {} ->
                    perform ToResult {cont = continuation::new {}} in
                        Ok { val = calc }
            """.trimIndent())
        }

        @Test
        fun `step-by-step ToResult with record error`() {
            typeCheck("""
                use std::control::continuation
                use std::control::continuation::{Continuation}
                use std::effects::exception::{Exception, ToResult}

                let thrower = {a, b} ->
                    if b == 0 then
                        Exception.throw {error = {code = 500, msg = "error"}}
                    else
                        a / b

                let calc = thrower {a = 10, b = 2}

                let result = {} ->
                    perform ToResult {cont = continuation::new {}} in
                        Ok { val = calc }
            """.trimIndent())
        }
    }

    // ========================================================================
    // DIRECT PERFORM - ToOption
    // ========================================================================

    @Nested
    @DisplayName("Direct Perform with ToOption")
    inner class DirectPerformToOption {

        @Test
        fun `direct ToOption with String error`() {
            typeCheck("""
                use std::control::continuation
                use std::control::continuation::{Continuation}
                use std::effects::exception::{Exception, ToOption}

                let safe_div = {a, b} ->
                    perform ToOption {cont = continuation::new {}} in
                        if b == 0 then
                            Exception.throw {error = "division by zero"}
                        else
                            Some { val = a / b }
            """.trimIndent())
        }

        @Test
        fun `direct ToOption with record error`() {
            typeCheck("""
                use std::control::continuation
                use std::control::continuation::{Continuation}
                use std::effects::exception::{Exception, ToOption}

                let safe_div = {a, b} ->
                    perform ToOption {cont = continuation::new {}} in
                        if b == 0 then
                            Exception.throw {error = {code = 500, msg = "error"}}
                        else
                            Some { val = a / b }
            """.trimIndent())
        }
    }

    // ========================================================================
    // STEP-BY-STEP PERFORM - ToOption
    // ========================================================================

    @Nested
    @DisplayName("Step-by-Step Perform with ToOption")
    inner class StepByStepPerformToOption {

        @Test
        fun `step-by-step ToOption with String error`() {
            typeCheck("""
                use std::control::continuation
                use std::control::continuation::{Continuation}
                use std::effects::exception::{Exception, ToOption}

                let thrower = {a, b} ->
                    if b == 0 then
                        Exception.throw {error = "division by zero"}
                    else
                        a / b

                let calc = thrower {a = 10, b = 2}

                let result = {} ->
                    perform ToOption {cont = continuation::new {}} in
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
        fun `ToResult without Ok wrapper produces type error`() {
            val error = assertThrows<TypeCheckError> {
                typeCheck("""
                    use std::control::continuation
                    use std::control::continuation::{Continuation}
                    use std::effects::exception::{Exception, ToResult}

                    let thrower = {a, b} ->
                        if b == 0 then
                            Exception.throw {error = "division by zero"}
                        else
                            a / b

                    let calc = thrower {a = 10, b = 2}

                    let result = {} ->
                        perform ToResult {cont = continuation::new {}} in
                            calc
                """.trimIndent())
            }
            assertTrue(error.message?.contains("Result") == true || error.message?.contains("mismatch") == true)
        }

        @Test
        fun `ToOption without Some wrapper produces type error`() {
            val error = assertThrows<TypeCheckError> {
                typeCheck("""
                    use std::control::continuation
                    use std::control::continuation::{Continuation}
                    use std::effects::exception::{Exception, ToOption}

                    let thrower = {a, b} ->
                        if b == 0 then
                            Exception.throw {error = "division by zero"}
                        else
                            a / b

                    let calc = thrower {a = 10, b = 2}

                    let result = {} ->
                        perform ToOption {cont = continuation::new {}} in
                            calc
                """.trimIndent())
            }
            assertTrue(error.message?.contains("Option") == true || error.message?.contains("mismatch") == true)
        }

        @Test
        fun `direct ToResult without Ok wrapper produces type error`() {
            val error = assertThrows<TypeCheckError> {
                typeCheck("""
                    use std::control::continuation
                    use std::control::continuation::{Continuation}
                    use std::effects::exception::{Exception, ToResult}

                    let safe_div = {a, b} ->
                        perform ToResult {cont = continuation::new {}} in
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
                use std::control::continuation
                use std::control::continuation::{Continuation}
                use std::effects::exception::{Exception, ToResult}

                let validate = {x} ->
                    if x < 0 then
                        Exception.throw {error = "negative"}
                    else if x > 100 then
                        Exception.throw {error = "too large"}
                    else
                        x

                let result = {} ->
                    perform ToResult {cont = continuation::new {}} in
                        Ok { val = validate {x = 50} }
            """.trimIndent())
        }

        @Test
        fun `multiple throws with same Int error type`() {
            typeCheck("""
                use std::control::continuation
                use std::control::continuation::{Continuation}
                use std::effects::exception::{Exception, ToResult}

                let validate = {x} ->
                    if x < 0 then
                        Exception.throw {error = -1}
                    else if x > 100 then
                        Exception.throw {error = 100}
                    else
                        x

                let result = {} ->
                    perform ToResult {cont = continuation::new {}} in
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
                use std::control::continuation
                use std::control::continuation::{Continuation}
                use std::effects::exception::{Exception, ToResult}

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
                    perform ToResult {cont = continuation::new {}} in
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
        fun `ToResult with String value`() {
            typeCheck("""
                use std::control::continuation
                use std::control::continuation::{Continuation}
                use std::effects::exception::{Exception, ToResult}

                let get_name = {id} ->
                    perform ToResult {cont = continuation::new {}} in
                        if id == 0 then
                            Exception.throw {error = "not found"}
                        else
                            Ok { val = "Alice" }
            """.trimIndent())
        }

        @Test
        fun `ToResult with record value`() {
            typeCheck("""
                use std::control::continuation
                use std::control::continuation::{Continuation}
                use std::effects::exception::{Exception, ToResult}

                let get_user = {id} ->
                    perform ToResult {cont = continuation::new {}} in
                        if id == 0 then
                            Exception.throw {error = "not found"}
                        else
                            Ok { val = {name = "Alice", age = 30} }
            """.trimIndent())
        }
    }
}
