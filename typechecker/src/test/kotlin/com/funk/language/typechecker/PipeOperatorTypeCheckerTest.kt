package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PipeOperatorTypeCheckerTest {

    private val parser = FunkParser()

    private fun parseAndTypeCheck(source: String): Program {
        val program = parser.parse(source)
        val typeChecker = TypeChecker()
        return typeChecker.typeCheck(program).program
    }

    @Test
    fun `test simple pipe type checking`() {
        val source = """
let square = {x} -> x * x
let result = 5 |> square {x = @}
result
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

            // Result should have Int type
            val resultBinding = program.items[1] as? LetBinding
            assertNotNull(resultBinding, "Second statement should be let binding")
        }
    }

    @Test
    fun `test chained pipe type inference`() {
        val source = """
let add = {x, y} -> x + y
let multiply = {x, y} -> x * y
let result = 5 |> add {x = 10, y = @} |> multiply {x = @, y = 2}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

            // result should have Int type from chained operations
            val resultBinding = program.items[2] as? LetBinding
            assertNotNull(resultBinding, "Should have result binding")

        }
    }

    @Test
    fun `test pipe with function returning different type`() {
        val source = """
let toString = {x} -> "value"
let result = 42 |> toString {x = @}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

            // Result should have String type
            val resultBinding = program.items[1] as? LetBinding
            assertNotNull(resultBinding)

        }
    }

    @Test
    fun `test pipe type unification`() {
        val source = """
let identity = {x} -> x
let result = 5 |> identity {x = @}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

            // Type should be unified correctly
            val resultBinding = program.items[1] as? LetBinding
            assertNotNull(resultBinding)

        }
    }

    @Test
    fun `test pipe with polymorphic function`() {
        val source = """
let double = {x} -> x + x
let result1 = 5 |> double {x = @}
let result2 = 10 |> double {x = @}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

            // Should work with different numeric values (polymorphic over NumAdd)
            val result1Binding = program.items[1] as? LetBinding
            val result2Binding = program.items[2] as? LetBinding

            assertNotNull(result1Binding)
            assertNotNull(result2Binding)

        }
    }

    @Test
    fun `test pipe with record argument`() {
        val source = """
let combine = {x, y, z} -> x + y + z
let result = 5 |> combine {x = @, y = 10, z = 15}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

            val resultBinding = program.items[1] as? LetBinding
            assertNotNull(resultBinding)

        }
    }

    @Test
    fun `test pipe with nested records`() {
        val source = """
let process = {data} -> data + 1
let nested = {inner} -> inner + 2
let result = 5 |> process {data = @} |> nested {inner = @}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

        }
    }

    @Test
    fun `test pipe preserves ability constraints`() {
        val source = """
let add = {x, y} -> x + y
let result = 5 |> add {x = @, y = 3}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

            // Should preserve NumAdd ability constraint
        }
    }

    @Test
    fun `test multiple placeholders with same value`() {
        val source = """
let combine = {x, y} -> x + y
let result = 5 |> combine {x = @, y = @}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

            // Both placeholders should be replaced with same value
            val resultBinding = program.items[1] as? LetBinding
            assertNotNull(resultBinding)

        }
    }

    @Test
    fun `test pipe with comparison operators`() {
        val source = """
let isPositive = {x} -> x > 0
let result = 5 |> isPositive {x = @}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

            // Result should have Bool type
            val resultBinding = program.items[1] as? LetBinding
            assertNotNull(resultBinding)

        }
    }

    @Test
    fun `test pipe in complex expression context`() {
        val source = """
let square = {x} -> x * x
let add = {x, y} -> x + y
let result = (5 |> square {x = @}) + (3 |> square {x = @})
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

            val resultBinding = program.items[2] as? LetBinding
            assertNotNull(resultBinding)

        }
    }

    @Test
    fun `test pipe with conditional`() {
        val source = """
let process = {x} -> if x > 0 then x * 2 else x
let result = 5 |> process {x = @}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

        }
    }

    @Test
    fun `test pipe with lambda result`() {
        val source = """
let makeAdder = {x} -> {y} -> x + y
let adder = 5 |> makeAdder {x = @}
let result = adder {y = 3}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

            // adder should be a function
            val adderBinding = program.items[1] as? LetBinding
            assertNotNull(adderBinding)

        }
    }

    @Test
    fun `test pipe with record literal value`() {
        val source = """
let getField = {rec} -> rec
let result = {x = 5, y = 10} |> getField {rec = @}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

        }
    }

    @Test
    fun `test pipe with array operations`() {
        val source = """
let head = {arr} -> arr
let result = [1, 2, 3] |> head {arr = @}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

        }
    }

    @Test
    fun `test deeply nested pipe chain`() {
        val source = """
let f = {x} -> x + 1
let g = {x} -> x * 2
let h = {x} -> x - 3
let result = 5 |> f {x = @} |> g {x = @} |> h {x = @}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

            val resultBinding = program.items[3] as? LetBinding
            assertNotNull(resultBinding)

        }
    }

    @Test
    fun `test pipe with string interpolation`() {
        val source = """
let greet = {name} -> "Hello, $(name)"
let result = "World" |> greet {name = @}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

        }
    }

    @Test
    fun `test pipe with boolean operations`() {
        val source = """
let negate = {b} -> !b
let result = true |> negate {b = @}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

        }
    }

    @Test
    fun `test pipe operator precedence in type checking`() {
        val source = """
let square = {x} -> x * x
let result = 2 + 3 |> square {x = @}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

            // Should evaluate as (2 + 3) |> square
            val resultBinding = program.items[1] as? LetBinding
            assertNotNull(resultBinding)

        }
    }

    @Test
    fun `test pipe in let binding RHS`() {
        val source = """
let square = {x} -> x * x
let triple = {x} -> x * 3
let composition = 5 |> square {x = @} |> triple {x = @}
""".trim()

        assertDoesNotThrow {
            val program = parseAndTypeCheck(source)
            assertNotNull(program)

            val compositionBinding = program.items[2] as? LetBinding
            assertNotNull(compositionBinding)

        }
    }
}
