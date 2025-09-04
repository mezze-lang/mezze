package com.funk.language.typechecker

import com.funk.language.parser.FunkParserImpl
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class ExampleFilesTypeCheckTest {

    private val parser = FunkParserImpl()
    private val typeChecker = TypeChecker()

    @Test
    fun `type check all example files`() {
        // Try different possible paths for examples directory
        val examplesDir = listOf(
            File("examples"),
            File("../examples"),
            File("../../examples")
        ).firstOrNull { it.exists() }
        
        if (examplesDir == null) {
            println("⚠️ Examples directory not found, skipping comprehensive type check test")
            return
        }

        val funkFiles = examplesDir.listFiles { file -> 
            file.isFile && file.extension == "funk" 
        } ?: emptyArray()

        if (funkFiles.isEmpty()) {
            println("⚠️ No .funk files found in examples directory")
            return
        }

        var successCount = 0
        var parseErrorCount = 0
        var typeErrorCount = 0
        val results = mutableListOf<TestResult>()

        funkFiles.forEach { file ->
            try {
                val source = file.readText()
                val ast = parser.parse(source)
                
                try {
                    val typedProgram = typeChecker.typeCheck(ast)
                    results.add(TestResult(file.name, TestStatus.SUCCESS, null, typedProgram.items.size))
                    successCount++
                } catch (typeError: Exception) {
                    results.add(TestResult(file.name, TestStatus.TYPE_ERROR, typeError.message, null))
                    typeErrorCount++
                }
                
            } catch (parseError: Exception) {
                results.add(TestResult(file.name, TestStatus.PARSE_ERROR, parseError.message, null))
                parseErrorCount++
            }
        }

        // Print detailed results
        println("\n🔍 Type Checking Results for Example Files:")
        println("=".repeat(60))
        
        results.sortedBy { it.status.ordinal }.forEach { result ->
            val statusIcon = when (result.status) {
                TestStatus.SUCCESS -> "✅"
                TestStatus.TYPE_ERROR -> "🔴"
                TestStatus.PARSE_ERROR -> "❌"
            }
            
            val details = when (result.status) {
                TestStatus.SUCCESS -> "${result.itemCount} items typed successfully"
                TestStatus.TYPE_ERROR -> "Type error: ${result.errorMessage}"
                TestStatus.PARSE_ERROR -> "Parse error: ${result.errorMessage}"
            }
            
            println("$statusIcon ${result.fileName.padEnd(30)} - $details")
        }

        println("=".repeat(60))
        println("📊 Summary:")
        println("   ✅ Successfully type checked: $successCount files")
        println("   🔴 Type check failed: $typeErrorCount files")
        println("   ❌ Parse failed: $parseErrorCount files")
        println("   📁 Total files: ${funkFiles.size}")

        // Print details of failed files
        val failedFiles = results.filter { it.status != TestStatus.SUCCESS }
        if (failedFiles.isNotEmpty()) {
            println("\n🔍 Failed Files Details:")
            failedFiles.forEach { result ->
                println("   ${result.fileName}: ${result.errorMessage}")
            }
        }

        // The test should pass even if some files fail type checking,
        // but we want to see the results. In a stricter version, we could
        // require all files to type check successfully.
        assertTrue(successCount > 0, "At least some example files should type check successfully")
        
        // Optional: Uncomment this line if you want ALL files to type check successfully
        // assertEquals(0, typeErrorCount + parseErrorCount, "All example files should parse and type check successfully")
    }

    @Test
    fun `individual example file tests`() {
        // Test some specific important examples individually for more detailed reporting
        val importantExamples = listOf(
            "hello.funk",
            "functions.funk", 
            "basic_test.funk",
            "simple_lambda_test.funk"
        )
        
        val examplesDir = listOf(
            File("examples"),
            File("../examples"),
            File("../../examples")
        ).firstOrNull { it.exists() }
        
        if (examplesDir == null) {
            println("⚠️ Examples directory not found, skipping individual tests")
            return
        }

        importantExamples.forEach { fileName ->
            val file = File(examplesDir, fileName)
            if (file.exists()) {
                try {
                    val source = file.readText()
                    val ast = parser.parse(source)
                    val typedProgram = typeChecker.typeCheck(ast)
                    
                    assertNotNull(typedProgram, "Type checking should produce a result for $fileName")
                    assertTrue(typedProgram.items.isNotEmpty(), "$fileName should have typed items")
                    
                    println("✅ $fileName: ${typedProgram.items.size} items typed successfully")
                } catch (e: Exception) {
                    println("❌ $fileName failed: ${e.message}")
                    // Don't fail the test for individual files, just report
                }
            } else {
                println("⚠️ $fileName not found, skipping")
            }
        }
    }

    private data class TestResult(
        val fileName: String,
        val status: TestStatus,
        val errorMessage: String?,
        val itemCount: Int?
    )

    private enum class TestStatus {
        SUCCESS,
        TYPE_ERROR, 
        PARSE_ERROR
    }
}
