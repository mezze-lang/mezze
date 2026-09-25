package com.mezze.language.typechecker.util

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.ParsedNamedType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A name that is not a type VARIABLE converts to a nominal type, not to a var.
 *
 * `TypeAnnotationConverter.convert`'s `ParsedNamedType` arm used to answer every
 * non-primitive, non-alias name with a `VarType` — so a declared `Json`,
 * `DecodeError` or `Instant` arrived as a type VARIABLE. A variable unifies with
 * anything, which makes every check that reads one vacuous rather than wrong:
 * it does not reject, and nothing says it declined to look.
 *
 * That is how `having 'out = Color` against an impl declaring `Str` was accepted
 * — the caller's `annotatedType is VarType -> continue` skipped it. The sibling
 * entry point `convertWith` always made this split correctly via
 * [com.mezze.language.ast.isTypeVarName]; only this one did not.
 *
 * MEASURED before the fix: 2,014,080 nominal names converted to variables across
 * both suites, led by `CodecCtx`, `JsonC`, `Json`, `DecodeError` and `Instant` —
 * every one of them a real declaration.
 */
class ConvertNominalNameTest {

    private val loc = Loc("t.mz", 1, 1, 1, 1)

    @Test
    fun `a declared type name is nominal, not a variable`() {
        val converted = TypeAnnotationConverter.convert(ParsedNamedType("Color", loc))
        assertTrue(
            converted is MezzeType.TypeApplication,
            "`Color` must convert to a nominal type; a VarType unifies with anything and " +
                "silently makes its caller's check vacuous. Got: $converted",
        )
        assertEquals("Color", (converted as MezzeType.TypeApplication).constructor)
    }

    @Test
    fun `a type VARIABLE still converts to a variable`() {
        // The boundary. Fixing the nominal case must not swallow the case the arm
        // was actually written for.
        assertTrue(
            TypeAnnotationConverter.convert(ParsedNamedType("'a", loc)) is MezzeType.VarType,
            "`'a` is a type variable and must stay one",
        )
        assertTrue(
            TypeAnnotationConverter.convert(ParsedNamedType("elem", loc)) is MezzeType.VarType,
            "a lowercase name is a type variable by Mezze's convention and must stay one",
        )
    }

    @Test
    fun `a primitive still wins over both`() {
        assertEquals(MezzeType.IntType, TypeAnnotationConverter.convert(ParsedNamedType("Int", loc)))
        assertEquals(MezzeType.StringType, TypeAnnotationConverter.convert(ParsedNamedType("Str", loc)))
    }

    @Test
    fun `the two entry points now agree on a nominal name`() {
        // The whole point: `convert` and `convertWith` are the same conversion and
        // disagreeing on something this basic is what let the defect hide.
        val viaConvert = TypeAnnotationConverter.convert(ParsedNamedType("Color", loc))
        val viaConvertWith = TypeAnnotationConverter.convertWith(
            ParsedNamedType("Color", loc), TypeAnnotationConverter.ConversionConfig()
        )
        assertEquals(viaConvertWith::class, viaConvert::class)
        assertEquals(
            (viaConvertWith as MezzeType.TypeApplication).constructor,
            (viaConvert as MezzeType.TypeApplication).constructor,
        )
    }
}
