package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Test to investigate module type preservation issue
 * Following Phase 1 of TYPE_SYSTEM_INVESTIGATION.md
 */
class ModuleTypePreservationTest {

    @Test
    fun `test module with explicit type annotations exports correctly`(@TempDir tempDir: Path) {
        println("\n========================================")
        println("TEST: Module with explicit type annotations")
        println("========================================\n")

        // Clear caches
        ModuleResolver.clearCache()
        TypeChecker.clearCache()

        // Create a simple module file with explicit type annotations
        val moduleFile = tempDir.resolve("test_module.mz")
        moduleFile.writeText("""
            pub let simple_int: Int = 42
            pub let simple_func: { x: Int } -> Int = { x } -> x + 1
        """.trimIndent())

        // Create a file that imports from the module
        val mainSource = """
            use test_module::{simple_int, simple_func}

            let test_int = simple_int
            let test_func = simple_func
        """.trimIndent()

        // Parse and type check
        val parser = MezzeParser()
        val typeChecker = TypeChecker()

        try {
            val parsedModule = parser.parseParsedModule(mainSource, "test.mz")
            val result = typeChecker.typeCheck(parsedModule, tempDir)

            println("\n========================================")
            println("RESULTS:")
            println("========================================\n")

            // Get types from the typed result
            val simpleIntType = result.getVariableType("test_int")
            val simpleFuncType = result.getVariableType("test_func")

            println("test_int type: ${simpleIntType?.let { prettyPrintType(it) } ?: "null"}")
            println("test_func type: ${simpleFuncType?.let { prettyPrintType(it) } ?: "null"}")

            // Assertions
            assertNotNull(simpleIntType, "test_int should have a type")
            assertNotNull(simpleFuncType, "test_func should have a type")

            // Check that types are not just 'a (fresh type variables)
            assertTrue(simpleIntType !is MezzeType.VarType, "test_int should not be a type variable, got: ${prettyPrintType(simpleIntType!!)}")
            assertTrue(simpleFuncType !is MezzeType.VarType, "test_func should not be a type variable, got: ${prettyPrintType(simpleFuncType!!)}")

            // Check concrete types
            assertEquals(MezzeType.IntType, simpleIntType, "test_int should have type Int")
            assertTrue(simpleFuncType is MezzeType.FunctionType, "test_func should be a function type")

        } catch (e: Exception) {
            println("\n========================================")
            println("ERROR:")
            println("========================================\n")
            e.printStackTrace()
            throw e
        }
    }

    @Test
    fun `test module with inferred types exports correctly`(@TempDir tempDir: Path) {
        println("\n========================================")
        println("TEST: Module with inferred types (no annotations)")
        println("========================================\n")

        // Clear caches
        ModuleResolver.clearCache()
        TypeChecker.clearCache()

        // Create a simple module file WITHOUT explicit type annotations
        val moduleFile = tempDir.resolve("test_inferred.mz")
        moduleFile.writeText("""
            pub let inferred_int = 42
            pub let inferred_func = { x } -> x + 1
        """.trimIndent())

        // Create a file that imports from the module
        val mainSource = """
            use test_inferred::{inferred_int, inferred_func}

            let test_int = inferred_int
            let test_func = inferred_func
        """.trimIndent()

        // Parse and type check
        val parser = MezzeParser()
        val typeChecker = TypeChecker()

        try {
            val parsedModule = parser.parseParsedModule(mainSource, "test.mz")
            val result = typeChecker.typeCheck(parsedModule, tempDir)

            // Get types from the typed result
            val testIntType = result.getVariableType("test_int")
            val testFuncType = result.getVariableType("test_func")

            println("test_int type: ${testIntType?.let { prettyPrintType(it) } ?: "null"}")
            println("test_func type: ${testFuncType?.let { prettyPrintType(it) } ?: "null"}")

            assertNotNull(testIntType, "test_int should have a type")
            assertNotNull(testFuncType, "test_func should have a type")

            // The bug was that it became 'a (a fresh type variable)
            // After the fix, it should be Int
            val typeStr = prettyPrintType(testIntType!!)

            // Check it's NOT a bare type variable like 'a, 'b, etc.
            val isFreshVar = testIntType is MezzeType.VarType &&
                             typeStr.matches(Regex("'[a-z]"))

            assertFalse(isFreshVar,
                "test_int should NOT be a fresh type variable like 'a, got: $typeStr")

            assertTrue(testIntType is MezzeType.IntType,
                "test_int should be Int, got: $typeStr")

            println("VERIFIED: Import is NOT a fresh type variable!")
            println("Type is correctly preserved as: $typeStr")

        } catch (e: Exception) {
            println("\n========================================")
            println("ERROR:")
            println("========================================\n")
            e.printStackTrace()
            throw e
        }
    }
}
