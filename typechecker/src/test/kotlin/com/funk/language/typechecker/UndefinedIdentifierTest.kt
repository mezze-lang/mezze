package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class UndefinedIdentifierTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    @Test
    fun `test undefined function call throws error`() {
        val source = """
            let add = {x, y} -> show {val = ((x + y) > (x - y))}

            add
        """.trimIndent()

        val program = parser.parse(source)

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("Undefined") == true)
        assertTrue(exception.message?.contains("show") == true)
    }

    @Test
    fun `test undefined variable throws error`() {
        val source = """
            let test = undefinedVar + 5

            test
        """.trimIndent()

        val program = parser.parse(source)

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("Undefined") == true)
        assertTrue(exception.message?.contains("undefinedVar") == true)
    }

    @Test
    fun `test undefined function in nested expression throws error`() {
        val source = """
            let compute = {x} -> unknownFunc {val = x * 2}

            compute
        """.trimIndent()

        val program = parser.parse(source)

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("Undefined") == true)
        assertTrue(exception.message?.contains("unknownFunc") == true)
    }

    @Test
    fun `test variant constructor does not throw error`() {
        val source = """
            let value = Some

            value
        """.trimIndent()

        val program = parser.parse(source)

        // Should not throw - variant constructors are allowed
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test lambda parameters are defined in scope`() {
        val source = """
            let identity = {x} -> x

            identity
        """.trimIndent()

        val program = parser.parse(source)

        // Should not throw - x is a parameter
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test let bound variables are defined in scope`() {
        val source = """
            let value = 42
            let result = value + 10

            result
        """.trimIndent()

        val program = parser.parse(source)

        // Should not throw - value is defined
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test built-in functions are defined`() {
        val source = """
            use std::int

            let result = int::to_string {n = 42}

            result
        """.trimIndent()

        val program = parser.parse(source)

        // Should not throw - intToString is built-in
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test undefined function used in if expression`() {
        val source = """
            let test = {x} -> if x > 0 then mysteryFunc {x = x} else 0

            test
        """.trimIndent()

        val program = parser.parse(source)

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("Undefined") == true)
        assertTrue(exception.message?.contains("mysteryFunc") == true)
    }

    @Test
    fun `test undefined variable in record field`() {
        val source = """
            let record = {field = nonExistentVar}

            record
        """.trimIndent()

        val program = parser.parse(source)

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("Undefined") == true)
        assertTrue(exception.message?.contains("nonExistentVar") == true)
    }
}
