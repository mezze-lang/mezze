package com.funk.language.typechecker

import com.funk.language.parser.FunkParserImpl
import com.funk.language.ast.*
import com.funk.language.ast.prettyPrintTypeWithAliases
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.io.File

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FixtureBasedTypeCheckerTest {
    
    private val parser = FunkParserImpl()
    private val typeChecker = TypeChecker()
    
    private fun getFixturesPath(): String {
        return this::class.java.classLoader.getResource("fixtures")?.path 
            ?: throw IllegalStateException("Fixtures directory not found")
    }
    
    private fun loadFunkFile(path: String): String {
        return File(path).readText()
    }
    
    private fun parseAndTypeCheck(source: String): Program {
        val program = parser.parse(source)
        return typeChecker.typeCheck(program)
    }
    
    private fun extractExpectedTypes(source: String): Map<String, String> {
        val expectedTypes = mutableMapOf<String, String>()
        val lines = source.lines()
        
        for (line in lines) {
            // Look for comments like: # expected: variableName : Type
            val match = Regex("""#\s*expected:\s*(\w+)\s*:\s*(.+)""").find(line.trim())
            if (match != null) {
                val (varName, expectedType) = match.destructured
                expectedTypes[varName] = expectedType.trim()
            }
        }
        
        return expectedTypes
    }
    
    private fun getActualTypes(program: Program): Map<String, String> {
        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(program)
        val typeAliases = typeChecker.getTypeAliases()
        
        val actualTypes = mutableMapOf<String, String>()
        
        for (item in result.items) {
            when (item) {
                is LetBinding -> {
                    val typeStr = item.inferredType?.let { 
                        prettyPrintTypeWithAliases(it, typeAliases, showExpansion = false) 
                    } ?: "unknown"
                    actualTypes[item.variableName] = typeStr
                }
                is TypeDeclaration -> {
                    val expansionStr = item.inferredType?.let { 
                        prettyPrintType(it) 
                    } ?: ""
                    actualTypes[item.name] = expansionStr
                }
                else -> {
                    // Skip other program items (modules, etc.)
                }
            }
        }
        
        return actualTypes
    }
    
    @TestFactory
    fun `valid programs should match expected types`(): List<DynamicTest> {
        val validFixturesDir = File(getFixturesPath(), "valid")
        return validFixturesDir.listFiles { file -> file.extension == "funk" }
            ?.map { file ->
                DynamicTest.dynamicTest("Valid: ${file.nameWithoutExtension}") {
                    val source = loadFunkFile(file.absolutePath)
                    val expectedTypes = extractExpectedTypes(source)
                    
                    // Parse and type check
                    val program = parser.parse(source)
                    val actualTypes = getActualTypes(program)
                    
                    // Compare expected vs actual types
                    for ((varName, expectedType) in expectedTypes) {
                        val actualType = actualTypes[varName]
                        assertNotNull(actualType, "Variable '$varName' should have a type")
                        assertEquals(expectedType, actualType, "Type mismatch for variable '$varName'")
                    }
                }
            } ?: emptyList()
    }
    
    @TestFactory
    fun `inference programs should match expected types`(): List<DynamicTest> {
        val inferenceFixturesDir = File(getFixturesPath(), "inference")
        return inferenceFixturesDir.listFiles { file -> file.extension == "funk" }
            ?.map { file ->
                DynamicTest.dynamicTest("Inference: ${file.nameWithoutExtension}") {
                    val source = loadFunkFile(file.absolutePath)
                    val expectedTypes = extractExpectedTypes(source)
                    
                    // Parse and type check
                    val program = parser.parse(source)
                    val actualTypes = getActualTypes(program)
                    
                    // Compare expected vs actual types
                    for ((varName, expectedType) in expectedTypes) {
                        val actualType = actualTypes[varName]
                        assertNotNull(actualType, "Variable '$varName' should have a type")
                        assertEquals(expectedType, actualType, "Type mismatch for variable '$varName'")
                    }
                }
            } ?: emptyList()
    }
    
    @TestFactory
    fun `annotation programs should match expected types`(): List<DynamicTest> {
        val annotationFixturesDir = File(getFixturesPath(), "annotations")
        return annotationFixturesDir.listFiles { file -> file.extension == "funk" }
            ?.map { file ->
                DynamicTest.dynamicTest("Annotations: ${file.nameWithoutExtension}") {
                    val source = loadFunkFile(file.absolutePath)
                    val expectedTypes = extractExpectedTypes(source)
                    
                    // Parse and type check
                    val program = parser.parse(source)
                    val actualTypes = getActualTypes(program)
                    
                    // Compare expected vs actual types
                    for ((varName, expectedType) in expectedTypes) {
                        val actualType = actualTypes[varName]
                        assertNotNull(actualType, "Variable '$varName' should have a type")
                        assertEquals(expectedType, actualType, "Type mismatch for variable '$varName'")
                    }
                }
            } ?: emptyList()
    }
    
    @TestFactory
    fun `invalid programs should fail with expected errors`(): List<DynamicTest> {
        val invalidFixturesDir = File(getFixturesPath(), "invalid")
        return invalidFixturesDir.listFiles { file -> file.extension == "funk" }
            ?.map { file ->
                DynamicTest.dynamicTest("Invalid: ${file.nameWithoutExtension}") {
                    val source = loadFunkFile(file.absolutePath)
                    
                    // Should throw an exception
                    assertThrows<RuntimeException> {
                        parseAndTypeCheck(source)
                    }
                }
            } ?: emptyList()
    }
    
    @Test
    fun `type alias functionality should work correctly`() {
        val source = """
            type Color = | Red, Blue |
            type Box 'a = { value: 'a }
            type Maybe 'a = | Some {val: 'a}, None |
            
            let red = Red
            let blue = Blue
            let intBox = { value = 42 }
            let stringBox = { value = "hello" }
            let justInt = Some { val = 42 }
            let justString = Some { val = "world" }
        """.trimIndent()
        
        val program = parser.parse(source)
        val actualTypes = getActualTypes(program)
        
        // Test type alias matching
        assertEquals("Color", actualTypes["red"])
        assertEquals("Color", actualTypes["blue"])
        assertEquals("Box Int", actualTypes["intBox"])
        assertEquals("Box String", actualTypes["stringBox"])
        assertEquals("Maybe Int", actualTypes["justInt"])
        assertEquals("Maybe String", actualTypes["justString"])
    }
}
