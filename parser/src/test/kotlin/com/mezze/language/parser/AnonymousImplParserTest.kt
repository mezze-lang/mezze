package com.mezze.language.parser

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AnonymousImplParserTest {

    private val parser = MezzeParser()

    @Test
    fun `test simple anonymous impl`() {
        val source = """
            impl Int where {
                double = {} -> 42
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(1, program.items.size)

        val impl = program.items[0] as ImplAbilityDeclaration
        assertTrue(impl.isInherent)
        assertEquals(1, impl.targetTypes.size)
        assertEquals("Int", (impl.targetTypes[0] as IdentifierTypeAnnotation).name)
        assertEquals(1, impl.operations.size)
        assertEquals("double", impl.operations[0].name)
    }

    @Test
    fun `test anonymous impl with multiple methods`() {
        val source = """
            impl Int where {
                double = {} -> 42,
                triple = {} -> 63
            }
        """.trimIndent()

        val program = parser.parse(source)
        val impl = program.items[0] as ImplAbilityDeclaration

        assertTrue(impl.isInherent)
        assertEquals(2, impl.operations.size)
        assertEquals("double", impl.operations[0].name)
        assertEquals("triple", impl.operations[1].name)
    }

    @Test
    fun `test anonymous impl with method taking arguments`() {
        val source = """
            impl Str where {
                pad = {n: Int} -> "padded"
            }
        """.trimIndent()

        val program = parser.parse(source)
        val impl = program.items[0] as ImplAbilityDeclaration

        assertTrue(impl.isInherent)
        assertEquals("Str", (impl.targetTypes[0] as IdentifierTypeAnnotation).name)
        assertEquals(1, impl.operations.size)
        assertEquals("pad", impl.operations[0].name)
    }

    @Test
    fun `test anonymous impl with type annotation on method`() {
        val source = """
            impl Int where {
                to_float: {} -> Float = {} -> 1.0
            }
        """.trimIndent()

        val program = parser.parse(source)
        val impl = program.items[0] as ImplAbilityDeclaration

        assertTrue(impl.isInherent)
        assertEquals(1, impl.operations.size)
        val op = impl.operations[0]
        assertEquals("to_float", op.name)
        assertNotNull(op.returnType)
        assertEquals("Float", (op.returnType as IdentifierTypeAnnotation).name)
    }

    @Test
    fun `test anonymous impl with trailing comma`() {
        val source = """
            impl Bool where {
                to_str = {} -> "true",
            }
        """.trimIndent()

        val program = parser.parse(source)
        val impl = program.items[0] as ImplAbilityDeclaration

        assertTrue(impl.isInherent)
        assertEquals(1, impl.operations.size)
        assertEquals("to_str", impl.operations[0].name)
    }

    @Test
    fun `test anonymous impl coexists with ability declarations`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where {
                show = {} -> "int"
            }

            impl Int where {
                double = {} -> 42
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(3, program.items.size)

        assertTrue(program.items[0] is AbilityDeclaration)

        val namedImpl = program.items[1] as ImplAbilityDeclaration
        assertFalse(namedImpl.isInherent)
        assertEquals("Show", namedImpl.abilityName)

        val anonImpl = program.items[2] as ImplAbilityDeclaration
        assertTrue(anonImpl.isInherent)
    }

    @Test
    fun `test anonymous impl for user-defined type`() {
        val source = """
            type Point = {x: Int, y: Int}

            impl Point where {
                magnitude = {} -> 0
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(2, program.items.size)

        val impl = program.items[1] as ImplAbilityDeclaration
        assertTrue(impl.isInherent)
        assertEquals("Point", (impl.targetTypes[0] as IdentifierTypeAnnotation).name)
    }

    @Test
    fun `test anonymous impl has empty forallVars`() {
        val source = """
            impl Float where {
                to_int = {} -> 0
            }
        """.trimIndent()

        val program = parser.parse(source)
        val impl = program.items[0] as ImplAbilityDeclaration

        assertEquals(emptyList<String>(), impl.forallVars)
        assertTrue(impl.associatedTypeBindings.isEmpty())
    }

    @Test
    fun `test anonymous impl has source location`() {
        val source = """
            impl Int where {
                method = {} -> 0
            }
        """.trimIndent()

        val program = parser.parse(source)
        val impl = program.items[0] as ImplAbilityDeclaration

        assertNotNull(impl.sourceLocation)
    }

    @Test
    fun `test anonymous impl with complex method body`() {
        val source = """
            impl Int where {
                is_positive = {} -> it > 0,
                is_negative = {} -> it < 0,
                sign = {} -> if it > 0 then 1 else if it < 0 then -1 else 0
            }
        """.trimIndent()

        val program = parser.parse(source)
        val impl = program.items[0] as ImplAbilityDeclaration

        assertTrue(impl.isInherent)
        assertEquals(3, impl.operations.size)
        assertEquals("is_positive", impl.operations[0].name)
        assertEquals("is_negative", impl.operations[1].name)
        assertEquals("sign", impl.operations[2].name)
    }

    @Test
    fun `test multiple anonymous impls for different types`() {
        val source = """
            impl Int where {
                double = {} -> 42
            }

            impl Str where {
                shout = {} -> "HELLO"
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(2, program.items.size)

        val intImpl = program.items[0] as ImplAbilityDeclaration
        assertTrue(intImpl.isInherent)
        assertEquals("Int", (intImpl.targetTypes[0] as IdentifierTypeAnnotation).name)

        val strImpl = program.items[1] as ImplAbilityDeclaration
        assertTrue(strImpl.isInherent)
        assertEquals("Str", (strImpl.targetTypes[0] as IdentifierTypeAnnotation).name)
    }

    @Test
    fun `test anonymous impl with native function call`() {
        val source = """
            impl Int where {
                to_str = {} -> __native_int_to_str {n = it}
            }
        """.trimIndent()

        val program = parser.parse(source)
        val impl = program.items[0] as ImplAbilityDeclaration

        assertTrue(impl.isInherent)
        assertEquals(1, impl.operations.size)
        assertEquals("to_str", impl.operations[0].name)
    }

    // ========================================================================
    // SEALED TYPE PROPERTIES
    // ========================================================================

    @Test
    fun `inherent impl target is ImplTarget Inherent`() {
        val source = """
            impl Int where {
                foo = {} -> 0
            }
        """.trimIndent()

        val program = parser.parse(source)
        val impl = program.items[0] as ImplAbilityDeclaration

        assertTrue(impl.target is ImplTarget.Inherent)
        assertNull(impl.abilityName)
        assertNull(impl.abilityRef)
    }

    @Test
    fun `named impl target is ImplTarget Named`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where {
                show = {} -> "int"
            }
        """.trimIndent()

        val program = parser.parse(source)
        val impl = program.items[1] as ImplAbilityDeclaration

        assertTrue(impl.target is ImplTarget.Named)
        assertEquals("Show", impl.abilityName)
        assertNotNull(impl.abilityRef)
        assertFalse(impl.isInherent)
    }

    @Test
    fun `inherent impl for variant type`() {
        val source = """
            type Color = | Red, Green, Blue |

            impl Color where {
                is_primary = {} -> True,
                name = {} -> "color"
            }
        """.trimIndent()

        val program = parser.parse(source)
        val impl = program.items[1] as ImplAbilityDeclaration

        assertTrue(impl.isInherent)
        assertEquals("Color", (impl.targetTypes[0] as IdentifierTypeAnnotation).name)
        assertEquals(2, impl.operations.size)
    }

    @Test
    fun `inherent impl with method using it reference`() {
        val source = """
            impl Int where {
                doubled = {} -> it + it
            }
        """.trimIndent()

        val program = parser.parse(source)
        val impl = program.items[0] as ImplAbilityDeclaration

        assertTrue(impl.isInherent)
        val body = impl.operations[0].implementation
        assertTrue(body is LambdaExpression)
    }

    @Test
    fun `inherent impl mixed with let bindings and type declarations`() {
        val source = """
            type Meter = {value: Float}

            let pi = 3.14

            impl Meter where {
                zero = {} -> Meter {value = 0.0}
            }

            let x = 42
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(4, program.items.size)

        assertTrue(program.items[0] is TypeDeclaration)
        assertTrue(program.items[1] is LetBinding)
        assertTrue(program.items[2] is ImplAbilityDeclaration)
        assertTrue(program.items[3] is LetBinding)

        val impl = program.items[2] as ImplAbilityDeclaration
        assertTrue(impl.isInherent)
    }

    @Test
    fun `multiple inherent impls on same type`() {
        val source = """
            impl Int where {
                is_zero = {} -> it == 0
            }

            impl Int where {
                is_positive = {} -> it > 0
            }
        """.trimIndent()

        val program = parser.parse(source)
        assertEquals(2, program.items.size)

        val impl1 = program.items[0] as ImplAbilityDeclaration
        val impl2 = program.items[1] as ImplAbilityDeclaration

        assertTrue(impl1.isInherent)
        assertTrue(impl2.isInherent)
        assertEquals("is_zero", impl1.operations[0].name)
        assertEquals("is_positive", impl2.operations[0].name)
    }

    @Test
    fun `inherent impl with multi-line method body`() {
        val source = """
            impl Int where {
                classify = {} ->
                    if it > 0
                    then "positive"
                    else if it < 0
                    then "negative"
                    else "zero"
            }
        """.trimIndent()

        val program = parser.parse(source)
        val impl = program.items[0] as ImplAbilityDeclaration

        assertTrue(impl.isInherent)
        assertEquals("classify", impl.operations[0].name)
    }
}
