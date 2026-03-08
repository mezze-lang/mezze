package com.mezze.interpreter.v2

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for records with arrays - debugging parsing issues.
 * Migrated from V1 to V2 with unboxed values.
 */
class RecordWithArrayDebugTest {

    private val parser = MezzeParser()

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test empty array`() {
        val code = "[]"
        println("Parsing: $code")
        try {
            val program = parser.parse(code)
            println("Parse succeeded")
        } catch (e: Exception) {
            println("Parse failed: ${e.message}")
            throw e
        }
    }

    @Test
    fun `test record with one empty array`() {
        val code = "let foo = {x = []}"
        println("Parsing: $code")
        try {
            val program = parser.parse(code)
            println("Parse succeeded")
        } catch (e: Exception) {
            println("Parse failed: ${e.message}")
            throw e
        }
    }

    @Test
    fun `test record with two empty arrays`() {
        val code = "let foo = {x = [], y = []}"
        println("Parsing: $code")
        try {
            val program = parser.parse(code)
            println("Parse succeeded")
        } catch (e: Exception) {
            println("Parse failed: ${e.message}")
            throw e
        }
    }

    @Test
    fun `test just record expression with two empty arrays`() {
        val code = "{x = [], y = []}"
        println("Parsing: $code")
        try {
            val program = parser.parse(code)
            println("Parse succeeded")
        } catch (e: Exception) {
            println("Parse failed: ${e.message}")
            throw e
        }
    }
}
