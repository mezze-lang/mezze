package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class ComparisonConstraintTest {
    
    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: FunkParser
    
    @BeforeEach
    fun setUp() {
        typeChecker = TypeChecker()
        parser = FunkParser()
    }
    
    @Test
    fun `debug comparison operation`() {
        val code = "let compare = {x, y} -> x == y"
        
        try {
            println("Parsing code: $code")
            val program = parser.parse(code)
            println("Parsed program: $program")
            
            println("Type checking...")
            val typedProgram = typeChecker.typeCheck(program).program
            println("Type checked successfully")
            
            // Check the result
            val compareBinding = typedProgram.items.filterIsInstance<LetBinding>().firstOrNull { it.variableName == "compare" }
            println("Compare binding: $compareBinding")
            println("Inferred type: ${compareBinding?.inferredType}")
            
        } catch (e: Exception) {
            println("Error: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
    
    @Test
    fun `debug simple comparison`() {
        val code = "1 == 2"
        
        try {
            println("Parsing code: $code")
            val program = parser.parse(code)
            println("Parsed program: $program")
            
            println("Type checking...")
            val typedProgram = typeChecker.typeCheck(program).program
            println("Type checked successfully: $typedProgram")
            
        } catch (e: Exception) {
            println("Error: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
}