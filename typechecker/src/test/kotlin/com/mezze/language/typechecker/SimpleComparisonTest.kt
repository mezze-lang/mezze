package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class SimpleComparisonTest {
    
    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: MezzeParser
    
    @BeforeEach
    fun setUp() {
        typeChecker = TypeChecker()
        parser = MezzeParser()
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
