package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull

class TestResultImport {

    private val parser = FunkParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        System.setProperty("funk.project.root", "/Users/shriyans/Projects/funk")
    }

    @Test
    fun `test import result module only`() {
        val source = """
            use std::result
        """.trimIndent()

        println("Testing import only")

        try {
            val program = parser.parse(source)
            val typed = typeChecker.typeCheck(program).program
            assertNotNull(typed)
            println("SUCCESS: Import succeeded")
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
