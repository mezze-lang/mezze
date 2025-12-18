package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BasicEffectPropagationTest {
    
    private val parser = FunkParser()
    private val typeChecker = TypeChecker()
    
    @Test
    fun testEffectWithNestedEffects() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }

            effect Database where {
              query : {sql: String} -> String <Database>
            }

            let useDatabase : {query: String} -> String <Database> = {query} ->
              Database.query {sql = query}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // The useDatabase function should be the last item (index 2: Log, Database, useDatabase)
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
    fun testSimpleEffectCall() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }

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
        assertNotNull(functionType.effects)

        // Should have Log effect
        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("Log"), "Should contain Log effect")
    }
}
