package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull
import java.io.File

class TestActualResultFile {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
    }

    @Test
    fun `test typecheck actual result mezze file directly`() {
        val resultFile = TestUtils.findProjectRoot().resolve("stdlib/std/result.mz").toFile()
        val source = resultFile.readText()

        println("Testing actual result.mz file")
        println("File size: ${source.length} characters")

        try {
            val program = parser.parse(source)
            println("Parsed successfully, ${program.items.size} items")

            val result = typeChecker.checkProgram(program, resultFile.toPath().parent, "std::result")
            val typed = when (result) {
                is TypeChecker.TypeCheckResult.Success -> result.program
                is TypeChecker.TypeCheckResult.Failure -> throw RuntimeException(result.errors.joinToString("\n") { e -> e.message })
            }
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
