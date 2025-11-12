package com.funk.language.parser

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

        val parser = FunkParser()
        try {
            val program = parser.parse(source)
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

        val parser = FunkParser()
        try {
            val program = parser.parse(source)
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

        val parser = FunkParser()

        println("First parse:")
        try {
            val program1 = parser.parse(source)
            println("✅ First parse succeeded")
        } catch (e: ParseException) {
            println("❌ First parse failed: ${e.message}")
        }

        println("Second parse (same instance):")
        try {
            val program2 = parser.parse(source)
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

        val parser = FunkParser()
        try {
            val program = parser.parse(source)
            println("✅ Success: $program")
        } catch (e: ParseException) {
            println("❌ Failed: ${e.message}")
            e.printStackTrace()
        }
    }
}
