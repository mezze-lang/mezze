package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class BuiltinTypesTest {
    
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
    fun testOptionTypeIsBuiltIn() {
        val source = """
            let x = Some { val = 42 }
            x
        """.trimIndent()
        
        val program = parser.parse(source)
        
        // Should not throw an exception if Option type is available
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
    }
    
    @Test
    fun testListTypeIsBuiltIn() {
        val source = """
            let x = [1, 2, 3]
            x
        """.trimIndent()
        
        val program = parser.parse(source)
        
        // Should not throw an exception if List type is available
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
    }
    
    @Test
    fun testResultTypeIsBuiltIn() {
        val source = """
            let x = Ok { val = 42 }
            x
        """.trimIndent()
        
        val program = parser.parse(source)
        
        // Should not throw an exception if Result type is available
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
    }
    
    @Test
    fun testOrderingTypeIsBuiltIn() {
        val source = """
            let x = LT
            x
        """.trimIndent()
        
        val program = parser.parse(source)
        
        // Should not throw an exception if Ordering type is available
        assertDoesNotThrow {
            typeChecker.typeCheck(program).program
        }
    }
}
