package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test

class PolymorphicDebugTest {

    @Test
    fun testPolymorphicArithmetic() {
        val testCases = listOf(
            "let complex = {x, y, z} -> x + y" to "{ x: 'a, y: 'a, z: 'b } with NumAdd 'a -> 'a",
            "let addTwo = { x } -> x + x" to "{ x: 'a } with NumAdd 'a -> 'a",
            "let square = { x } -> x * x" to "{ x: 'a } with NumMul 'a -> 'a"
        )

        // Test bidirectional detection
        testCases.forEach { (code, _) ->
            val parser = MezzeParser()
            val v1Program = parser.parse(code)
            for (item in v1Program.items) {
                if (item is LetBinding && item.expression is LambdaExpression) {
                    val lambda = item.expression as LambdaExpression
                    val typeChecker = TypeChecker()
                    val shouldUseBidirectional = typeChecker.testShouldUseBidirectionalInference(lambda)
                    println("${item.variableName}: shouldUseBidirectional = $shouldUseBidirectional")
                }
            }
        }

        testCases.forEach { (code, expected) ->
            println("=== Testing: $code ===")
            println("Expected: $expected")

            val parser = MezzeParser()
            val parsedModule = parser.parseParsedModule(code, "test.mz")

            val typeChecker = TypeChecker()
            val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
            val typeAliases = typeChecker.getTypeAliases()

            // Extract the variable name from the code (e.g. "complex", "addTwo", "square")
            val varName = when {
                code.contains("complex") -> "complex"
                code.contains("addTwo") -> "addTwo"
                code.contains("square") -> "square"
                else -> null
            }

            if (varName != null) {
                val varType = result.getVariableType(varName)
                val typeStr = varType?.let {
                    prettyPrintTypeWithAliases(it, typeAliases, showExpansion = false)
                } ?: "unknown"
                println("Actual  : $typeStr")

                // Check if it has the right structure
                val hasCorrectStructure = when (varName) {
                    "complex" -> typeStr.contains("x: 'a, y: 'a") && typeStr.contains("z: ") && typeStr.contains("NumAdd")
                    "addTwo" -> typeStr.contains("x: 'a") && typeStr.contains("NumAdd") && typeStr.contains("-> 'a")
                    "square" -> typeStr.contains("x: 'a") && typeStr.contains("NumMul") && typeStr.contains("-> 'a")
                    else -> false
                }

                println("Structure OK: $hasCorrectStructure")
            }
            println()
        }
    }
}
