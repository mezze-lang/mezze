package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class BasicEffectPropagationTest {
    
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
    fun testEffectWithNestedEffects() {
        val source = """
            effect Log where {
              log : {msg: Str} -> {} <Log>
            }

            effect Database where {
              query : {sql: Str} -> Str <Database>
            }

            let useDatabase : {query: Str} -> Str <Database> = {query} ->
              Database.query {sql = query}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val useDatabaseType = result.getVariableType("useDatabase")
        assertNotNull(useDatabaseType)
        assertTrue(useDatabaseType is MezzeType.FunctionType)

        val functionType = useDatabaseType as MezzeType.FunctionType
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
              log : {msg: Str} -> {} <Log>
            }

            let logSomething : {msg: Str} -> {} <Log> = {msg} ->
              Log.log {msg = msg}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val logFunctionType = result.getVariableType("logSomething")
        assertNotNull(logFunctionType)
        assertTrue(logFunctionType is MezzeType.FunctionType)

        val functionType = logFunctionType as MezzeType.FunctionType
        assertNotNull(functionType.effects)

        // Should have Log effect
        val effects = functionType.effects!!.effects
        assertTrue(effects.contains("Log"), "Should contain Log effect")
    }
}
