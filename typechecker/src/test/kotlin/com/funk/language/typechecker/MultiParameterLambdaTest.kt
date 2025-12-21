package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for multi-parameter lambda type checking and closure variable capture.
 * These tests specifically cover the bug fix for monomorphic function name resolution
 * in closure variables.
 */
class MultiParameterLambdaTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    private fun parseAndTypeCheck(source: String): com.funk.language.ast.Program {
        val program = parser.parse(source)
        return typeChecker.typeCheck(program).program
    }

    @Test
    fun `test multi-parameter lambda type inference`() {
        val source = """
            let add = {x, y} -> x + y
            add
        """.trimIndent()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)
        }
    }

    @Test
    fun `test multi-parameter lambda with explicit types`() {
        val source = """
            let add: {x: Int, y: Int} -> Int = {x, y} -> x + y
            add
        """.trimIndent()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)
        }
    }

    @Test
    fun `test multi-parameter lambda in closure`() {
        val source = """
            let outer = {a, b} -> do
                let inner = {x, y} -> a + b + x + y
                inner
            let result = outer { a = 1, b = 2 }
            result
        """.trimIndent()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)
        }
    }

    @Test
    fun `test multi-parameter lambda calling another multi-parameter lambda`() {
        val source = """
            let add = {x, y} -> x + y
            let apply = {f, a, b} -> f {x = a, y = b}
            apply { f = add, a = 1, b = 2 }
        """.trimIndent()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)
        }
    }

    @Test
    fun `test multi-parameter lambda with record literal body`() {
        val source = """
            let makePoint = {x, y} -> {x = x, y = y}
            makePoint
        """.trimIndent()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)
        }
    }

    @Test
    fun `test multi-parameter lambda with function call body`() {
        val source = """
            let double = {n} -> n * 2
            let addAndDouble = {x, y} -> double {n = x + y}
            addAndDouble
        """.trimIndent()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)
        }
    }

    @Test
    fun `test nested multi-parameter lambdas`() {
        val source = """
            let outer = {a, b} -> {c, d} -> {e, f} -> a + b + c + d + e + f
            outer
        """.trimIndent()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)
        }
    }

    @Test
    fun `test multi-parameter lambda with captured monomorphic function`() {
        // This test specifically covers the bug we fixed: closure capturing
        // monomorphic function names correctly
        val source = """
            let helper = {x} -> x + 1
            let wrapper = {a, b} -> helper {x = a} + helper {x = b}
            wrapper
        """.trimIndent()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)
        }
    }

    @Test
    fun `test multi-parameter lambda with three parameters`() {
        val source = """
            let add3 = {x, y, z} -> x + y + z
            add3 { x = 1, y = 2, z = 3 }
        """.trimIndent()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)
        }
    }

    @Test
    fun `test multi-parameter lambda with four parameters`() {
        val source = """
            let add4 = {a, b, c, d} -> a + b + c + d
            add4 { a = 1, b = 2, c = 3, d = 4 }
        """.trimIndent()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)
        }
    }

    @Test
    fun `test multi-parameter lambda with mixed types`() {
        val source = """
            use std::int::{to_string}
            let format = {name, age} -> "$(name) is $(to_string {n = age}) years old"
            format { name = "Alice", age = 30 }
        """.trimIndent()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)
        }
    }

    @Test
    fun `test multi-parameter lambda partial application`() {
        val source = """
            let concat = {xs, ys} -> xs
            let partial = concat { xs = [] }
            partial
        """.trimIndent()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)
        }
    }

    @Test
    fun `test multi-parameter lambda with polymorphic types`() {
        val source = """
            let first = {x, y} -> x
            let intResult = first { x = 1, y = 2 }
            let strResult = first { x = "a", y = "b" }
            intResult
        """.trimIndent()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)
        }
    }

    @Test
    fun `test multi-parameter lambda in do expression`() {
        val source = """
            do
                let add = {x, y} -> x + y
                let result = add { x = 1, y = 2 }
                result
        """.trimIndent()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)
        }
    }

    @Test
    fun `test multi-parameter lambda as record field`() {
        val source = """
            let ops = {
                add = {x, y} -> x + y,
                sub = {x, y} -> x - y
            }
            ops.add { x = 5, y = 3 }
        """.trimIndent()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)
        }
    }
}
