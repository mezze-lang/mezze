package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.typedast.TypedAbilityDecl
import com.mezze.language.typedast.TypedEffectDecl
import com.mezze.language.typedast.TypedAbilityImpl
import com.mezze.language.typedast.TypedPerformerDecl
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

class BasicAbilitiesTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun testSimpleAbilityDeclaration() {
        val source = """
            ability Show for 'a where {
              show : {val: 'a} -> Str
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Verify ability declaration is processed via TypedModule
        val abilityDecl = typedProgram.typedModule?.abilityDecls?.find { it.name == "Show" }
        assertNotNull(abilityDecl)
        assertEquals("Show", abilityDecl!!.name)
        assertEquals(1, abilityDecl.operations.size)
        assertEquals("show", abilityDecl.operations[0].name)
    }

    @Test
    fun testSimpleAbilityImplementation() {
        val source = """
            use std::int

            ability Show for 'a where {
              show : {val: 'a} -> Str
            }

            impl Show for Int where {
              show : {val: Int} -> Str = {val} -> int::to_str {n = val}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Verify implementation is processed via TypedModule
        val implDecl = typedProgram.typedModule?.abilityImpls?.firstOrNull()
        assertNotNull(implDecl)
        assertTrue(implDecl!!.operations.containsKey("show"))
    }

    @Test
    fun testMultiParameterAbilityDeclaration() {
        val source = """
            ability Convert 'b for 'a where {
              convert : {val: 'a} -> 'b
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Verify multi-parameter ability declaration
        val convertDecl = typedProgram.typedModule?.abilityDecls?.find { it.name == "Convert" }
        assertNotNull(convertDecl)
        assertEquals(1, convertDecl!!.operations.size)
        assertEquals("convert", convertDecl.operations[0].name)
    }

    @Test
    fun testAbilityWithMethodLevelConstraints() {
        val source = """
            ability Eq for 'a where {
              equals : {left: 'a, right: 'a} -> Bool
            }

            ability Ord for 'a where {
              compare : {left: 'a, right: 'a} with Eq 'a -> Int
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Verify Ord ability was processed
        val ordDecl = typedProgram.typedModule?.abilityDecls?.find { it.name == "Ord" }
        assertNotNull(ordDecl)
        assertEquals(1, ordDecl!!.operations.size)
        assertEquals("compare", ordDecl.operations[0].name)
    }

    @Test
    fun testSimpleEffectDeclaration() {
        val source = """
            effect Log where {
              log : {message: Str} -> {} <Log>
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Verify effect declaration is processed via TypedModule
        val effectDecl = typedProgram.typedModule?.effectDecls?.find { it.name == "Log" }
        assertNotNull(effectDecl)
        assertEquals("Log", effectDecl!!.name)
        assertEquals(1, effectDecl.operations.size)
        assertEquals("log", effectDecl.operations[0].name)
    }

    @Test
    fun testSimpleEffectImplementation() {
        val source = """
            effect Log where {
              log : {message: Str} -> {} <Log>
            }

            impl Log for ConsoleLogger where {
              log : {message: Str} -> {} = {message} -> {}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Verify performer (effect impl) is processed via TypedModule
        val performerDecl = typedProgram.typedModule?.performerDecls?.firstOrNull()
        assertNotNull(performerDecl)
        assertEquals("ConsoleLogger", performerDecl!!.name)
        assertTrue(performerDecl.operations.containsKey("log"))
    }

    @Test
    fun testBasicLetBinding() {
        val source = """
            let x = 42
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Verify let binding is processed
        val xType = typedProgram.getVariableType("x")
        assertNotNull(xType)
        assertTrue(xType is MezzeType.IntType)
    }
}
