package com.mezze.language.parser

import com.mezze.language.parser.internal.Lexer
import org.junit.jupiter.api.Test

class DebugTest {
    @Test
    fun `debug simple let`() {
        val source = "let x = 1"
        println("\n=== Debugging: '$source' ===")

        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        println("Tokens:")
        tokens.forEach { println("  ${it.type}: '${it.text}' at ${it.position}") }

        val parser = MezzeParser()
        try {
            val program = parser.parseParsedModule(source)
            println("✅ Success: $program")
        } catch (e: ParseException) {
            println("❌ Failed: ${e.message}")
            e.printStackTrace()
        }
    }

    @Test
    fun `debug multiline record`() {
        val source = """
let obj = {
  x = 1,
  y = 2
}
""".trim()
        println("\n=== Debugging multiline record ===")
        println("Source: '$source'")

        val lexer = Lexer(source)
        val tokens = lexer.tokenize()
        println("Tokens (${tokens.size}):")
        tokens.forEach { println("  ${it.type}: '${it.text}' at line ${it.line}:${it.column}") }

        val parser = MezzeParser()
        try {
            val program = parser.parseParsedModule(source)
            println("✅ Success: $program")
        } catch (e: ParseException) {
            println("❌ Failed: ${e.message}")
            e.printStackTrace()
        }
    }

    @Test
    fun `debug parser reuse`() {
        val source = """
let obj = {
  x = 1,
  y = 2
}
""".trim()
        println("\n=== Testing parser reuse ===")

        val parser = MezzeParser()

        println("First parse:")
        try {
            val program1 = parser.parseParsedModule(source)
            println("✅ First parse succeeded")
        } catch (e: ParseException) {
            println("❌ First parse failed: ${e.message}")
        }

        println("Second parse (same instance):")
        try {
            val program2 = parser.parseParsedModule(source)
            println("✅ Second parse succeeded")
        } catch (e: ParseException) {
            println("❌ Second parse failed: ${e.message}")
        }
    }

    @Test
    fun `debug lambda with parameter`() {
        val source = "let f = {x} -> x + 1"
        println("\n=== Debugging lambda with parameter ===")
        println("Source: '$source'")

        val parser = MezzeParser()
        try {
            val program = parser.parseParsedModule(source)
            println("✅ Success: $program")
        } catch (e: ParseException) {
            println("❌ Failed: ${e.message}")
            e.printStackTrace()
        }
    }
}
