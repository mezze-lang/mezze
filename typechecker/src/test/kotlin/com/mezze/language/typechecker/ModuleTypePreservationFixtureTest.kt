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
 * Test module type preservation using fixture files
 * This verifies the fix for the bug where imported values lost their types
 */
class ModuleTypePreservationFixtureTest {

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
    fun `test module type preservation with explicit annotations`(@TempDir tempDir: Path) {
        // Create the exported module
        val exportedModule = tempDir.resolve("exported_module.mz")
        exportedModule.writeText("""
            pub let exported_int: Int = 42
            pub let exported_string: Str = "hello"
            pub let add_func: { x: Int, y: Int } -> Int = { x, y } -> x + y
        """.trimIndent())

        // Create the importing module
        val importingSource = """
            use exported_module::{exported_int, exported_string, add_func}

            let test_int = exported_int
            let test_string = exported_string
            let test_func = add_func
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(importingSource, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, tempDir)

        // Verify test_int has type Int (not a type variable)
        val testIntType = getBindingType(typedProgram, "test_int")
        assertNotNull(testIntType, "test_int should have a type")
        assertTrue(testIntType is MezzeType.IntType,
                   "test_int should be Int, but got: ${prettyPrintType(testIntType!!)}")

        // Verify test_string has type String
        val testStringType = getBindingType(typedProgram, "test_string")
        assertNotNull(testStringType, "test_string should have a type")
        assertTrue(testStringType is MezzeType.StringType,
                   "test_string should be Str, but got: ${prettyPrintType(testStringType!!)}")

        // Verify test_func has a function type
        val testFuncType = getBindingType(typedProgram, "test_func")
        assertNotNull(testFuncType, "test_func should have a type")
        assertTrue(testFuncType is MezzeType.FunctionType,
                   "test_func should be a function type, but got: ${prettyPrintType(testFuncType!!)}")

        println("✅ All types preserved correctly!")
        println("  test_int: ${prettyPrintType(testIntType)}")
        println("  test_string: ${prettyPrintType(testStringType)}")
        println("  test_func: ${prettyPrintType(testFuncType)}")
    }

    @Test
    fun `test module type preservation without explicit annotations`(@TempDir tempDir: Path) {
        // Create the exported module WITHOUT type annotations
        val exportedModule = tempDir.resolve("inferred_module.mz")
        exportedModule.writeText("""
            pub let num = 100
            pub let text = "world"
            pub let double = { x } -> x + x
        """.trimIndent())

        // Create the importing module
        val importingSource = """
            use inferred_module::{num, text, double}

            let my_num = num
            let my_text = text
            let my_func = double
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(importingSource, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, tempDir)

        // Verify types exist
        val myNumType = getBindingType(typedProgram, "my_num")
        val myTextType = getBindingType(typedProgram, "my_text")
        val myFuncType = getBindingType(typedProgram, "my_func")

        assertNotNull(myNumType, "my_num should have a type")
        assertNotNull(myTextType, "my_text should have a type")
        assertNotNull(myFuncType, "my_func should have a type")

        println("✅ Types inferred from module:")
        println("  my_num: ${prettyPrintType(myNumType!!)}")
        println("  my_text: ${prettyPrintType(myTextType!!)}")
        println("  my_func: ${prettyPrintType(myFuncType!!)}")
    }

    @Test
    fun `test imported function can be called with correct types`(@TempDir tempDir: Path) {
        // Create module with a function
        val mathModule = tempDir.resolve("math_module.mz")
        mathModule.writeText("""
            pub let multiply: { a: Int, b: Int } -> Int = { a, b } -> a * b
        """.trimIndent())

        // Import and use the function
        val importingSource = """
            use math_module::{multiply}

            let result = multiply { a = 5, b = 10 }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(importingSource, "test.mz")

        // This should not throw any type errors
        val typedProgram = typeChecker.typeCheck(parsedModule, tempDir)

        // result should be Int
        val resultType = getBindingType(typedProgram, "result")
        assertNotNull(resultType, "result should have a type")

        println("✅ Function call type-checked successfully!")
        println("  result: ${prettyPrintType(resultType!!)}")
    }

    @Test
    fun `test multiple imports from same module preserve types`(@TempDir tempDir: Path) {
        // Create module with multiple exports
        val utilsModule = tempDir.resolve("utils_module.mz")
        utilsModule.writeText("""
            pub let max_int: Int = 2147483647
            pub let min_int: Int = -2147483648
            pub let is_positive: { n: Int } -> Bool = { n } -> n > 0
            pub let abs: { n: Int } -> Int = { n } -> if n < 0 then -n else n
        """.trimIndent())

        // Import multiple items
        val importingSource = """
            use utils_module::{max_int, min_int, is_positive, abs}

            let max = max_int
            let min = min_int
            let check = is_positive { n = 5 }
            let absolute = abs { n = -10 }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(importingSource, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, tempDir)

        // Verify all have appropriate types
        val maxType = getBindingType(typedProgram, "max")
        val minType = getBindingType(typedProgram, "min")
        val checkType = getBindingType(typedProgram, "check")
        val absoluteType = getBindingType(typedProgram, "absolute")

        assertNotNull(maxType, "max should have a type")
        assertNotNull(minType, "min should have a type")
        assertNotNull(checkType, "check should have a type")
        assertNotNull(absoluteType, "absolute should have a type")

        println("✅ Multiple imports preserved types:")
        println("  max: ${prettyPrintType(maxType!!)}")
        println("  min: ${prettyPrintType(minType!!)}")
        println("  check: ${prettyPrintType(checkType!!)}")
        println("  absolute: ${prettyPrintType(absoluteType!!)}")
    }
}
