package com.mezze.interpreter

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for records with seqs - debugging parsing issues.
 * Migrated from V1 to V2 with unboxed values.
 */
class RecordWithSeqDebugTest {

    private val parser = MezzeParser()
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test empty seq`() {
        val code = "[]"
        println("Parsing: $code")
        try {
            val program = parser.parseParsedModule(code)
            println("Parse succeeded")
        } catch (e: Exception) {
            println("Parse failed: ${e.message}")
            throw e
        }
    }

    @Test
    fun `test record with one empty seq`() {
        val code = "let foo = {x = []}"
        println("Parsing: $code")
        try {
            val program = parser.parseParsedModule(code)
            println("Parse succeeded")
        } catch (e: Exception) {
            println("Parse failed: ${e.message}")
            throw e
        }
    }

    @Test
    fun `test record with two empty seqs`() {
        val code = "let foo = {x = [], y = []}"
        println("Parsing: $code")
        try {
            val program = parser.parseParsedModule(code)
            println("Parse succeeded")
        } catch (e: Exception) {
            println("Parse failed: ${e.message}")
            throw e
        }
    }

    @Test
    fun `test just record expression with two empty seqs`() {
        val code = "{x = [], y = []}"
        println("Parsing: $code")
        try {
            val program = parser.parseParsedModule(code)
            println("Parse succeeded")
        } catch (e: Exception) {
            println("Parse failed: ${e.message}")
            throw e
        }
    }
}
