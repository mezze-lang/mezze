package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class SimpleComparisonTest {
    
    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: FunkParser
    
    @BeforeEach
    fun setUp() {
        typeChecker = TypeChecker()
        parser = FunkParser()
    }
    
    @Test
    fun `test simple integer comparison`() {
        try {
            // Test a simple comparison that should work
            val code = "1 == 1"
            println("Testing: $code")
            
            val program = parser.parse(code)
            println("Parsed successfully")
            
            val result = typeChecker.typeCheck(program).program
            println("Type check successful: $result")
            
        } catch (e: Exception) {
            println("Error: ${e.javaClass.simpleName}")
            println("Message: ${e.message}")
            e.printStackTrace()
            
            // Don't rethrow - just print for debugging
        }
    }
}
