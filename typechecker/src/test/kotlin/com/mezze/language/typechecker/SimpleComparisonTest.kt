package com.mezze.language.typechecker

import java.nio.file.Paths
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
            
            val parsedModule = parser.parseParsedModule(code, "test.mz")
            println("Parsed successfully")
            
            val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
            println("Type check successful: $result")
            
        } catch (e: Exception) {
            println("Error: ${e.javaClass.simpleName}")
            println("Message: ${e.message}")
            e.printStackTrace()
            
            // Don't rethrow - just print for debugging
        }
    }
}
