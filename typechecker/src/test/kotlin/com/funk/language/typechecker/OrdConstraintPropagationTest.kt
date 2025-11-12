package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class OrdConstraintPropagationTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    @Test
    fun `test Ord constraint with direct parameter comparison`() {
        val source = """
            let test = {x, y} -> x > y

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertTrue(letBinding!!.inferredType is FunkType.FunctionType)

        val funcType = letBinding.inferredType as FunkType.FunctionType
        // Should have Ord constraint
        val hasOrd = funcType.abilityConstraints.any { it.abilityName == "Ord" }
        assertTrue(hasOrd, "Expected Ord constraint for direct parameter comparison")
    }

    @Test
    fun `test Ord constraint with let-bound variables`() {
        val source = """
            let test = {x, y} -> do
              let sum = x + y
              let diff = x - y
              sum > diff

            test
        """.trimIndent()

        // Enable debug output
        System.setProperty("funk.debug.constraints", "true")

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertTrue(letBinding!!.inferredType is FunkType.FunctionType)

        val funcType = letBinding.inferredType as FunkType.FunctionType

        // Should have NumAdd, NumSub, and Ord constraints
        val hasNumAdd = funcType.abilityConstraints.any { it.abilityName == "NumAdd" }
        val hasNumSub = funcType.abilityConstraints.any { it.abilityName == "NumSub" }
        val hasOrd = funcType.abilityConstraints.any { it.abilityName == "Ord" }

        assertTrue(hasNumAdd, "Expected NumAdd constraint")
        assertTrue(hasNumSub, "Expected NumSub constraint")
        assertTrue(hasOrd, "Expected Ord constraint for let-bound variable comparison, got: ${funcType.abilityConstraints}")
    }
}
