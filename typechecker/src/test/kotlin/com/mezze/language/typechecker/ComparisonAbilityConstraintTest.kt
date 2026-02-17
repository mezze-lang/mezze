package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class ComparisonAbilityConstraintTest {
    
    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: MezzeParser
    
    @BeforeEach
    fun setUp() {
        typeChecker = TypeChecker()
        parser = MezzeParser()
    }
    
    @Test
    fun `test equality operators generate Eq constraint`() {
        val code = "let eq = {x, y} -> x == y"
        
        val program = parser.parse(code)
        val typedProgram = typeChecker.typeCheck(program).program
        
        // Should succeed and return Boolean
        assertNotNull(typedProgram)
        val eqBinding = typedProgram.items.filterIsInstance<LetBinding>().first { it.variableName == "eq" }
        assertTrue(eqBinding.inferredType is MezzeType.FunctionType)
        val functionType = eqBinding.inferredType as MezzeType.FunctionType
        val boolVariant = MezzeType.VariantType(
            constructors = mapOf("True" to null, "False" to null),
            rowVar = null
        )
        assertEquals(boolVariant, functionType.returnType)
    }
    
    @Test
    fun `test inequality operators generate Eq constraint`() {
        val code = "let neq = {x, y} -> x != y"
        
        val program = parser.parse(code)
        val typedProgram = typeChecker.typeCheck(program).program
        
        // Should succeed and return Boolean
        assertNotNull(typedProgram)
        val neqBinding = typedProgram.items.filterIsInstance<LetBinding>().first { it.variableName == "neq" }
        assertTrue(neqBinding.inferredType is MezzeType.FunctionType)
        val functionType = neqBinding.inferredType as MezzeType.FunctionType
        val boolVariant = MezzeType.VariantType(
            constructors = mapOf("True" to null, "False" to null),
            rowVar = null
        )
        assertEquals(boolVariant, functionType.returnType)
    }
    
    @Test
    fun `test ordering operators generate Ord constraint`() {
        val code = "let lt = {x, y} -> x < y"
        
        val program = parser.parse(code)
        val typedProgram = typeChecker.typeCheck(program).program
        
        // Should succeed and return Boolean
        assertNotNull(typedProgram)
        val ltBinding = typedProgram.items.filterIsInstance<LetBinding>().first { it.variableName == "lt" }
        assertTrue(ltBinding.inferredType is MezzeType.FunctionType)
        val functionType = ltBinding.inferredType as MezzeType.FunctionType
        val boolVariant = MezzeType.VariantType(
            constructors = mapOf("True" to null, "False" to null),
            rowVar = null
        )
        assertEquals(boolVariant, functionType.returnType)
    }
    
    @Test
    fun `test concrete type comparison validation for Int`() {
        val code = "1 == 2"
        
        val program = parser.parse(code)
        val typedProgram = typeChecker.typeCheck(program).program
        
        // Should succeed because Int has Eq ability
        assertNotNull(typedProgram)
    }
    
    @Test
    fun `test concrete type ordering validation for Int`() {
        val code = "1 < 2"
        
        val program = parser.parse(code)
        val typedProgram = typeChecker.typeCheck(program).program
        
        // Should succeed because Int has Ord ability
        assertNotNull(typedProgram)
    }
}
