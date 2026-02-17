package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull
import java.io.File

class TestFullResultStep {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        TestUtils.setupProjectRoot()
    }

    @Test
    fun `test parsing full result module functions one by one`() {
        val resultFile = TestUtils.findProjectRoot().resolve("stdlib/std/result.mz").toFile()
        val lines = resultFile.readLines()

        val functions = mutableListOf<String>()
        var currentFunc = mutableListOf<String>()
        var inFunction = false

        for (line in lines) {
            if (line.trim().startsWith("pub let ")) {
                // Save previous function if we have one
                if (currentFunc.isNotEmpty()) {
                    functions.add(currentFunc.joinToString("\n"))
                }
                currentFunc = mutableListOf(line)
                inFunction = true
            } else if (inFunction) {
                if (line.trim().isEmpty()) {
                    // Empty line marks end of function
                    if (currentFunc.isNotEmpty()) {
                        functions.add(currentFunc.joinToString("\n"))
                        currentFunc = mutableListOf()
                        inFunction = false
                    }
                } else {
                    // Non-empty line - add to current function
                    currentFunc.add(line)
                }
            }
        }
        // Don't forget the last function if file doesn't end with blank line
        if (currentFunc.isNotEmpty()) {
            functions.add(currentFunc.joinToString("\n"))
        }

        println("Found ${functions.size} function declarations")

        var accumulated = "# std/result.mz - Result type utility functions\n# Note: Result type (Ok/Err) is built-in to the language\n\n"

        for ((index, func) in functions.withIndex()) {
            accumulated += func + "\n\n"
            val funcName = func.lines().first().substringAfter("let ").substringBefore(":").trim()
            println("\n[$index] Testing with function: $funcName")

            try {
                val program = parser.parse(accumulated)
                val typed = typeChecker.typeCheck(program).program
                assertNotNull(typed)
                println("  ✓ SUCCESS with $funcName")
            } catch (e: Exception) {
                println("  ✗ FAILED after adding $funcName")
                println("  Error: ${e.message?.take(300)}")
                throw AssertionError("Failed after adding function $funcName at index $index", e)
            }
        }
    }
}
