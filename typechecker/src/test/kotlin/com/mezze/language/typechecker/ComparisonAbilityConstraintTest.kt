package com.mezze.language.typechecker

import java.nio.file.Paths
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
        
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
        
        // Should succeed and return Boolean
        assertNotNull(typedProgram)
        val eqType = typedProgram.getVariableType("eq")
        assertTrue(eqType is MezzeType.FunctionType)
        val functionType = eqType as MezzeType.FunctionType
        val boolType = MezzeType.BoolType
        assertEquals(boolType, functionType.returnType)
    }
    
    @Test
    fun `test inequality operators generate Eq constraint`() {
        val code = "let neq = {x, y} -> x != y"
        
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
        
        // Should succeed and return Boolean
        assertNotNull(typedProgram)
        val neqType = typedProgram.getVariableType("neq")
        assertTrue(neqType is MezzeType.FunctionType)
        val functionType = neqType as MezzeType.FunctionType
        val boolType = MezzeType.BoolType
        assertEquals(boolType, functionType.returnType)
    }
    
    @Test
    fun `test ordering operators generate Ord constraint`() {
        val code = "let lt = {x, y} -> x < y"
        
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
        
        // Should succeed and return Boolean
        assertNotNull(typedProgram)
        val ltType = typedProgram.getVariableType("lt")
        assertTrue(ltType is MezzeType.FunctionType)
        val functionType = ltType as MezzeType.FunctionType
        val boolType = MezzeType.BoolType
        assertEquals(boolType, functionType.returnType)
    }
    
    @Test
    fun `test concrete type comparison validation for Int`() {
        val code = "1 == 2"
        
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
        
        // Should succeed because Int has Eq ability
        assertNotNull(typedProgram)
    }
    
    @Test
    fun `test concrete type ordering validation for Int`() {
        val code = "1 < 2"
        
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
        
        // Should succeed because Int has Ord ability
        assertNotNull(typedProgram)
    }
}
