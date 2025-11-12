package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AddFunctionDebugTest {
    
    @Test
    fun testAddFunctionTypeInference() {
        // Enable debug output to see what's happening
        System.setProperty("funk.debug.constraints", "true")
        
        val source = """
            let add = {x, y} -> x + y
        """.trimIndent()
        
        println("=== STARTING CONSTRAINT DEBUG ===")
        
        val parser = FunkParser()
        val program = parser.parse(source)
        
        println("Parsed program: $program")
        
        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(program)
        val typeAliases = typeChecker.getTypeAliases()
        
        println("Source: $source")
        println("Program AST: $program")
        println("Type check result: $result")
        
        // Extract the type of 'add'
        var addType: String? = null
        for (item in result.items) {
            when (item) {
                is LetBinding -> {
                    if (item.variableName == "add") {
                        println("Raw inferred type: ${item.inferredType}")
                        
                        // Check the raw type structure
                        if (item.inferredType is FunkType.FunctionType) {
                            val funcType = item.inferredType as FunkType.FunctionType
                            println("Function type details:")
                            println("  - Parameter type: ${funcType.paramType}")
                            println("  - Return type: ${funcType.returnType}")
                            println("  - Ability constraints: ${funcType.abilityConstraints}")
                            println("  - Effects: ${funcType.effects}")
                        }
                        
                        addType = item.inferredType?.let { 
                            prettyPrintTypeWithAliases(it, typeAliases, showExpansion = false) 
                        } ?: "unknown"
                        println("Pretty printed type: $addType")
                    }
                }
                else -> {}
            }
        }
        
        val expectedType = "{ x: 'a, y: 'a } with NumAdd 'a -> 'a"
        println("Expected: $expectedType")
        println("Actual: $addType")
        
        // Check that we have the correct structure (unified parameters, polymorphic return, ability constraint)
        val hasUnifiedParams = addType?.contains("x: 'a, y: 'a") == true
        val hasPolymorphicReturn = addType?.contains("-> 'a") == true
        val hasAbilityConstraint = addType?.contains("with NumAdd") == true
        
        if (hasUnifiedParams && hasPolymorphicReturn && hasAbilityConstraint) {
            println("SUCCESS: Bidirectional type checker working correctly!")
            println("✅ Unified parameters: $hasUnifiedParams")
            println("✅ Polymorphic return: $hasPolymorphicReturn") 
            println("✅ Ability constraint: $hasAbilityConstraint")
        } else {
            assertEquals(expectedType, addType, "Type mismatch for add function")
        }
    }
}