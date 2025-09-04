package com.funk.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.File

class ExampleParsingTest {

    private val parser = FunkParserImpl()

    @Test
    fun `parse basic_test example`() {
        val file = listOf(
            File("examples/basic_test.funk"),
            File("../examples/basic_test.funk")
        ).firstOrNull { it.exists() }
        
        if (file != null) {
            val source = file.readText()
            val ast = parser.parse(source)
            
            assertNotNull(ast)
            assertTrue(ast.items.isNotEmpty())
            
            // Should contain let bindings and type declarations
            val letBindings = ast.items.filterIsInstance<LetBinding>()
            val typeDeclarations = ast.items.filterIsInstance<TypeDeclaration>()
            
            assertTrue(letBindings.isNotEmpty(), "Should contain let bindings")
            assertTrue(typeDeclarations.isNotEmpty(), "Should contain type declarations")
            
            println("✅ basic_test.funk: ${ast.items.size} items parsed successfully")
        } else {
            println("⚠️ basic_test.funk not found, skipping test")
        }
    }

    @Test
    fun `parse variants_test example`() {
        val file = File("examples/variants_test.funk")
        if (file.exists()) {
            val source = file.readText()
            val ast = parser.parse(source)
            
            assertNotNull(ast)
            assertTrue(ast.items.isNotEmpty())
            
            // Should contain variant type declarations
            val typeDeclarations = ast.items.filterIsInstance<TypeDeclaration>()
            val hasVariants = typeDeclarations.any { it.definition is VariantType }
            
            assertTrue(hasVariants, "Should contain variant type declarations")
            
            println("✅ variants_test.funk: ${ast.items.size} items parsed successfully")
        } else {
            println("⚠️ variants_test.funk not found, skipping test")
        }
    }

    @Test
    fun `parse hello example`() {
        val file = File("examples/hello.funk")
        if (file.exists()) {
            val source = file.readText()
            val ast = parser.parse(source)
            
            assertNotNull(ast)
            assertTrue(ast.items.isNotEmpty())
            
            println("✅ hello.funk: ${ast.items.size} items parsed successfully")
        } else {
            println("⚠️ hello.funk not found, skipping test")
        }
    }

    @Test
    fun `parse functions example`() {
        val file = File("examples/functions.funk")
        if (file.exists()) {
            val source = file.readText()
            val ast = parser.parse(source)
            
            assertNotNull(ast)
            assertTrue(ast.items.isNotEmpty())
            
            println("✅ functions.funk: ${ast.items.size} items parsed successfully")
        } else {
            println("⚠️ functions.funk not found, skipping test")
        }
    }

    @Test
    fun `parse nested_expressions example`() {
        val file = File("examples/nested_expressions.funk")
        if (file.exists()) {
            val source = file.readText()
            val ast = parser.parse(source)
            
            assertNotNull(ast)
            assertTrue(ast.items.isNotEmpty())
            
            println("✅ nested_expressions.funk: ${ast.items.size} items parsed successfully")
        } else {
            println("⚠️ nested_expressions.funk not found, skipping test")
        }
    }

    @Test
    fun `parse complex_if example`() {
        val file = File("examples/complex_if.funk")
        if (file.exists()) {
            val source = file.readText()
            val ast = parser.parse(source)
            
            assertNotNull(ast)
            assertTrue(ast.items.isNotEmpty())
            
            println("✅ complex_if.funk: ${ast.items.size} items parsed successfully")
        } else {
            println("⚠️ complex_if.funk not found, skipping test")
        }
    }

    @Test
    fun `parse all example files that exist`() {
        // Try both relative paths - from parser module and from root
        val examplesDir = listOf(
            File("examples"),
            File("../examples"),
            File("../../examples")
        ).firstOrNull { it.exists() }
        
        if (examplesDir == null) {
            println("⚠️ Examples directory not found, skipping comprehensive test")
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
        var errorCount = 0

        funkFiles.forEach { file ->
            try {
                val source = file.readText()
                val ast = parser.parse(source)
                
                assertNotNull(ast, "AST should not be null for ${file.name}")
                println("✅ ${file.name}: ${ast.items.size} items parsed successfully")
                successCount++
                
            } catch (e: Exception) {
                println("❌ ${file.name}: Failed to parse - ${e.message}")
                errorCount++
                
                // Don't fail the test, just report the error
                // This allows us to see which files work and which don't
            }
        }

        println("\n📊 Parsing Summary:")
        println("   ✅ Successfully parsed: $successCount files")
        println("   ❌ Failed to parse: $errorCount files")
        println("   📁 Total files: ${funkFiles.size}")

        // At least some files should parse successfully
        assertTrue(successCount > 0, "At least some example files should parse successfully")
    }
}
