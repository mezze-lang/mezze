package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ExplicitEffectPropagationTest {
    
    private val parser = FunkParser()
    private val typeChecker = TypeChecker()
    
    @Test
    fun testExplicitEffectPropagation() {
        val source = """
            effect Log {
              log : {msg: String} -> {}
            }
            
            effect Database {
              query : {sql: String} -> String <Log>
            }
            
            # Explicit effect annotation - should include both Database and Log
            let useDatabase : {query: String} -> String <Database, Log> = {query} ->
              Database.query {sql = query}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        
        // The useDatabase function should be at index 2
        val useDatabaseDecl = typedProgram.items[2] as LetBinding
        
        assertNotNull(useDatabaseDecl.inferredType)
        assertTrue(useDatabaseDecl.inferredType is FunkType.FunctionType)
        
        val functionType = useDatabaseDecl.inferredType as FunkType.FunctionType
        assertNotNull(functionType.effects)
        
        // Should have both Database and Log effects
        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("Database"), "Should contain Database effect")
        assertTrue(effects.contains("Log"), "Should contain Log effect")
        assertEquals(2, effects.size, "Should have exactly 2 effects")
    }
    
    @Test
    fun testInferredEffectPropagation() {
        val source = """
            effect Log {
              log : {msg: String} -> {}
            }
            
            # Function without explicit effect annotation - should infer Log effect
            let logSomething : {msg: String} -> {} = {msg} ->
              Log.log {msg = msg}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        
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
