package com.mezze.language.parser

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
    private val parser = MezzeParser()

    @Test
    fun `test valid function call with record literal`() {
        val code = """
            let my_func = {x, y} -> x + y
            let z = my_func { x = 5, y = 7 }
            z
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test invalid function call without braces should fail`() {
        val code = """
            let my_func = {x} -> x + 1
            let z = my_func x
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
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
            parser.parseParsedModule(code)
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test valid chained function calls`() {
        val code = """
            let f = {x} -> {y} -> x + y
            let z = f { x = 5 }
            let w = z { y = 7 }
            w
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test member access followed by identifier on new line is valid`() {
        // Complex expression followed by identifier on new line should not error
        val code = """
            ability Show for 'a where {
              show: {} -> String
            }

            let x = Show.show
            let y = 5
            y
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call in if expression is valid`() {
        val code = """
            let f = {x} -> x > 0
            let result = if f { x = 5 } then 1 else 0
            result
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call with multiple named arguments is valid`() {
        val code = """
            let calc = {a, b, c} -> a + b * c
            let result = calc { a = 1, b = 2, c = 3 }
            result
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function returning function called with braces is valid`() {
        val code = """
            let makeAdder = {n} -> {x} -> x + n
            let add10 = makeAdder { n = 10 }
            let result = add10 { x = 5 }
            result
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test pipe operator with function call is valid`() {
        val code = """
            let double = {x} -> x * 2
            let result = 5 |> double { x = @ }
            result
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    // ========== ADDITIONAL INVALID SYNTAX TESTS ==========

    @Test
    fun `test Haskell style application should fail`() {
        val code = """
            let add = {x, y} -> x + y
            let result = add x y
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test multiple identifiers in sequence should fail`() {
        val code = """
            let a = {x} -> x
            let b = 5
            let c = a b
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
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
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("myFunction myArg") == true)
    }

    // ========== COMPREHENSIVE VALID SYNTAX TESTS ==========

    @Test
    fun `test function call with string argument`() {
        val code = """
            let greet = {name} -> "Hello"
            let result = greet { name = "World" }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call with boolean argument`() {
        val code = """
            let choose = {flag, a, b} -> if flag then a else b
            let result = choose { flag = true, a = 1, b = 2 }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call with record argument`() {
        val code = """
            let getX = {point} -> point.x
            let p = { x = 10, y = 20 }
            let result = getX { point = p }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call with lambda argument`() {
        val code = """
            let apply = {f, x} -> f { x = x }
            let double = {x} -> x * 2
            let result = apply { f = double, x = 5 }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call with list argument`() {
        val code = """
            let getFirst = {xs} -> xs
            let result = getFirst { xs = [1, 2, 3] }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test deeply nested function calls`() {
        val code = """
            let f = {x} -> x + 1
            let g = {x} -> x * 2
            let h = {x} -> x - 3
            let result = f { x = g { x = h { x = 10 } } }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call in let binding`() {
        val code = """
            let double = {x} -> x * 2
            let result = double { x = 5 }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call as final expression`() {
        val code = """
            let add = {x, y} -> x + y
            add { x = 1, y = 2 }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call with arithmetic expressions in arguments`() {
        val code = """
            let add = {x, y} -> x + y
            let result = add { x = 2 * 3, y = 4 + 1 }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call with comparison in arguments`() {
        val code = """
            let choose = {cond, a, b} -> if cond then a else b
            let result = choose { cond = 5 > 3, a = 1, b = 0 }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test multiple function calls on same line`() {
        val code = """
            let f = {x} -> x + 1
            let g = {x} -> x * 2
            let a = f { x = 1 }
            let b = g { x = 2 }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call with field access in argument`() {
        val code = """
            let double = {x} -> x * 2
            let record = { value = 5 }
            let result = double { x = record.value }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call with negation in argument`() {
        val code = """
            let negate = {x} -> 0 - x
            let result = negate { x = 5 }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call in record value`() {
        val code = """
            let double = {x} -> x * 2
            let record = { value = double { x = 5 } }
            record
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call with spread record in value`() {
        val code = """
            let getX = {r} -> r.x
            let base = { x = 1, y = 2 }
            let updated = { ...base, x = 10 }
            let result = getX { r = updated }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    // ========== COMPREHENSIVE INVALID SYNTAX TESTS ==========

    @Test
    fun `test single arg function call without braces`() {
        val code = """
            let double = {x} -> x * 2
            let result = double x
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
    }

    @Test
    fun `test function call with variable without braces`() {
        val code = """
            let f = {x} -> x
            let arg = 5
            let result = f arg
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
    }

    @Test
    fun `test curried function call without braces`() {
        val code = """
            let add = {x} -> {y} -> x + y
            let partial = add x
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
    }

    @Test
    fun `test ML style function application should fail`() {
        val code = """
            let f = {x} -> x + 1
            let result = f x
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
    }

    @Test
    fun `test three identifiers in sequence should fail`() {
        val code = """
            let f = {x, y} -> x + y
            let a = 1
            let b = 2
            let result = f a b
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
    }

    @Test
    fun `test underscore identifier without braces should fail`() {
        val code = """
            let my_func = {x} -> x
            let _arg = 5
            let result = my_func _arg
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
    }

    @Test
    fun `test camelCase identifier without braces should fail`() {
        val code = """
            let myFunc = {x} -> x
            let myArg = 5
            let result = myFunc myArg
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
    }

    @Test
    fun `test error message suggests braces syntax`() {
        val code = """
            let func = {x} -> x
            let arg = 5
            let result = func arg
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("func { ... }") == true)
    }

    @Test
    fun `test error mentions named arguments`() {
        val code = """
            let func = {x} -> x
            let arg = 5
            let result = func arg
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("named arguments") == true)
    }

    // ========== EDGE CASES ==========

    @Test
    fun `test identifier followed by newline and identifier is valid`() {
        val code = """
            let x = 5
            let y = 10
            x
            y
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test expression followed by record literal is function call`() {
        // This is valid: identifier followed by { is a function call
        val code = """
            let f = {x} -> x + 1
            let result = f { x = 5 }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test let expression on same line`() {
        // Identifiers in let binding should not trigger the check
        val code = """
            let x = 5
            let y = x
            y
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test binary operator between identifiers is valid`() {
        val code = """
            let x = 5
            let y = 10
            let result = x + y
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test identifier in if condition is valid`() {
        val code = """
            let flag = true
            let result = if flag then 1 else 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function in let followed by call is valid`() {
        val code = """
            let result = ({x} -> x + 1) { x = 5 }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test ability definition with function call syntax in body`() {
        val code = """
            ability Num for 'a where {
                add: {x: 'a, y: 'a} -> 'a
            }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test recursive function call`() {
        val code = """
            let factorial = {n} -> if n < 2 then 1 else n * factorial { n = n - 1 }
            let result = factorial { n = 5 }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call result used in arithmetic`() {
        val code = """
            let double = {x} -> x * 2
            let result = double { x = 5 } + 10
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call in list`() {
        val code = """
            let f = {x} -> x + 1
            let xs = [f { x = 1 }, f { x = 2 }, f { x = 3 }]
            xs
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `test function call with shorthand field`() {
        val code = """
            let f = {x, y} -> x + y
            let x = 5
            let y = 10
            let result = f { x, y }
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    // ========== LITERAL ARGUMENT SYNTAX ERRORS ==========

    @Test
    fun `test function call with string literal should fail`() {
        val code = """
            let f = {x} -> x
            let result = f "hello"
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
        assertTrue(exception.message?.contains("string") == true)
    }

    @Test
    fun `test function call with integer literal should fail`() {
        val code = """
            let f = {x} -> x
            let result = f 42
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
        assertTrue(exception.message?.contains("number") == true)
    }

    @Test
    fun `test function call with float literal should fail`() {
        val code = """
            let f = {x} -> x
            let result = f 3.14
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
        assertTrue(exception.message?.contains("number") == true)
    }

    @Test
    fun `test function call with fraction literal should fail`() {
        val code = """
            let f = {x} -> x
            let result = f 1//2
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
        assertTrue(exception.message?.contains("fraction") == true)
    }

    @Test
    fun `test function call with boolean constructor True should fail`() {
        // True/False are now variant constructors (uppercase identifiers), not boolean literals
        // Function calls still require {} syntax
        val code = """
            let f = {x} -> x
            let result = f True
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
        assertTrue(exception.message?.contains("True") == true)
    }

    @Test
    fun `test function call with boolean constructor False should fail`() {
        // True/False are now variant constructors (uppercase identifiers), not boolean literals
        // Function calls still require {} syntax
        val code = """
            let f = {x} -> x
            let result = f False
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
        assertTrue(exception.message?.contains("False") == true)
    }

    @Test
    fun `test member access function call with string literal should fail`() {
        val code = """
            ability Parse for 'a where {
              decode: {} -> 'a
            }

            let result = Parse.decode "1233"
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
        assertTrue(exception.message?.contains("Parse.decode") == true)
        assertTrue(exception.message?.contains("string") == true)
    }

    @Test
    fun `test member access function call with number literal should fail`() {
        val code = """
            use std::int

            let result = int::to_string 42
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
        assertTrue(exception.message?.contains("number") == true)
    }

    @Test
    fun `test error message for literal includes field placeholder`() {
        val code = """
            let f = {x} -> x
            let result = f "test"
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        assertTrue(exception.message?.contains("<field>") == true)
    }

    @Test
    fun `test multiple literal arguments should fail on first`() {
        val code = """
            let f = {x, y} -> x + y
            let result = f 1 2
        """.trimIndent()

        val exception = assertThrows<ParseException> {
            parser.parseParsedModule(code)
        }
        // Should fail on the first literal argument
        assertTrue(exception.message?.contains("Function calls must use {} syntax") == true)
    }
}
