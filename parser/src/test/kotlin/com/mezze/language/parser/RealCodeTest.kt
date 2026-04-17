package com.mezze.language.parser

import com.mezze.language.parsedast.*
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
            val module = parser.parseParsedModule(source)
            assertNotNull(module)
            assertEquals(1, module.decls.size)

            val letBinding = module.decls[0] as ParsedLetDecl
            assertEquals("map", letBinding.variableName)

            val lambda = letBinding.value as ParsedLambda
            val body = lambda.body as ParsedMatch

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
            val module = parser.parseParsedModule(source)
            assertNotNull(module)
            println("✅ Nested match with if-else parsed successfully!")
        }
    }

    @Test
    fun `test chained operations`() {
        val source = """
let result = obj.field.method { x = 1 }.value
""".trim()

        assertDoesNotThrow {
            val module = parser.parseParsedModule(source)
            assertNotNull(module)
            println("✅ Chained operations parsed successfully!")
        }
    }
}
