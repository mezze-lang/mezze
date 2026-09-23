package com.mezze.language.ast

import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The property that matters: a type's head name is always a name a Mezze
 * programmer would recognise. Never a Kotlin class name.
 *
 * Both renderers this replaced ended in `else -> type::class.simpleName`, and both
 * leaked through it — `FracType` into a diagnostic, and `String` (for `Str`) into
 * a go-to-definition lookup that could never resolve.
 */
class TypeNamesTest {

    private val everyType: List<MezzeType> = listOf(
        MezzeType.IntType, MezzeType.FloatType, MezzeType.StringType,
        MezzeType.BoolType, MezzeType.FracType, MezzeType.BytesType,
        MezzeType.CharType,
        MezzeType.VarType(TypeVar.freshWithName("'a")),
        MezzeType.FunctionType(MezzeType.IntType, MezzeType.IntType),
        MezzeType.RecordType(mapOf("x" to MezzeType.IntType)),
        MezzeType.VariantType(mapOf("Some" to MezzeType.IntType, "None" to null)),
        MezzeType.TypeApplication("Option", listOf(MezzeType.IntType)),
        MezzeType.EffectRow(effects = setOf("Exception")),
    )

    @Test
    fun `every primitive renders as its Mezze name`() {
        assertEquals("Int", TypeNames.head(MezzeType.IntType))
        assertEquals("Float", TypeNames.head(MezzeType.FloatType))
        // `Str`, not `String` — the LSP renderer used to answer `String`, which is
        // not a type name in this language.
        assertEquals("Str", TypeNames.head(MezzeType.StringType))
        assertEquals("Bool", TypeNames.head(MezzeType.BoolType))
        // The three the hand-written list forgot.
        assertEquals("Frac", TypeNames.head(MezzeType.FracType))
        assertEquals("Char", TypeNames.head(MezzeType.CharType))
        assertEquals("Bytes", TypeNames.head(MezzeType.BytesType))
    }

    @Test
    fun `no type renders as a Kotlin class name`() {
        for (t in everyType) {
            val name = TypeNames.head(t)
            assertTrue(
                !name.endsWith("Type") && name != t::class.simpleName,
                "${t::class.simpleName} rendered as '$name' — that is the Kotlin class leaking out"
            )
            assertTrue(name.isNotBlank(), "${t::class.simpleName} rendered blank")
        }
    }

    @Test
    fun `a declared type renders by its declared name`() {
        val id = TypeIdentity.derive("Colour", Hash("abc123"), allowBuiltin = false)
        assertEquals(
            "Colour",
            TypeNames.head(MezzeType.VariantType(mapOf("Red" to null), identity = id))
        )
        assertEquals("Option", TypeNames.head(MezzeType.TypeApplication("Option", listOf(MezzeType.IntType))))
    }

    @Test
    fun `an anonymous structural type renders as a placeholder, not a name`() {
        assertEquals("{record}", TypeNames.head(MezzeType.RecordType(mapOf("x" to MezzeType.IntType))))
        assertEquals("|variant|", TypeNames.head(MezzeType.VariantType(mapOf("A" to null))))
        assertEquals("{fn}", TypeNames.head(MezzeType.FunctionType(MezzeType.IntType, MezzeType.IntType)))
    }

    // ---- nominal() is the lookup question, and must not answer placeholders ----

    @Test
    fun `nominal is null exactly where there is no declaration to find`() {
        assertNull(TypeNames.nominal(MezzeType.FunctionType(MezzeType.IntType, MezzeType.IntType)))
        assertNull(TypeNames.nominal(MezzeType.VarType(TypeVar.freshWithName("'a"))))
        assertNull(TypeNames.nominal(MezzeType.EffectRow(effects = setOf("Exception"))))
        // Anonymous structural types have a display placeholder but no declaration.
        assertNull(TypeNames.nominal(MezzeType.RecordType(mapOf("x" to MezzeType.IntType))))
        assertNull(TypeNames.nominal(MezzeType.VariantType(mapOf("A" to null))))
    }

    @Test
    fun `nominal never returns a display placeholder`() {
        for (t in everyType) {
            val n = TypeNames.nominal(t) ?: continue
            assertTrue(
                !n.startsWith("{") && !n.startsWith("|") && !n.startsWith("<"),
                "${t::class.simpleName}: nominal() returned the display placeholder '$n', " +
                    "which would be looked up as a declaration name and never resolve"
            )
        }
    }
}
