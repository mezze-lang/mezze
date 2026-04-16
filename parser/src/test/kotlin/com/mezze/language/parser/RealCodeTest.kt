package com.mezze.language.parser

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RealCodeTest {

    private val parser = MezzeParser()

    @Test
    fun `test option map function`() {
        val source = """
let map = { opt, f } ->
    match opt is
        Some { val } -> Some { val = f { val } }
        None -> None
""".trim()

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            assertEquals(1, program.items.size)

            val letBinding = program.items[0] as LetBinding
            assertEquals("map", letBinding.variableName)

            val lambda = letBinding.expression as LambdaExpression
            val body = lambda.body as MatchExpression

            println("✅ Real Mezze code (option map) parsed successfully!")
        }
    }

    @Test
    fun `test nested match and function calls`() {
        val source = """
let result = match x is
    Some { val } -> if val > 0 then val else 0
    None -> defaultValue
""".trim()

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            println("✅ Nested match with if-else parsed successfully!")
        }
    }

    @Test
    fun `test chained operations`() {
        val source = """
let result = obj.field.method { x = 1 }.value
""".trim()

        assertDoesNotThrow {
            val program = parser.parseParsedModule(source)
            assertNotNull(program)
            println("✅ Chained operations parsed successfully!")
        }
    }
}
