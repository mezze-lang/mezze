package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test

class TestOpenVariant {
    @Test
    fun `test parse open variant`() {
        val code = """
            type Color = |Red, Blue, Green, ... |
            Red
        """.trimIndent()

        println("Attempting to parse: $code")
        val parser = FunkParser()
        try {
            val program = parser.parse(code)
            println("SUCCESS: Parsed open variant")
            println("Program: $program")
        } catch (e: Exception) {
            println("FAILED: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
}
