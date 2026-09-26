package com.mezze.language.typechecker.util

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import com.mezze.language.parsedast.ParsedFnAbilityConstraint
import com.mezze.language.parsedast.ParsedFunctionType
import com.mezze.language.parsedast.ParsedNamedType
import com.mezze.language.parsedast.ParsedQualifiedType
import com.mezze.language.parsedast.ParsedRecordType
import com.mezze.language.parsedast.ParsedRecordTypeEntry
import com.mezze.language.parsedast.ParsedVariantType
import com.mezze.language.parsedast.ParsedVariantTypeCtor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * There is ONE annotation walker, and these are the things the copies of it
 * dropped.
 *
 * Six conversions of `ParsedTypeAnnotation` to `MezzeType` existed — in the
 * emitter, twice in the ability checker, in the built-in abilities, as an
 * impl-binding substituter, and as a second walker inside the shared converter
 * itself. They were not six decisions; five were copies that fell behind, each
 * silently discarding part of what the author wrote. A discarded part does not
 * fail: it converts to something weaker that unifies more readily, so the check
 * reading it passes and says nothing.
 *
 * Every case below is something a copy lost. They pass because there is now one
 * walker; they exist so that reintroducing a shortcut fails here instead of
 * showing up as an accepted program that should not have typechecked.
 */
class OneWalkerConversionTest {

    private val loc = Loc("t.mz", 1, 1, 1, 1)
    private fun named(n: String) = ParsedNamedType(n, loc)
    private fun convert(a: com.mezze.language.parsedast.ParsedTypeAnnotation) =
        TypeAnnotationConverter.convert(a)

    // ---- openness is part of the type, not a detail ------------------------

    @Test
    fun `an open record keeps its row variable`() {
        val open = convert(
            ParsedRecordType(listOf(ParsedRecordTypeEntry("a", named("Int"), loc)), true, loc)
        )
        assertTrue(open is MezzeType.RecordType)
        assertNotNull((open as MezzeType.RecordType).rowVar,
            "`{ a: Int, ... }` is an OPEN record; dropping the row var converts it to a " +
                "different type that rejects the extra fields the author allowed")
    }

    @Test
    fun `a closed record has no row variable`() {
        // The other half: openness must be read from the annotation, not assumed.
        val closed = convert(
            ParsedRecordType(listOf(ParsedRecordTypeEntry("a", named("Int"), loc)), false, loc)
        )
        assertNull((closed as MezzeType.RecordType).rowVar,
            "`{ a: Int }` is closed and must stay closed")
    }

    @Test
    fun `an open variant keeps its row variable`() {
        val open = convert(
            ParsedVariantType(listOf(ParsedVariantTypeCtor("Red", null, loc)), true, loc)
        )
        assertNotNull((open as MezzeType.VariantType).rowVar,
            "`|Red, ...|` admits further constructors; a closed variant does not")
    }

    // ---- a qualified type is a type ----------------------------------------

    @Test
    fun `a module-qualified type is nominal, not a variable`() {
        // The second walker answered EVERY qualified type with `VarType(fresh())`.
        // A variable unifies with anything, so any check reading one was vacuous —
        // and one of the two ability-resolution copies additionally returned null
        // for a qualified type, abandoning resolution rather than resolving it.
        val converted = convert(ParsedQualifiedType(listOf("json", "core"), "Json", emptyList(), loc))
        assertTrue(converted is MezzeType.TypeApplication,
            "`json::core::Json` names a type and must convert to one, got $converted")
        assertEquals("json::core::Json", (converted as MezzeType.TypeApplication).constructor,
            "the qualification is part of the name — it is what distinguishes this " +
                "`Json` from another module's")
    }

    // ---- constraints are part of a function's type --------------------------

    @Test
    fun `ability constraints on a function survive conversion`() {
        val fn = convert(
            ParsedFunctionType(
                paramType = named("'a"),
                returnType = named("Str"),
                abilityConstraints = listOf(ParsedFnAbilityConstraint("Show", listOf(named("'a")), loc = loc)),
                loc = loc,
            )
        )
        val constraints = (fn as MezzeType.FunctionType).abilityConstraints
        assertEquals(listOf("Show"), constraints.map { it.abilityName },
            "`with Show 'a` is an obligation on the CALLER; dropping it means callers " +
                "are never asked for the impl")
    }

    // ---- one written variable is one variable -------------------------------

    @Test
    fun `one type variable written twice converts to one variable`() {
        // This codebase's most-repeated defect, found five separate times before
        // this one. With a per-occurrence variable, `{x: 'a} -> 'a` converts to
        // `{x: 'b} -> 'c` — which is not the identity function's type and no longer
        // relates the argument to the result at all.
        val fn = convert(
            ParsedFunctionType(
                paramType = ParsedRecordType(listOf(ParsedRecordTypeEntry("x", named("'a"), loc)), false, loc),
                returnType = named("'a"),
                loc = loc,
            )
        ) as MezzeType.FunctionType

        val param = (fn.paramType as MezzeType.RecordType).fields.getValue("x")
        assertTrue(param is MezzeType.VarType && fn.returnType is MezzeType.VarType)
        assertEquals(
            (param as MezzeType.VarType).variable,
            (fn.returnType as MezzeType.VarType).variable,
            "the `'a` in the parameter and the `'a` in the return are ONE variable",
        )
    }

    @Test
    fun `two different variables stay different`() {
        // The boundary for the test above: sharing by name must not collapse names.
        val fn = convert(
            ParsedFunctionType(paramType = named("'a"), returnType = named("'b"), loc = loc)
        ) as MezzeType.FunctionType
        assertTrue(
            (fn.paramType as MezzeType.VarType).variable != (fn.returnType as MezzeType.VarType).variable,
            "`'a` and `'b` are distinct variables",
        )
    }

    @Test
    fun `convertSignature shares variables across separate annotations`() {
        // A parameter list is several annotations that form ONE signature.
        // Converting each on its own gave `{ x: 'a, y: 'a }` two variables, so
        // `same { x = 1, y = "two" }` typechecked.
        val converted = TypeAnnotationConverter.convertSignature(
            listOf("x" to named("'a"), "y" to named("'a")),
            TypeAnnotationConverter.ConversionConfig(),
        ).toMap()

        assertEquals(
            (converted.getValue("x") as MezzeType.VarType).variable,
            (converted.getValue("y") as MezzeType.VarType).variable,
            "both fields were written `'a`, so both are the same variable",
        )
    }

    @Test
    fun `separate convert calls do NOT share variables`() {
        // The counterpart: two unrelated annotations must not be coupled just
        // because their authors both reached for `'a`.
        val a = convert(named("'a")) as MezzeType.VarType
        val b = convert(named("'a")) as MezzeType.VarType
        assertTrue(a.variable != b.variable,
            "two independent conversions are independent; sharing here would fuse " +
                "unrelated signatures")
    }
}
