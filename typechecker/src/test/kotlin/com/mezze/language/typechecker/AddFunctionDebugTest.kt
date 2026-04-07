package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AddFunctionDebugTest {

    @Test
    fun testAddFunctionTypeInference() {
        // Enable debug output to see what's happening
        System.setProperty("mezze.debug.constraints", "true")
        System.setProperty("mezze.typecheck.debug", "true")

        val source = """
            let add = {x, y} -> x + y
        """.trimIndent()

        println("=== STARTING CONSTRAINT DEBUG ===")

        val parser = MezzeParser()
        val parsedModule = parser.parseParsedModule(source, "test.mz")

        println("Parsed program: $parsedModule")

        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        val typeAliases = typeChecker.getTypeAliases()

        println("Source: $source")
        println("Program AST: $parsedModule")
        println("Type check result: $result")

        // Extract the type of 'add' from typedModule
        val addRawType = result.getVariableType("add")
        println("Raw inferred type: $addRawType")

        if (addRawType is MezzeType.FunctionType) {
            println("Function type details:")
            println("  - Parameter type: ${addRawType.paramType}")
            println("  - Return type: ${addRawType.returnType}")
            println("  - Ability constraints: ${addRawType.abilityConstraints}")
            println("  - Effects: ${addRawType.effects}")
        }

        val addType = addRawType?.let {
            prettyPrintTypeWithAliases(it, typeAliases, showExpansion = false)
        } ?: "unknown"
        println("Pretty printed type: $addType")

        val expectedType = "{ x: 'a, y: 'a } with NumAdd 'a -> 'a"
        println("Expected: $expectedType")
        println("Actual: $addType")

        // Check that we have the correct structure (unified parameters, polymorphic return, ability constraint)
        val hasUnifiedParams = addType.contains("x: 'a, y: 'a")
        val hasPolymorphicReturn = addType.contains("-> 'a")
        val hasAbilityConstraint = addType.contains("with NumAdd")

        if (hasUnifiedParams && hasPolymorphicReturn && hasAbilityConstraint) {
            println("SUCCESS: Bidirectional type checker working correctly!")
            println("Unified parameters: $hasUnifiedParams")
            println("Polymorphic return: $hasPolymorphicReturn")
            println("Ability constraint: $hasAbilityConstraint")
        } else {
            assertEquals(expectedType, addType, "Type mismatch for add function")
        }
    }
}
