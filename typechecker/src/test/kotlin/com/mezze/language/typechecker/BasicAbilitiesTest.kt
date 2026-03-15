package com.mezze.language.typechecker

import com.mezze.language.ast.*
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
            ability Show 'a where {
              show : {val: 'a} -> Str
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Verify ability declaration is processed
        val abilityDecl = typedProgram.items.find { it is AbilityDeclaration } as AbilityDeclaration?
        assertNotNull(abilityDecl)
        assertEquals("Show", abilityDecl.name)
        assertEquals(listOf("'a"), abilityDecl.typeParams)
        assertEquals(1, abilityDecl.operations.size)
        assertEquals("show", abilityDecl.operations[0].name)
    }

    @Test
    fun testSimpleAbilityImplementation() {
        val source = """
            use std::int

            ability Show 'a where {
              show : {val: 'a} -> Str
            }

            impl Show Int where {
              show : {val: Int} -> Str = {val} -> int::to_str {n = val}
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Verify implementation is processed  
        val implDecl = typedProgram.items.find { it is ImplAbilityDeclaration } as ImplAbilityDeclaration?
        assertNotNull(implDecl)
        assertEquals("Show", implDecl.ability.localName)
        assertEquals(1, implDecl.targetTypes.size)
        assertEquals(1, implDecl.operations.size)
    }

    @Test
    fun testMultiParameterAbilityDeclaration() {
        val source = """
            ability Convert 'a 'b where {
              convert : {val: 'a} -> 'b
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Verify multi-parameter ability declaration
        val convertDecl = typedProgram.items.find { 
            it is AbilityDeclaration && it.name == "Convert" 
        } as AbilityDeclaration?
        assertNotNull(convertDecl)
        assertEquals(listOf("'a", "'b"), convertDecl.typeParams)
        assertEquals(1, convertDecl.operations.size)
    }

    @Test
    fun testAbilityWithMethodLevelConstraints() {
        val source = """
            ability Eq 'a where {
              equals : {left: 'a, right: 'a} -> Boolean
            }

            ability Ord 'a where {
              compare : {left: 'a, right: 'a} with Eq 'a -> Int
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Verify Ord ability has method-level constraint (not ability-level dependency)
        val ordDecl = typedProgram.items.find {
            it is AbilityDeclaration && it.name == "Ord"
        } as AbilityDeclaration?
        assertNotNull(ordDecl)
        // No ability-level dependencies
        assertTrue(ordDecl.dependencies.isEmpty())
        // But method has constraint
        assertEquals(1, ordDecl.operations.first().abilityConstraints.size)
        assertEquals("Eq", ordDecl.operations.first().abilityConstraints.first().abilityName)
    }

    @Test
    fun testSimpleEffectDeclaration() {
        val source = """
            effect Log where {
              log : {message: Str} -> Unit <Log>
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Verify effect declaration is processed
        val effectDecl = typedProgram.items.find { it is EffectDeclaration } as EffectDeclaration?
        assertNotNull(effectDecl)
        assertEquals("Log", effectDecl.name)
        assertEquals(1, effectDecl.operations.size)
        assertEquals("log", effectDecl.operations[0].name)
    }

    @Test
    fun testSimpleEffectImplementation() {
        val source = """
            effect Log where {
              log : {message: Str} -> Unit <Log>
            }

            impl Log for ConsoleLogger where {
              log : {message: Str} -> Unit = {message} -> {}
            }
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Verify implementation is processed
        val implDecl = typedProgram.items.find { it is ImplDeclaration } as ImplDeclaration?
        assertNotNull(implDecl)
        assertEquals("Log", implDecl.effect.localName)
        assertEquals("ConsoleLogger", implDecl.performerName)
        assertEquals(1, implDecl.operations.size)
    }

    @Test
    fun testBasicLetBinding() {
        val source = """
            let x = 42
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Verify let binding is processed
        val letBinding = typedProgram.items.find { it is LetBinding } as LetBinding?
        assertNotNull(letBinding)
        assertEquals("x", letBinding.variableName)
        assertTrue(letBinding.inferredType is MezzeType.IntType)
    }
}
