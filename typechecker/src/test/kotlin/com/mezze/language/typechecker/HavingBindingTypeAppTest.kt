package com.mezze.language.typechecker

import com.mezze.language.parsedast.ParsedFunctionType
import com.mezze.language.parsedast.ParsedLetDecl
import com.mezze.language.parsedast.ParsedTypeApp
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `having` binding takes a TYPE, and a type application is a type.
 *
 * `<Exception having 'error = Map Str Int>` used to fail with
 * "Expected '=' but got '<'" — a message about the `<` that OPENS the row, which
 * is nowhere near the problem. The binding value was parsed by the single-atom
 * parser, which consumed `Map` and stopped; the row then failed to close on `>`,
 * and the whole row was silently backtracked, leaving the outer parser to trip
 * over tokens it had no context for.
 *
 * Inside `< … >` a binding is delimited by `,` or `>`, so there is nothing
 * ambiguous about consuming the whole application.
 */
class HavingBindingTypeAppTest {

    private val parser = MezzeParser()

    private fun effectRowOf(src: String) =
        (parser.parseParsedModule(src, "having.mz")
            .decls.filterIsInstance<ParsedLetDecl>().single()
            .typeAnnotation as ParsedFunctionType).effects

    @Test
    fun `an effect row's having binding accepts a type application`() {
        val row = effectRowOf(
            "let risky : { x: Int } -> Int <Exception having 'error = Map Str Int> = {x} -> x"
        )
        val bound = row!!.effectAnnotations.single().associatedTypes.getValue("'error")
        assertTrue(bound is ParsedTypeApp, "expected a type application, got $bound")
        assertEquals("Map", (bound as ParsedTypeApp).constructor)
        assertEquals(2, bound.args.size, "both arguments must be consumed, not just the first")
    }

    @Test
    fun `the parenthesised form still works`() {
        val row = effectRowOf(
            "let risky : { x: Int } -> Int <Exception having 'error = (Map Str Int)> = {x} -> x"
        )
        val bound = row!!.effectAnnotations.single().associatedTypes.getValue("'error")
        assertEquals("Map", (bound as ParsedTypeApp).constructor)
    }

    @Test
    fun `a simple bound type still works`() {
        val row = effectRowOf(
            "let risky : { x: Int } -> Int <Exception having 'error = Str> = {x} -> x"
        )
        assertEquals(
            "Str",
            (row!!.effectAnnotations.single().associatedTypes.getValue("'error")
                as com.mezze.language.parsedast.ParsedNamedType).name
        )
    }

    @Test
    fun `a row with a plain effect beside a bound one still closes`() {
        val row = effectRowOf(
            "let risky : { x: Int } -> Int <Exception having 'error = Map Str Int, Log> = {x} -> x"
        )
        assertEquals(listOf("Log"), row!!.effects)
        assertEquals(listOf("Exception"), row.effectAnnotations.map { it.name })
    }

    @Test
    fun `a nested application inside the binding is consumed whole`() {
        val row = effectRowOf(
            "let risky : { x: Int } -> Int <Exception having 'error = Map Str (Option Int)> = {x} -> x"
        )
        val bound = row!!.effectAnnotations.single().associatedTypes.getValue("'error") as ParsedTypeApp
        assertEquals(2, bound.args.size)
        assertTrue(bound.args[1] is ParsedTypeApp, "the parenthesised argument must survive as an application")
    }
}
