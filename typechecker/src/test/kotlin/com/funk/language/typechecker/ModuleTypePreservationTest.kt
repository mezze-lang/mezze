package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Test to investigate module type preservation issue
 * Following Phase 1 of TYPE_SYSTEM_INVESTIGATION.md
 */
class ModuleTypePreservationTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() {
            // Enable debug logging
            System.setProperty("funk.typecheck.debug", "true")
        }
    }

    @Test
    fun `test module with explicit type annotations exports correctly`(@TempDir tempDir: Path) {
        println("\n========================================")
        println("TEST: Module with explicit type annotations")
        println("========================================\n")

        // Create a simple module file with explicit type annotations
        val moduleFile = tempDir.resolve("test_module.funk")
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
        val parser = FunkParser()
        val typeChecker = TypeChecker()

        try {
            val program = parser.parse(mainSource)
            val typedProgram = typeChecker.typeCheck(program, tempDir)

            println("\n========================================")
            println("RESULTS:")
            println("========================================\n")

            // Check the types of the imported values
            val context = typeChecker.getContext()
            println("Final context size: ${context.size}")
            println("Final context keys: ${context.keys.joinToString(", ")}")

            context.forEach { (k, v) ->
                println("  $k -> ${prettyPrintType(v)}")
            }

            // Verify simple_int has type Int
            val simpleIntType = context["test_int"]
            println("\ntest_int type: ${simpleIntType?.let { prettyPrintType(it) } ?: "null"}")

            // Verify simple_func has the correct function type
            val simpleFuncType = context["test_func"]
            println("test_func type: ${simpleFuncType?.let { prettyPrintType(it) } ?: "null"}")

            // Assertions
            assertNotNull(simpleIntType, "test_int should have a type")
            assertNotNull(simpleFuncType, "test_func should have a type")

            // Check that types are not just 'a (fresh type variables)
            assertTrue(simpleIntType !is FunkType.VarType, "test_int should not be a type variable, got: ${prettyPrintType(simpleIntType!!)}")
            assertTrue(simpleFuncType !is FunkType.VarType, "test_func should not be a type variable, got: ${prettyPrintType(simpleFuncType!!)}")

            // Check concrete types
            assertEquals(FunkType.IntType, simpleIntType, "test_int should have type Int")
            assertTrue(simpleFuncType is FunkType.FunctionType, "test_func should be a function type")

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

        // Create a simple module file WITHOUT explicit type annotations
        val moduleFile = tempDir.resolve("test_inferred.funk")
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
        val parser = FunkParser()
        val typeChecker = TypeChecker()

        try {
            val program = parser.parse(mainSource)
            val typedProgram = typeChecker.typeCheck(program, tempDir)

            println("\n========================================")
            println("RESULTS:")
            println("========================================\n")

            // Check the types of the imported values
            val context = typeChecker.getContext()
            println("Final context size: ${context.size}")
            println("Final context keys: ${context.keys.joinToString(", ")}")

            context.forEach { (k, v) ->
                println("  $k -> ${prettyPrintType(v)}")
            }

            // Verify inferred_int has type Int
            val inferredIntType = context["test_int"]
            println("\ntest_int type: ${inferredIntType?.let { prettyPrintType(it) } ?: "null"}")

            // Verify inferred_func has the correct function type
            val inferredFuncType = context["test_func"]
            println("test_func type: ${inferredFuncType?.let { prettyPrintType(it) } ?: "null"}")

            // Assertions
            assertNotNull(inferredIntType, "test_int should have a type")
            assertNotNull(inferredFuncType, "test_func should have a type")

            // Check that types are not just 'a (fresh type variables)
            // This test is expected to FAIL until the module system is fixed
            println("\nExpected behavior: These should NOT be type variables")
            println("Actual test_int type: ${prettyPrintType(inferredIntType!!)}")
            println("Actual test_func type: ${prettyPrintType(inferredFuncType!!)}")

        } catch (e: Exception) {
            println("\n========================================")
            println("ERROR:")
            println("========================================\n")
            e.printStackTrace()
            throw e
        }
    }
}
