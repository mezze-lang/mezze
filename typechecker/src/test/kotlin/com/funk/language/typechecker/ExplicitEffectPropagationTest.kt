package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class ExplicitEffectPropagationTest {
    
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = FunkParser()
        typeChecker = TypeChecker()
    }
    
    @Test
    fun testExplicitEffectPropagation() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }
            
            effect Database where {
              query : {sql: String} -> String <Database>
            }
            
            # Explicit effect annotation - should include only Database
            let useDatabase : {query: String} -> String <Database> = {query} ->
              Database.query {sql = query}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        // The useDatabase function should be at index 2
        val useDatabaseDecl = typedProgram.items[2] as LetBinding
        
        assertNotNull(useDatabaseDecl.inferredType)
        assertTrue(useDatabaseDecl.inferredType is FunkType.FunctionType)
        
        val functionType = useDatabaseDecl.inferredType as FunkType.FunctionType
        assertNotNull(functionType.effects)
        
        // Should have only Database effect
        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("Database"), "Should contain Database effect")
        assertEquals(1, effects.size, "Should have exactly 1 effect")
    }
    
    @Test
    fun testInferredEffectPropagation() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }
            
            # Function with explicit effect annotation
            let logSomething : {msg: String} -> {} <Log> = {msg} ->
              Log.log {msg = msg}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        // The logSomething function should be at index 1
        val logFunctionDecl = typedProgram.items[1] as LetBinding
        
        assertNotNull(logFunctionDecl.inferredType)
        assertTrue(logFunctionDecl.inferredType is FunkType.FunctionType)
        
        val functionType = logFunctionDecl.inferredType as FunkType.FunctionType
        
        // This is the key test - does effect inference work?
        if (functionType.effects != null) {
            val effects = functionType.effects!!.effects
            assertTrue(effects.contains("Log"), "Should infer Log effect")
        } else {
            fail("Effect inference should have detected Log effect")
        }
    }
}
