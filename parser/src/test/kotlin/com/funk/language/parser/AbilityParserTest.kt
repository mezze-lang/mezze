package com.funk.language.parser

import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AbilityParserTest {

    private val parser = FunkParser()

    @Test
    fun testSimpleAbilityDeclaration() {
        val source = """
            ability Encoder 'a where {
              encode : {val: 'a} -> String
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val ability = program.items[0] as AbilityDeclaration
        assertEquals("Encoder", ability.name)
        assertEquals(listOf("'a"), ability.typeParams)
        assertEquals(0, ability.dependencies.size)
        assertEquals(1, ability.operations.size)

        val operation = ability.operations[0]
        assertEquals("encode", operation.name)
        assertEquals("String", (operation.returnType as IdentifierTypeAnnotation).name)
    }

    @Test
    fun testAbilityWithMultipleOperations() {
        val source = """
            ability Show 'a where {
              show : {val: 'a} -> String,
              debug : {val: 'a} -> String,
            }
        """.trimIndent()

        val program = parser.parse(source)
        val ability = program.items[0] as AbilityDeclaration

        assertEquals("Show", ability.name)
        assertEquals(listOf("'a"), ability.typeParams)
        assertEquals(2, ability.operations.size)

        assertEquals("show", ability.operations[0].name)
        assertEquals("debug", ability.operations[1].name)
    }

    @Test
    fun testSimpleImplAbilityDeclaration() {
        val source = """
            impl Encoder Int where {
              encode : {val: Int} -> String = {val} -> "number"
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val impl = program.items[0] as ImplAbilityDeclaration
        assertEquals("Encoder", impl.abilityName)
        assertEquals(1, impl.targetTypes.size)
        assertEquals("Int", (impl.targetTypes[0] as IdentifierTypeAnnotation).name)
        assertEquals(1, impl.operations.size)

        val operation = impl.operations[0]
        assertEquals("encode", operation.name)
        assertEquals("String", (operation.returnType as IdentifierTypeAnnotation).name)
        assertTrue(operation.implementation is LambdaExpression)
    }

    @Test
    fun testImplAbilityWithMultipleOperations() {
        val source = """
            impl Show String where {
              show : {val: String} -> String = {val} -> val,
              debug : {val: String} -> String = {val} -> "\"$(val)\"",
            }
        """.trimIndent()

        val program = parser.parse(source)
        val impl = program.items[0] as ImplAbilityDeclaration

        assertEquals("Show", impl.abilityName)
        assertEquals("String", (impl.targetTypes[0] as IdentifierTypeAnnotation).name)
        assertEquals(2, impl.operations.size)

        assertEquals("show", impl.operations[0].name)
        assertEquals("debug", impl.operations[1].name)
    }

    @Test
    fun testAbilityAndImplTogether() {
        val source = """
            ability Encoder 'a where {
              encode : {val: 'a} -> String
            }

            impl Encoder Int where {
              encode : {val: Int} -> String = {val} -> intToString {val = val}
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(2, program.items.size)

        val ability = program.items[0] as AbilityDeclaration
        assertEquals("Encoder", ability.name)

        val impl = program.items[1] as ImplAbilityDeclaration
        assertEquals("Encoder", impl.abilityName)
        assertEquals("Int", (impl.targetTypes[0] as IdentifierTypeAnnotation).name)
    }

    @Test
    fun testComplexAbilityExample() {
        val source = """
            ability Serializer 'a where {
              serialize : {val: 'a} -> String,
              deserialize : {str: String} -> 'a,
            }

            impl Serializer Int where {
              serialize : {val: Int} -> String = {val} -> intToString {val = val},
              deserialize : {str: String} -> Int = {str} -> stringToInt {str = str},
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(2, program.items.size)

        val ability = program.items[0] as AbilityDeclaration
        assertEquals("Serializer", ability.name)
        assertEquals(2, ability.operations.size)
        assertEquals("serialize", ability.operations[0].name)
        assertEquals("deserialize", ability.operations[1].name)

        val impl = program.items[1] as ImplAbilityDeclaration
        assertEquals("Serializer", impl.abilityName)
        assertEquals(2, impl.operations.size)
    }

    @Test
    fun testAbilityWithMultipleTypeParameters() {
        val source = """
            ability KVStore 'a 'b where {
              store : {key: 'a, value: 'b} -> {}
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val ability = program.items[0] as AbilityDeclaration
        assertEquals("KVStore", ability.name)
        assertEquals(listOf("'a", "'b"), ability.typeParams)
        assertEquals(1, ability.operations.size)

        val operation = ability.operations[0]
        assertEquals("store", operation.name)
    }

    @Test
    fun testImplWithMultipleTypes() {
        val source = """
            impl KVStore String Int where {
              store : {key: String, value: Int} -> {} = {key, value} -> {}
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val impl = program.items[0] as ImplAbilityDeclaration
        assertEquals("KVStore", impl.abilityName)
        assertEquals(2, impl.targetTypes.size)
        assertEquals("String", (impl.targetTypes[0] as IdentifierTypeAnnotation).name)
        assertEquals("Int", (impl.targetTypes[1] as IdentifierTypeAnnotation).name)
        assertEquals(1, impl.operations.size)
    }
}
