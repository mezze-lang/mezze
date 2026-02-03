package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

/**
 * Test that NewTypeChecker is a drop-in replacement for TypeChecker.
 * This is a copy of TestResultImport but using NewTypeChecker instead.
 */
class TestResultImportWithNewTypeChecker {

    private val parser = FunkParser()
    private lateinit var typeChecker: NewTypeChecker  // Changed from TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        NewTypeChecker.clearCache()  // Changed from TypeChecker
        typeChecker = NewTypeChecker()  // Changed from TypeChecker
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
    }

    @Test
    fun `test import result module only`() {
        val source = """
            use std::result
        """.trimIndent()

        println("Testing import only (with NewTypeChecker)")

        try {
            val program = parser.parse(source)
            val typed = typeChecker.typeCheck(program).program
            assertNotNull(typed)
            println("SUCCESS: Import succeeded with NewTypeChecker!")
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
