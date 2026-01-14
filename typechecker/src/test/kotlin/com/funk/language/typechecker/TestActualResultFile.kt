package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull
import java.io.File

class TestActualResultFile {

    private val parser = FunkParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
    }

    @Test
    fun `test typecheck actual result funk file directly`() {
        val resultFile = File("/Users/shriyans/Projects/funk/stdlib/std/result.funk")
        val source = resultFile.readText()

        println("Testing actual result.funk file")
        println("File size: ${source.length} characters")

        try {
            val program = parser.parse(source)
            println("Parsed successfully, ${program.items.size} items")

            val typed = typeChecker.typeCheck(program).program
            assertNotNull(typed)
            println("SUCCESS: Typechecked successfully")
        } catch (e: Exception) {
            println("FAILED: ${e.javaClass.simpleName}")
            val msg = e.message ?: ""
            if (msg.length < 500) {
                println("Error: $msg")
            } else {
                println("Error (first 500 chars): ${msg.take(500)}")
            }
            throw e
        }
    }
}
