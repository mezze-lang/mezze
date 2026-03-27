package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class PartialApplicationTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(source: String): com.mezze.language.ast.TypeCheckResult {
        val program = parser.parse(source)
        return typeChecker.typeCheck(program)
    }

    @Test
    fun `test basic partial application with closed record`() {
        val source = """
            let add = { x, y } -> x + y
            let add_five = add { x = 5 }
            let result = add_five { y = 10 }
            result
        """.trimIndent()

        assertDoesNotThrow {
            val result = parseAndTypeCheck(source)
            assertNotNull(result)
        }
    }

    @Test
    fun `test partial application returns function type`() {
        val source = """
            let multiply = { x, y } -> x * y
            let double = multiply { x = 2 }
            double
        """.trimIndent()

        assertDoesNotThrow {
            val result = parseAndTypeCheck(source)
            assertNotNull(result)

            // The type of 'double' should be a function type
            val doubleType = result.getVariableType("double")
            assertTrue(doubleType is com.mezze.language.ast.MezzeType.FunctionType,
                "Partial application should return a function type")
        }
    }

    @Test
    fun `test chained partial application`() {
        val source = """
            let add3 = { x, y, z } -> x + y + z
            let add_x = add3 { x = 1 }
            let add_xy = add_x { y = 2 }
            let result = add_xy { z = 3 }
            result
        """.trimIndent()

        assertDoesNotThrow {
            val result = parseAndTypeCheck(source)
            assertNotNull(result)
        }
    }

    @Test
    fun `test partial application with different parameter order`() {
        val source = """
            let sub = { x, y } -> x - y
            let sub_from_10 = sub { y = 10 }
            let result = sub_from_10 { x = 15 }
            result
        """.trimIndent()

        assertDoesNotThrow {
            val result = parseAndTypeCheck(source)
            assertNotNull(result)
        }
    }

    @Test
    fun `test partial application with pipe operator`() {
        val source = """
            let add = { x, y } -> x + y
            let multiply = { x, y } -> x * y
            let double = multiply { x = 2 }
            let result = 7 |> add { x = @, y = 3 } |> double { y = @ }
            result
        """.trimIndent()

        assertDoesNotThrow {
            val result = parseAndTypeCheck(source)
            assertNotNull(result)
        }
    }

    @Test
    fun `test partial application with mixed parameter types`() {
        val source = """
            let pair = { x, y } -> x
            let with_int = pair { x = 5 }
            let result = with_int { y = "string" }
            result
        """.trimIndent()

        assertDoesNotThrow {
            val result = parseAndTypeCheck(source)
            assertNotNull(result)
        }
    }

    @Test
    fun `test closed record rejects extra fields`() {
        val source = """
            let add = { x, y } -> x + y
            let result = add { x = 5, y = 10, z = 15 }
            result
        """.trimIndent()

        // Should reject extra field 'z' for closed record
        assertThrows(TypeCheckError::class.java) {
            parseAndTypeCheck(source)
        }
    }

    @Test
    fun `test partial application with string types`() {
        val source = """
            let concat3 = { a, b, c } -> "$(a)$(b)$(c)"
            let with_hello = concat3 { a = "hello" }
            let with_hello_world = with_hello { b = " world" }
            let result = with_hello_world { c = "!" }
            result
        """.trimIndent()

        assertDoesNotThrow {
            val result = parseAndTypeCheck(source)
            assertNotNull(result)
        }
    }

    @Test
    fun `test partial application with polymorphic function`() {
        val source = """
            let first = { x, y } -> x
            let get_5 = first { x = 5 }
            let result = get_5 { y = "ignored" }
            result
        """.trimIndent()

        assertDoesNotThrow {
            val result = parseAndTypeCheck(source)
            assertNotNull(result)
        }
    }

    @Test
    fun `test multiple partial applications of same function`() {
        val source = """
            let add = { x, y } -> x + y
            let add_5 = add { x = 5 }
            let add_10 = add { x = 10 }
            let result1 = add_5 { y = 3 }
            let result2 = add_10 { y = 3 }
            result1
        """.trimIndent()

        assertDoesNotThrow {
            val result = parseAndTypeCheck(source)
            assertNotNull(result)
        }
    }

    @Test
    fun `test open record partial application accepts extra fields`() {
        val source = """
            let process: { x: Int, y: Int, ... } -> Int = { x, y } -> x + y
            let process_x = process { x = 5 }
            let result = process_x { y = 10, z = "ignored" }
            result
        """.trimIndent()

        assertDoesNotThrow {
            val result = parseAndTypeCheck(source)
            assertNotNull(result)
        }
    }

    @Test
    fun `test open record partial application preserves open nature`() {
        val source = """
            let combine: { a: Int, b: Int, c: Int, ... } -> Int = { a, b, c } -> a + b + c
            let with_a = combine { a = 1 }
            let with_ab = with_a { b = 2 }
            let result = with_ab { c = 3, extra = "ignored" }
            result
        """.trimIndent()

        assertDoesNotThrow {
            val result = parseAndTypeCheck(source)
            assertNotNull(result)
        }
    }
}
