package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
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
            val parser = FunkParser()
            val ast = parser.parse(code)
            for (item in ast.items) {
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
            
            val parser = FunkParser()
            val ast = parser.parse(code)
            
            val typeChecker = TypeChecker()
            val result = typeChecker.typeCheck(ast)
            val typeAliases = typeChecker.getTypeAliases()
            
            for (item in result.items) {
                if (item is LetBinding) {
                    val typeStr = item.inferredType?.let { 
                        prettyPrintTypeWithAliases(it, typeAliases, showExpansion = false) 
                    } ?: "unknown"
                    println("Actual  : $typeStr")
                    
                    // Check if it has the right structure
                    val hasCorrectStructure = when (item.variableName) {
                        "complex" -> typeStr.contains("x: 'a, y: 'a") && typeStr.contains("z: ") && typeStr.contains("NumAdd")
                        "addTwo" -> typeStr.contains("x: 'a") && typeStr.contains("NumAdd") && typeStr.contains("-> 'a")
                        "square" -> typeStr.contains("x: 'a") && typeStr.contains("NumMul") && typeStr.contains("-> 'a")
                        else -> false
                    }
                    
                    println("Structure OK: $hasCorrectStructure")
                }
            }
            println()
        }
    }
}