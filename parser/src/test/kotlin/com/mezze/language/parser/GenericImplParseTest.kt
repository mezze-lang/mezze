package com.mezze.language.parser

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import com.mezze.language.ast.*

class GenericImplParseTest {
    private val parser = MezzeParser()

    @Test
    fun `impl Eq for seq type without generics parses`() {
        val source = """
            impl Eq for ['a] where {
                eq = {r: ['a]} -> True
            }
        """.trimIndent()

        val program = parser.parse(source, desugar = false)
        assertEquals(1, program.items.size)
        val impl = program.items[0] as ImplAbilityDeclaration
        assertEquals("Eq", impl.abilityName)
        assertEquals(0, impl.abilityTypeArgs.size)
        assertEquals(1, impl.targetTypes.size)
        assertTrue(impl.targetTypes[0] is SeqTypeAnnotation)
    }

    @Test
    fun `impl Eq with generic for seq type parses`() {
        // impl Eq 'a for ['a] — 'a is the generic type variable
        val source = """
            impl Eq 'a for ['a] where {
                eq = {r: ['a]} -> True
            }
        """.trimIndent()

        val program = parser.parse(source, desugar = false)
        assertEquals(1, program.items.size)
        val impl = program.items[0] as ImplAbilityDeclaration
        assertEquals("Eq", impl.abilityName)
        assertEquals(1, impl.abilityTypeArgs.size)
        assertEquals("'a", (impl.abilityTypeArgs[0] as IdentifierTypeAnnotation).name)
        assertEquals(1, impl.targetTypes.size)
        assertTrue(impl.targetTypes[0] is SeqTypeAnnotation)
    }

    @Test
    fun `impl Eq with generic for Option parses`() {
        val source = """
            impl Eq 'a for Option 'a where {
                eq = {r: Option 'a} -> True
            }
        """.trimIndent()

        val program = parser.parse(source, desugar = false)
        assertEquals(1, program.items.size)
        val impl = program.items[0] as ImplAbilityDeclaration
        assertEquals("Eq", impl.abilityName)
        assertEquals(1, impl.abilityTypeArgs.size)
        assertEquals("'a", (impl.abilityTypeArgs[0] as IdentifierTypeAnnotation).name)
        // Target type: Option 'a as a single TypeApplicationAnnotation
        assertEquals(1, impl.targetTypes.size)
        assertTrue(impl.targetTypes[0] is TypeApplicationAnnotation)
    }

    @Test
    fun `impl Eq with two generics for Map parses`() {
        // Map has two type params — both declared as generics on the ability
        val source = """
            impl Eq 'k 'v for Map 'k 'v where {
                eq = {r} -> True
            }
        """.trimIndent()

        val program = parser.parse(source, desugar = false)
        assertEquals(1, program.items.size)
        val impl = program.items[0] as ImplAbilityDeclaration
        assertEquals("Eq", impl.abilityName)
        assertEquals(2, impl.abilityTypeArgs.size)
        assertEquals("'k", (impl.abilityTypeArgs[0] as IdentifierTypeAnnotation).name)
        assertEquals("'v", (impl.abilityTypeArgs[1] as IdentifierTypeAnnotation).name)
        // Target type: Map 'k 'v as a single TypeApplicationAnnotation
        assertEquals(1, impl.targetTypes.size)
        assertTrue(impl.targetTypes[0] is TypeApplicationAnnotation)
        val mapType = impl.targetTypes[0] as TypeApplicationAnnotation
        assertEquals("Map", mapType.constructor)
        assertEquals(2, mapType.args.size)
    }

    @Test
    fun `impl with multiple operations parses`() {
        val source = """
            impl Eq 'a for ['a] where {
                eq = {r} -> True,
                neq = {r} -> False
            }
        """.trimIndent()

        val program = parser.parse(source, desugar = false)
        assertEquals(1, program.items.size)
        val impl = program.items[0] as ImplAbilityDeclaration
        assertEquals(2, impl.operations.size)
        assertEquals("eq", impl.operations[0].name)
        assertEquals("neq", impl.operations[1].name)
    }

    @Test
    fun `impl operation with method-level constraint parses`() {
        val source = """
            impl Eq 'a for ['a] where {
                eq: {r: ['a]} with Eq 'a -> Bool = {r} -> True
            }
        """.trimIndent()

        val program = parser.parse(source, desugar = false)
        assertEquals(1, program.items.size)
        val impl = program.items[0] as ImplAbilityDeclaration
        assertEquals("Eq", impl.abilityName)
        assertEquals(1, impl.abilityTypeArgs.size)
        assertEquals(1, impl.operations.size)
        assertEquals("eq", impl.operations[0].name)
        assertEquals(1, impl.operations[0].abilityConstraints.size)
        assertEquals("Eq", impl.operations[0].abilityConstraints[0].abilityName)
    }

    @Test
    fun `impl operation with multiple method-level constraints parses`() {
        val source = """
            impl Inspect 'a for ['a] where {
                to_dbg_str: {} with Eq 'a, Inspect 'a -> Str = {} -> "[]"
            }
        """.trimIndent()

        val program = parser.parse(source, desugar = false)
        assertEquals(1, program.items.size)
        val impl = program.items[0] as ImplAbilityDeclaration
        assertEquals(1, impl.operations.size)
        assertEquals(2, impl.operations[0].abilityConstraints.size)
        assertEquals("Eq", impl.operations[0].abilityConstraints[0].abilityName)
        assertEquals("Inspect", impl.operations[0].abilityConstraints[1].abilityName)
    }

    @Test
    fun `impl operation without constraint has empty list`() {
        val source = """
            ability Eq for 'a where { eq: {r: 'a} -> Bool }
            impl Eq for Int where {
                eq = {r} -> True
            }
        """.trimIndent()

        val program = parser.parse(source, desugar = false)
        val impl = program.items[1] as ImplAbilityDeclaration
        assertEquals(0, impl.operations[0].abilityConstraints.size)
    }

    @Test
    fun `simple impl without generics still works`() {
        val source = """
            ability Eq for 'a where { eq: {r: 'a} -> Bool }
            impl Eq for Int where {
                eq = {r} -> True
            }
        """.trimIndent()

        val program = parser.parse(source, desugar = false)
        assertEquals(2, program.items.size)
    }
}
