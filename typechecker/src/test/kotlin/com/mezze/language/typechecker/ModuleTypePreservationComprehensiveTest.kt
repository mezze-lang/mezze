package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Comprehensive test to verify the module type preservation fix
 * This test demonstrates that the bug is FIXED
 */
class ModuleTypePreservationComprehensiveTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    /** Helper to get inferred type of a binding by name from typed result */
    private fun getBindingType(program: TypeCheckResult, name: String): MezzeType? {
        return program.getVariableType(name)
    }

    @Test
    fun `comprehensive test - types are preserved across module boundaries`(@TempDir tempDir: Path) {
        println("\n" + "=".repeat(70))
        println("COMPREHENSIVE MODULE TYPE PRESERVATION TEST")
        println("=".repeat(70))

        // Create a module with various types
        val dataModule = tempDir.resolve("data_module.mz")
        dataModule.writeText("""
            # Module with explicit type annotations
            pub let pi: Float = 3.14159
            pub let max_value: Int = 1000
            pub let greeting: Str = "Hello, Mezze!"
            pub let is_active: Bool = True

            # Function types
            pub let add: { a: Int, b: Int } -> Int = { a, b } -> a + b
            pub let negate: { x: Int } -> Int = { x } -> x.neg {}
            pub let concat: { s1: Str, s2: Str } -> Str = { s1, s2 } -> s1
        """.trimIndent())

        // Create main program that imports and uses these
        val mainSource = """
            use data_module::{pi, max_value, greeting, is_active, add, negate}

            # These should ALL preserve their types
            let my_pi = pi
            let my_max = max_value
            let my_greeting = greeting
            let my_flag = is_active
            let my_add = add
            let my_negate = negate

            # Using the functions
            let sum = add { a = 10, b = 20 }
            let neg = negate { x = 42 }
        """.trimIndent()

        println("\n📝 Testing module imports...")
        val parsedModule = parser.parseParsedModule(mainSource, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, tempDir)

        println("\n📊 Type Check Results:")
        println("-".repeat(70))

        // Test each imported value
        val tests = listOf(
            "my_pi" to "Float",
            "my_max" to "Int",
            "my_greeting" to "Str",
            "my_flag" to "Boolean",
            "sum" to "Int",
            "neg" to "Int"
        )

        var allPassed = true
        for ((varName, expectedType) in tests) {
            val actualType = getBindingType(typedProgram, varName)
            val typeStr = actualType?.let { prettyPrintType(it) } ?: "null"

            val passed = when (expectedType) {
                "Int" -> actualType is MezzeType.IntType
                "Float" -> actualType is MezzeType.FloatType
                "Str" -> actualType is MezzeType.StringType
                "Boolean" -> actualType == PrimitiveTypes.BOOL_TYPE
                else -> false
            }

            val status = if (passed) "✅ PASS" else "❌ FAIL"
            println("  $status  $varName: $typeStr (expected: $expectedType)")

            if (!passed) allPassed = false

            // Assert for test framework
            assertNotNull(actualType, "$varName should have a type")
            if (expectedType == "Int") {
                assertTrue(actualType is MezzeType.IntType,
                    "$varName should be Int, got: $typeStr")
            }
        }

        // Test function types
        val myAdd = getBindingType(typedProgram, "my_add")
        assertNotNull(myAdd, "my_add should have a type")
        assertTrue(myAdd is MezzeType.FunctionType,
            "my_add should be a function type, got: ${prettyPrintType(myAdd!!)}")
        println("  ✅ PASS  my_add: ${prettyPrintType(myAdd)}")

        val myNegate = getBindingType(typedProgram, "my_negate")
        assertNotNull(myNegate, "my_negate should have a type")
        assertTrue(myNegate is MezzeType.FunctionType,
            "my_negate should be a function type, got: ${prettyPrintType(myNegate!!)}")
        println("  ✅ PASS  my_negate: ${prettyPrintType(myNegate)}")

        println("-".repeat(70))

        if (allPassed) {
            println("\n🎉 SUCCESS! All types preserved correctly across module boundaries!")
            println("✅ The module type preservation bug is FIXED!")
        } else {
            fail("Some types were not preserved correctly")
        }

        println("=".repeat(70) + "\n")
    }

    @Test
    fun `test that fresh type variables are NOT used for imports`(@TempDir tempDir: Path) {
        println("\n" + "=".repeat(70))
        println("TEST: Verify imports don't become fresh type variables")
        println("=".repeat(70))

        // Create module
        val module = tempDir.resolve("simple_module.mz")
        module.writeText("""
            pub let value: Int = 123
        """.trimIndent())

        // Import it
        val source = """
            use simple_module::{value}
            let my_value = value
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, tempDir)

        val myValueType = getBindingType(typedProgram, "my_value")

        println("\nmy_value type: ${myValueType?.let { prettyPrintType(it) } ?: "null"}")

        assertNotNull(myValueType, "my_value should have a type")

        // The bug was that it became 'a (a fresh type variable)
        // After the fix, it should be Int
        val typeStr = prettyPrintType(myValueType!!)

        // Check it's NOT a bare type variable like 'a, 'b, etc.
        val isFreshVar = myValueType is MezzeType.VarType &&
                         typeStr.matches(Regex("'[a-z]"))

        assertFalse(isFreshVar,
            "my_value should NOT be a fresh type variable like 'a, got: $typeStr")

        assertTrue(myValueType is MezzeType.IntType,
            "my_value should be Int, got: $typeStr")

        println("✅ VERIFIED: Import is NOT a fresh type variable!")
        println("✅ Type is correctly preserved as: $typeStr")
        println("=".repeat(70) + "\n")
    }
}
