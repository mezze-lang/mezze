package com.mezze.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BasicParsingTest {

    private val parser = MezzeParser()

    @Test
    fun `test basic multiline record`() {
        val source = """
let obj = {
  x = 1,
  y = 2
}
""".trim()

        assertDoesNotThrow {
            val program = parser.parse(source)
            assertNotNull(program)
            println("✅ Multiline record parsed successfully!")
        }
    }

    @Test
    fun `test inline record in let`() {
        val source = "let obj = {a = 1, b = 2}"

        assertDoesNotThrow {
            val program = parser.parse(source)
            assertNotNull(program)
            println("✅ Inline record in let parsed successfully!")
        }
    }

    @Test
    fun `test lambda expression in let`() {
        val source = "let f = {x} -> x + 1"

        assertDoesNotThrow {
            val program = parser.parse(source)
            assertNotNull(program)
            println("✅ Lambda expression in let parsed successfully!")
        }
    }

    @Test
    fun `test record with lambda field in let`() {
        val source = """
let obj = {
  handler = {x} -> x + 1
}
""".trim()

        assertDoesNotThrow {
            val program = parser.parse(source)
            assertNotNull(program)
            println("✅ Record with lambda field in let parsed successfully!")
        }
    }

    @Test
    fun `test empty lambda in let`() {
        val source = "let f = { } -> 42"

        assertDoesNotThrow {
            val program = parser.parse(source)
            assertNotNull(program)
            println("✅ Empty lambda in let parsed successfully!")
        }
    }
}
