package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RecordWithArrayDebugTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    @Test
    fun `test empty array`() {
        val code = "[]"
        println("Parsing: $code")
        try {
            val program = parser.parse(code)
            println("✓ Parse succeeded")
        } catch (e: Exception) {
            println("✗ Parse failed: ${e.message}")
            throw e
        }
    }

    @Test
    fun `test record with one empty array`() {
        val code = "let foo = {x = []}"
        println("Parsing: $code")
        try {
            val program = parser.parse(code)
            println("✓ Parse succeeded")
        } catch (e: Exception) {
            println("✗ Parse failed: ${e.message}")
            throw e
        }
    }

    @Test
    fun `test record with two empty arrays`() {
        val code = "let foo = {x = [], y = []}"
        println("Parsing: $code")
        try {
            val program = parser.parse(code)
            println("✓ Parse succeeded")
        } catch (e: Exception) {
            println("✗ Parse failed: ${e.message}")
            throw e
        }
    }

    @Test
    fun `test just record expression with two empty arrays`() {
        val code = "{x = [], y = []}"
        println("Parsing: $code")
        try {
            val program = parser.parse(code)
            println("✓ Parse succeeded")
        } catch (e: Exception) {
            println("✗ Parse failed: ${e.message}")
            throw e
        }
    }
}
