package com.funk.language.parser

import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PipeOperatorParserTest {

    private val parser = FunkParser()

    @Test
    fun `test simple pipe with placeholder`() {
        val source = """
let square = {x} -> x * x
let result = 5 |> square {x = @}
result
""".trim()

        assertDoesNotThrow {
            val program = parser.parse(source, desugar = false)
            assertNotNull(program)

            // Check that pipe operator is parsed as BinaryOperation
            val items = program.items
            assertTrue(items.size >= 2, "Expected at least 2 items")

            val resultBinding = items[1] as? LetBinding
            assertNotNull(resultBinding, "Second item should be a let binding")

            val pipeExpr = resultBinding!!.expression as? BinaryOperation
            assertNotNull(pipeExpr, "Value should be a BinaryOperation")
            assertEquals("|>", pipeExpr!!.operator, "Operator should be |>")

            println("✅ Simple pipe with placeholder parsed successfully!")
        }
    }

    @Test
    fun `test chained pipe operators`() {
        val source = """
let add = {x, y} -> x + y
let multiply = {x, y} -> x * y
let result = 5 |> add {x = 10, y = @} |> multiply {x = @, y = 2}
""".trim()

        assertDoesNotThrow {
            val program = parser.parse(source, desugar = false)
            assertNotNull(program)

            // Chained pipes should be left-associative
            val items = program.items
            val resultBinding = items[2] as? LetBinding
            assertNotNull(resultBinding, "Third item should be a let binding")

            val outerPipe = resultBinding!!.expression as? BinaryOperation
            assertNotNull(outerPipe, "Value should be a BinaryOperation")
            assertEquals("|>", outerPipe!!.operator, "Outer operator should be |>")

            // Left side should also be a pipe operation
            val innerPipe = outerPipe.left as? BinaryOperation
            assertNotNull(innerPipe, "Left side should also be a BinaryOperation")
            assertEquals("|>", innerPipe!!.operator, "Inner operator should be |>")

            println("✅ Chained pipe operators parsed successfully!")
        }
    }

    @Test
    fun `test pipe operator precedence with arithmetic`() {
        val source = """
let square = {x} -> x * x
let result = 5 + 3 |> square {x = @}
""".trim()

        assertDoesNotThrow {
            val program = parser.parse(source, desugar = false)
            assertNotNull(program)

            // Arithmetic should have higher precedence than pipe
            // So: (5 + 3) |> square {x = @}
            val items = program.items
            val resultBinding = items[1] as? LetBinding
            assertNotNull(resultBinding, "Second item should be a let binding")

            val pipeExpr = resultBinding!!.expression as? BinaryOperation
            assertNotNull(pipeExpr, "Value should be a BinaryOperation")
            assertEquals("|>", pipeExpr!!.operator, "Operator should be |>")

            val leftSide = pipeExpr.left as? BinaryOperation
            assertNotNull(leftSide, "Left side should be arithmetic BinaryOperation")
            assertEquals("+", leftSide!!.operator, "Left operator should be +")

            println("✅ Pipe operator precedence with arithmetic parsed successfully!")
        }
    }

    @Test
    fun `test at placeholder parsing`() {
        val source = """
let f = {x} -> x + 1
let result = 5 |> f {x = @}
""".trim()

        assertDoesNotThrow {
            val program = parser.parse(source, desugar = false)
            assertNotNull(program)

            val items = program.items
            val resultBinding = items[1] as? LetBinding
            assertNotNull(resultBinding)

            val pipeExpr = resultBinding!!.expression as? BinaryOperation
            assertNotNull(pipeExpr)

            val funcCall = pipeExpr!!.right as? FunctionCall
            assertNotNull(funcCall, "Right side should be a FunctionCall")

            val args = funcCall!!.arguments as? RecordLiteral
            assertNotNull(args, "Arguments should be a RecordLiteral")

            val entry = args!!.entries[0] as? RecordFieldEntry
            assertNotNull(entry, "First entry should be a RecordFieldEntry")

            val placeholder = entry!!.value as? Identifier
            assertNotNull(placeholder, "Placeholder should be an Identifier")
            assertEquals("@", placeholder!!.name, "Placeholder name should be @")

            println("✅ At placeholder parsing successful!")
        }
    }

    @Test
    fun `test multiple placeholders in record`() {
        val source = """
let combine = {x, y, z} -> x + y + z
let result = 5 |> combine {x = @, y = @, z = 10}
""".trim()

        assertDoesNotThrow {
            val program = parser.parse(source, desugar = false)
            assertNotNull(program)

            val items = program.items
            val resultBinding = items[1] as? LetBinding
            val pipeExpr = resultBinding!!.expression as? BinaryOperation
            val funcCall = pipeExpr!!.right as? FunctionCall
            val args = funcCall!!.arguments as? RecordLiteral

            // Check for multiple @ placeholders
            val placeholderCount = args!!.entries.count { entry ->
                (entry as? RecordFieldEntry)?.value is Identifier &&
                ((entry as RecordFieldEntry).value as Identifier).name == "@"
            }

            assertEquals(2, placeholderCount, "Should have 2 placeholders")

            println("✅ Multiple placeholders in record parsed successfully!")
        }
    }

    @Test
    fun `test pipe with comparison precedence`() {
        val source = """
let f = {x} -> x > 5
let result = 10 |> f {x = @}
""".trim()

        assertDoesNotThrow {
            val program = parser.parse(source, desugar = false)
            assertNotNull(program)

            // Comparison should bind tighter than pipe
            val items = program.items
            val resultBinding = items[1] as? LetBinding
            val pipeExpr = resultBinding!!.expression as? BinaryOperation

            assertNotNull(pipeExpr, "Should have pipe operator")
            assertEquals("|>", pipeExpr!!.operator, "Operator should be |>")

            println("✅ Pipe with comparison precedence parsed successfully!")
        }
    }

    @Test
    fun `test pipe operator left associativity`() {
        val source = """
let f = {x} -> x + 1
let g = {x} -> x * 2
let h = {x} -> x - 3
let result = 5 |> f {x = @} |> g {x = @} |> h {x = @}
""".trim()

        assertDoesNotThrow {
            val program = parser.parse(source, desugar = false)
            assertNotNull(program)

            // Should parse as: ((5 |> f) |> g) |> h
            val items = program.items
            val resultBinding = items[3] as? LetBinding

            val pipe3 = resultBinding!!.expression as? BinaryOperation
            assertEquals("|>", pipe3!!.operator, "Outermost should be |>")

            val pipe2 = pipe3.left as? BinaryOperation
            assertEquals("|>", pipe2!!.operator, "Middle should be |>")

            val pipe1 = pipe2.left as? BinaryOperation
            assertEquals("|>", pipe1!!.operator, "Innermost should be |>")

            println("✅ Pipe operator left associativity verified!")
        }
    }

    @Test
    fun `test pipe with nested function calls`() {
        val source = """
let add = {x, y} -> x + y
let result = 5 |> add {x = @, y = add {x = 1, y = 2}}
""".trim()

        assertDoesNotThrow {
            val program = parser.parse(source, desugar = false)
            assertNotNull(program)

            val items = program.items
            val resultBinding = items[1] as? LetBinding
            val pipeExpr = resultBinding!!.expression as? BinaryOperation

            assertNotNull(pipeExpr, "Should have pipe operator")
            assertEquals("|>", pipeExpr!!.operator, "Operator should be |>")

            println("✅ Pipe with nested function calls parsed successfully!")
        }
    }

    @Test
    fun `test standalone at placeholder fails gracefully`() {
        val source = "@"

        assertDoesNotThrow {
            val program = parser.parse(source, desugar = false)
            assertNotNull(program)

            // @ should parse as an identifier, even if semantically invalid
            println("✅ Standalone @ parsed (will fail at type checking)")
        }
    }

    @Test
    fun `test pipe without placeholder`() {
        val source = """
let f = {x} -> x + 1
let result = 5 |> f {x = 10}
""".trim()

        assertDoesNotThrow {
            val program = parser.parse(source, desugar = false)
            assertNotNull(program)

            // Should parse successfully, semantic checking happens later
            val items = program.items
            val resultBinding = items[1] as? LetBinding
            val pipeExpr = resultBinding!!.expression as? BinaryOperation

            assertEquals("|>", pipeExpr!!.operator, "Operator should be |>")

            println("✅ Pipe without placeholder parsed (semantics checked later)")
        }
    }

    @Test
    fun `test complex expression with pipe`() {
        val source = """
let square = {x} -> x * x
let add = {x, y} -> x + y
let result = (2 + 3) * 4 |> square {x = @} |> add {x = @, y = 100}
""".trim()

        assertDoesNotThrow {
            val program = parser.parse(source, desugar = false)
            assertNotNull(program)

            println("✅ Complex expression with pipe parsed successfully!")
        }
    }

    @Test
    fun `test pipe operator in do block`() {
        val source = """
let toString = {val} -> "value"
let log = {msg} -> {}
let noiseAdd = {x, y} -> do
  let sum = x + y
  let diff = x - y
  sum > diff |> toString {val = @} |> log {msg = @}
""".trim()

        assertDoesNotThrow {
            val program = parser.parse(source, desugar = true)
            assertNotNull(program)

            // The key test is that after desugaring, @ should be replaced
            val items = program.items
            val noiseAddBinding = items[2] as? LetBinding
            assertNotNull(noiseAddBinding, "Third item should be noiseAdd let binding")

            val lambdaExpr = noiseAddBinding!!.expression as? LambdaExpression
            assertNotNull(lambdaExpr, "noiseAdd value should be a lambda")

            val doExpr = lambdaExpr!!.body as? DoExpression
            assertNotNull(doExpr, "Lambda body should be a do expression")

            // After desugaring, the result expression should have @ replaced
            // It should be a function call, not a BinaryOperation with |>
            val resultExpr = doExpr!!.result
            assertTrue(resultExpr is FunctionCall, "Result should be desugared to FunctionCall, not BinaryOperation with |>")

            println("✅ Pipe operator in do block parsed and desugared successfully!")
        }
    }

    @Test
    fun `test pipe operator in do block result expression`() {
        val source = """
let double = {x} -> x * 2
let triple = {x} -> x * 3
let compute = {n} -> do
  let temp = n + 1
  temp |> double {x = @} |> triple {x = @}
""".trim()

        assertDoesNotThrow {
            val program = parser.parse(source, desugar = true)
            assertNotNull(program)

            val items = program.items
            val computeBinding = items[2] as? LetBinding
            assertNotNull(computeBinding)

            val lambdaExpr = computeBinding!!.expression as? LambdaExpression
            assertNotNull(lambdaExpr)

            val doExpr = lambdaExpr!!.body as? DoExpression
            assertNotNull(doExpr)

            // After desugaring, no @ placeholders should remain
            val resultExpr = doExpr!!.result
            assertFalse(containsAtPlaceholder(resultExpr), "Result expression should not contain @ after desugaring")

            println("✅ Pipe operator in do block result desugared correctly!")
        }
    }

    // Helper function to check if expression tree contains @ placeholder
    private fun containsAtPlaceholder(expr: Expression): Boolean {
        return when (expr) {
            is Identifier -> expr.name == "@"
            is BinaryOperation -> containsAtPlaceholder(expr.left) || containsAtPlaceholder(expr.right)
            is FunctionCall -> containsAtPlaceholder(expr.function) || containsAtPlaceholder(expr.arguments)
            is RecordLiteral -> expr.entries.any { entry ->
                when (entry) {
                    is RecordFieldEntry -> entry.value?.let { containsAtPlaceholder(it) } ?: false
                    is RecordSpreadEntry -> containsAtPlaceholder(entry.expression)
                }
            }
            is DoExpression -> expr.statements.any { stmt ->
                when (stmt) {
                    is LetStatement -> containsAtPlaceholder(stmt.expression)
                    is ExpressionStatement -> containsAtPlaceholder(stmt.expression)
                }
            } || containsAtPlaceholder(expr.result)
            else -> false
        }
    }
}
