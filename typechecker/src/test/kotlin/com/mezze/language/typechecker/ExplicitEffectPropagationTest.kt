package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class ExplicitEffectPropagationTest {
    
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
    fun testExplicitEffectPropagation() {
        val source = """
            effect Log where {
              log : {msg: Str} -> {} <Log>
            }
            
            effect Database where {
              query : {sql: Str} -> Str <Database>
            }
            
            # Explicit effect annotation - should include only Database
            let useDatabase : {query: Str} -> Str <Database> = {query} ->
              Database.query {sql = query}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        // The useDatabase function should be at index 2
        val useDatabaseDecl = typedProgram.items[2] as LetBinding
        
        assertNotNull(useDatabaseDecl.inferredType)
        assertTrue(useDatabaseDecl.inferredType is MezzeType.FunctionType)
        
        val functionType = useDatabaseDecl.inferredType as MezzeType.FunctionType
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
              log : {msg: Str} -> {} <Log>
            }
            
            # Function with explicit effect annotation
            let logSomething : {msg: Str} -> {} <Log> = {msg} ->
              Log.log {msg = msg}
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        // The logSomething function should be at index 1
        val logFunctionDecl = typedProgram.items[1] as LetBinding
        
        assertNotNull(logFunctionDecl.inferredType)
        assertTrue(logFunctionDecl.inferredType is MezzeType.FunctionType)
        
        val functionType = logFunctionDecl.inferredType as MezzeType.FunctionType
        
        // This is the key test - does effect inference work?
        if (functionType.effects != null) {
            val effects = functionType.effects!!.effects
            assertTrue(effects.contains("Log"), "Should infer Log effect")
        } else {
            fail("Effect inference should have detected Log effect")
        }
    }
}
