package com.funk.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for Bug #10: Function calls must use {} syntax
 * - `func { x = 1 }` is valid
 * - `func x` is invalid (must use braces)
 */
class FunctionCallSyntaxTest {
    private val parser = FunkParser()

    @Test
    fun `test valid function call with record literal`() {
        val code = """
            let my_func = {x, y} -> x + y
            let z = my_func { x = 5, y = 7 }
            z
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `test invalid function call without braces should fail`() {
        val code = """
            let my_func = {x} -> x + 1
            let z = my_func x
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parse(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
    }

    @Test
    fun `test invalid function call identifier followed by identifier`() {
        val code = """
            let f = {x} -> x
            let y = 5
            let z = f y
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parse(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
        assertTrue(exception.message?.contains("f y") == true)
    }

    @Test
    fun `test valid function call with empty record`() {
        val code = """
            let my_func = {} -> 42
            let z = my_func {}
            z
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `test valid chained function calls`() {
        val code = """
            let f = {x} -> {y} -> x + y
            let z = f { x = 5 }
            let w = z { y = 7 }
            w
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `test separate statements on different lines are valid`() {
        // This should parse as two separate statements, not a function call
        val code = """
            let x = 5
            let y = 7
            x
            y
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `test member access followed by identifier on new line is valid`() {
        // Complex expression followed by identifier on new line should not error
        val code = """
            ability Show 'a where {
              show: {val: 'a} -> String
            }

            let x = Show.show
            let y = 5
            y
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    // ========== ADDITIONAL VALID SYNTAX TESTS ==========

    @Test
    fun `test nested function calls are valid`() {
        val code = """
            let double = {x} -> x * 2
            let add = {x, y} -> x + y
            let result = add { x = double { x = 3 }, y = 4 }
            result
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `test function call in if expression is valid`() {
        val code = """
            let f = {x} -> x > 0
            let result = if f { x = 5 } then 1 else 0
            result
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `test function call with multiple named arguments is valid`() {
        val code = """
            let calc = {a, b, c} -> a + b * c
            let result = calc { a = 1, b = 2, c = 3 }
            result
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `test function returning function called with braces is valid`() {
        val code = """
            let makeAdder = {n} -> {x} -> x + n
            let add10 = makeAdder { n = 10 }
            let result = add10 { x = 5 }
            result
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `test pipe operator with function call is valid`() {
        val code = """
            let double = {x} -> x * 2
            let result = 5 |> double { x = @ }
            result
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    // ========== ADDITIONAL INVALID SYNTAX TESTS ==========

    @Test
    fun `test Haskell style application should fail`() {
        val code = """
            let add = {x, y} -> x + y
            let result = add x y
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parse(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
    }

    @Test
    fun `test function name followed by number literal should parse as separate`() {
        // This parses as two separate expressions: f and 5
        // Since 5 is not an identifier, our check doesn't trigger
        val code = """
            let f = {x} -> x
            f
            5
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `test multiple identifiers in sequence should fail`() {
        val code = """
            let a = {x} -> x
            let b = 5
            let c = a b
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parse(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
        assertTrue(exception.message?.contains("a b") == true)
    }

    @Test
    fun `test error message includes function and argument names`() {
        val code = """
            let myFunction = {x} -> x
            let myArg = 5
            let result = myFunction myArg
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parse(code)
        }
        assertTrue(exception.message?.contains("myFunction myArg") == true)
    }
}
