package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

class UndefinedIdentifierTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `test undefined function call throws error`() {
        val source = """
            let add = {x, y} -> show {val = ((x + y) > (x - y))}

            add
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        assertTrue(exception.message?.contains("Undefined") == true)
        assertTrue(exception.message?.contains("unknownFunc") == true)
    }

    @Test
    fun `test variant constructor does not throw error`() {
        // Using MyVariant instead of Some because Some is a built-in Option constructor
        // that expects a payload
        val source = """
            let value = MyVariant

            value
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // Should not throw - variant constructors are allowed
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test lambda parameters are defined in scope`() {
        val source = """
            let identity = {x} -> x

            identity
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // Should not throw - x is a parameter
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test let bound variables are defined in scope`() {
        val source = """
            let value = 42
            let result = value + 10

            result
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // Should not throw - value is defined
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test built-in functions are defined`() {
        val source = """
            use std::int

            let result = int::to_str {n = 42}

            result
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // Should not throw - intToString is built-in
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test undefined function used in if expression`() {
        val source = """
            let test = {x} -> if x > 0 then mysteryFunc {x = x} else 0

            test
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        assertTrue(exception.message?.contains("Undefined") == true)
        assertTrue(exception.message?.contains("nonExistentVar") == true)
    }
}
