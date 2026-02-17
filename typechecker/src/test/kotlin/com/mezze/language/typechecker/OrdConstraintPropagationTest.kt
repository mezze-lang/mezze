package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class OrdConstraintPropagationTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `test Ord constraint with direct parameter comparison`() {
        val source = """
            let test = {x, y} -> x > y

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertTrue(letBinding!!.inferredType is MezzeType.FunctionType)

        val funcType = letBinding.inferredType as MezzeType.FunctionType
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
        System.setProperty("mezze.debug.constraints", "true")

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertTrue(letBinding!!.inferredType is MezzeType.FunctionType)

        val funcType = letBinding.inferredType as MezzeType.FunctionType

        // Should have NumAdd, NumSub, and Ord constraints
        val hasNumAdd = funcType.abilityConstraints.any { it.abilityName == "NumAdd" }
        val hasNumSub = funcType.abilityConstraints.any { it.abilityName == "NumSub" }
        val hasOrd = funcType.abilityConstraints.any { it.abilityName == "Ord" }

        assertTrue(hasNumAdd, "Expected NumAdd constraint")
        assertTrue(hasNumSub, "Expected NumSub constraint")
        assertTrue(hasOrd, "Expected Ord constraint for let-bound variable comparison, got: ${funcType.abilityConstraints}")
    }
}
