package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class SimpleEffectPropagationTest {

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
    fun testSimpleEffectDeclaration() {
        val source = """
            effect Log where {
              log : {msg: Str} -> {} <Log>
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Just check that it parses and type checks successfully
        assertNotNull(typedProgram)
        val effectDecl = typedProgram.typedModule?.effectDecls?.find { it.name == "Log" }
        assertNotNull(effectDecl)
    }

    @Test
    fun testBasicEffectWithReturnEffect() {
        val source = """
            effect Log where {
              log : {msg: Str} -> {} <Log>
            }

            effect Database where {
              query : {sql: Str} -> Str <Database>
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check that both effects are declared
        assertNotNull(typedProgram)
        val effectDecls = typedProgram.typedModule?.effectDecls ?: emptyList()
        assertTrue(effectDecls.any { it.name == "Log" }, "Log effect should be declared")
        assertTrue(effectDecls.any { it.name == "Database" }, "Database effect should be declared")
    }

    @Test
    fun testDebugProgramStructure() {
        val source = """
            effect Log where {
              log : {msg: Str} -> {} <Log>
            }

            let testFunction : {x: Int} -> Int = {x} -> x
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Find the function via TypedModule
        val testFunctionType = typedProgram.getVariableType("testFunction")

        assertNotNull(testFunctionType, "Should find testFunction")
        assertTrue(testFunctionType is MezzeType.FunctionType)
    }
}
