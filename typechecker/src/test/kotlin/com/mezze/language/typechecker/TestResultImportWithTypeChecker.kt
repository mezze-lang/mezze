package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Test that TypeChecker is a drop-in replacement for TypeChecker.
 * This is a copy of TestResultImport but using TypeChecker instead.
 */
class TestResultImportWithTypeChecker {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker  // Changed from TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()  // Changed from TypeChecker
        typeChecker = TypeChecker()  // Changed from TypeChecker
        TestUtils.setupProjectRoot()
    }

    @Test
    fun `test import result module only`() {
        val source = """
            use std::result
        """.trimIndent()

        println("Testing import only (with TypeChecker)")

        try {
            val program = parser.parse(source)
            val typed = typeChecker.typeCheck(program)
            assertNotNull(typed)
            println("SUCCESS: Import succeeded with TypeChecker!")
        } catch (e: Exception) {
            println("FAILED: ${e.message}")
            if (e.message != null && e.message!!.length < 1000) {
                println("Full error: $e")
            } else {
                println("Error too long, first 500 chars:")
                println(e.message?.take(500))
            }
            throw e
        }
    }
}
