package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class ComparisonConstraintTest {
    
    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: MezzeParser
    
    @BeforeEach
    fun setUp() {
        typeChecker = TypeChecker()
        parser = MezzeParser()
    }
    
    @Test
    fun `debug comparison operation`() {
        val code = "let compare = {x, y} -> x == y"

        try {
            println("Parsing code: $code")
            val parsedModule = parser.parseParsedModule(code, "test.mz")
            println("Parsed program: $parsedModule")

            println("Type checking...")
            val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
            println("Type checked successfully")

            // Check the result
            val compareType = result.getVariableType("compare")
            println("Inferred type: $compareType")

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
            val parsedModule = parser.parseParsedModule(code, "test.mz")
            println("Parsed program: $parsedModule")
            
            println("Type checking...")
            val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
            println("Type checked successfully: $typedProgram")
            
        } catch (e: Exception) {
            println("Error: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
}
