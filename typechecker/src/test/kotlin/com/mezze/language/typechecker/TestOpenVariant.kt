package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test

class TestOpenVariant {
    @Test
    fun `test parse open variant`() {
        val code = """
            type Color = |Red, Blue, Green, ... |
            Red
        """.trimIndent()

        println("Attempting to parse: $code")
        val parser = MezzeParser()
        try {
            val parsedModule = parser.parseParsedModule(code, "test.mz")
            println("SUCCESS: Parsed open variant")
            println("Program: $parsedModule")
        } catch (e: Exception) {
            println("FAILED: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
}
