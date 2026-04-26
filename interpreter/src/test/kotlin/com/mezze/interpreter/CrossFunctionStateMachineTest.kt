package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import com.mezze.interpreter.values.MezzeVariant
import com.mezze.interpreter.values.MezzeRecord

/**
 * Tests for cross-function state machine transformation.
 *
 * These tests verify that effectful functions called from within a perform block
 * are correctly transformed to state machines, enabling continuation operations
 * like capture and resume to work across function boundaries.
 */
class CrossFunctionStateMachineTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================================================
    // Test: Exception effect works across function boundaries
    // ========================================================================

    @Test
    fun `test exception effect works in separate function`() {
        // effectful function returns Int with Exception effect
        // wrapping in Ok happens at the perform site
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let may_fail = {x} ->
                if x < 0 then
                    Exception.throw {error = "negative"}
                else
                    x * 2

            perform ExceptionToResult in Ok {val = may_fail {x = -5}}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    @Test
    fun `test exception effect success path`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let may_fail = {x} ->
                if x < 0 then
                    Exception.throw {error = "negative"}
                else
                    x * 2

            perform ExceptionToResult in Ok {val = may_fail {x = 5}}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val inner = (result as MezzeVariant).getPayloadValue() as MezzeRecord
        assertEquals(10L, inner.getField("val"))
    }

    @Test
    fun `test exception effect works in nested function calls`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let inner = {x} ->
                if x == 0 then
                    Exception.throw {error = "zero"}
                else
                    x

            let outer = {x} -> do
                let y = inner {x = x}
                y * 2

            perform ExceptionToResult in Ok {val = outer {x = 0}}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
    }

    @Test
    fun `test exception effect works with multiple function calls`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let step1 = {x} ->
                if x < 0 then Exception.throw {error = "step1 failed"}
                else x + 1

            let step2 = {x} ->
                if x > 100 then Exception.throw {error = "step2 failed"}
                else x * 2

            let process = {x} -> do
                let a = step1 {x = x}
                let b = step2 {x = a}
                b + 10

            perform ExceptionToResult in Ok {val = process {x = 5}}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val inner = (result as MezzeVariant).getPayloadValue() as MezzeRecord
        assertEquals(22L, inner.getField("val")) // (5+1)*2+10 = 22
    }

    // ========================================================================
    // Test: Early return effect works across function boundaries
    // ========================================================================

    @Test
    fun `test early return in separate function`() {
        val source = """
            use std::effects::early_return::{Return, EarlyReturn}

            let check = {x} ->
                if x < 0 then
                    Return.return {val = "early"}
                else
                    "normal"

            perform EarlyReturn in check {x = -1}
        """.trimIndent()

        val result = eval(source)
        assertEquals("early", result)
    }

    @Test
    fun `test early return in nested function`() {
        val source = """
            use std::effects::early_return::{Return, EarlyReturn}

            let inner = {x} ->
                if x == 0 then Return.return {val = 999}
                else x * 2

            let outer = {x} -> do
                let y = inner {x = x}
                y + 100

            perform EarlyReturn in outer {x = 0}
        """.trimIndent()

        val result = eval(source)
        assertEquals(999L, result)
    }

    // ========================================================================
    // Test: Effectful lambda body is transformed to state machine
    // ========================================================================

    @Test
    fun `test effectful function with do block is transformed`() {
        // This tests that the lambda body transformation works
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let complex_operation = {a, b} -> do
                let sum = a + b
                if sum < 0 then
                    Exception.throw {error = "sum negative"}
                else {}
                let product = a * b
                if product > 1000 then
                    Exception.throw {error = "product too large"}
                else {}
                {sum = sum, product = product}

            perform ExceptionToResult in Ok {val = complex_operation {a = 5, b = 3}}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val inner = (result as MezzeVariant).getPayloadValue() as MezzeRecord
        val record = inner.getField("val") as MezzeRecord
        assertEquals(8L, record.getField("sum"))
        assertEquals(15L, record.getField("product"))
    }

    // ========================================================================
    // Test: Pure functions are not transformed (performance check)
    // ========================================================================

    @Test
    fun `test pure function still works correctly`() {
        // Pure functions should not be transformed to state machines
        // This is a sanity check to ensure we don't break pure functions
        val source = """
            let pure_add = {a, b} -> a + b
            let pure_mul = {a, b} -> a * b

            let result = pure_add {a = 10, b = 5}
            pure_mul {a = result, b = 2}
        """.trimIndent()

        val result = eval(source)
        assertEquals(30L, result)
    }

    @Test
    fun `test effectful function called from pure context works`() {
        // An effectful function should work when the effect is handled
        // Wrapping in Ok happens at the perform site
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let effectful = {x} ->
                if x < 0 then Exception.throw {error = "negative"}
                else x

            let pure_wrapper = {x} ->
                perform ExceptionToResult in Ok {val = effectful {x = x}}

            pure_wrapper {x = 5}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
    }

    @Test
    fun `test exception to option works`() {
        // Same effectful function, but wrapped with Some at perform site
        val source = """
            use std::effects::exception::{Exception, ExceptionToOption}

            let effectful = {x} ->
                if x < 0 then Exception.throw {error = "negative"}
                else x

            let wrapper = {x} ->
                perform ExceptionToOption in Some {val = effectful {x = x}}

            wrapper {x = 5}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Some", (result as MezzeVariant).constructorName)
    }

    @Test
    fun `test exception to option failure path`() {
        val source = """
            use std::effects::exception::{Exception, ExceptionToOption}

            let effectful = {x} ->
                if x < 0 then Exception.throw {error = "negative"}
                else x

            let wrapper = {x} ->
                perform ExceptionToOption in Some {val = effectful {x = x}}

            wrapper {x = -5}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("None", (result as MezzeVariant).constructorName)
    }
}
