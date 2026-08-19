package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * #224: a container's `Ord` impl delegates to a native compare that compares
 * ELEMENTS, so `Container T < Container T` must require `Ord T`. Without the
 * `with Ord 'a` precondition on the container Ord ops, `List Color < List Color`
 * (Color has no Ord) type-checked and crashed at runtime in the native element
 * comparison.
 *
 * The container Ord impls now carry `with Ord 'a` per op (list/deque/
 * nonemptylist/sorted_set/sorted_map, plus Option). The existing recursive
 * operation-constraint check then rejects a container of a non-Ord element and
 * accepts a container of an Ord element.
 *
 * This runs through the interpreter harness, which loads the REAL stdlib (the
 * typechecker-module `:typechecker:test` uses a stale precompiled-stdlib
 * singleton that wouldn't reflect the `.mz` edits). The element type must be
 * firmly pinned (a param); a `Container (Ctor {…})` literal leaves the element
 * free and generalizes the obligation — a separate pre-existing quirk. Seq
 * (`['a]`) resolves the obligation differently and is a separate remaining
 * sub-case, not asserted here.
 */
class ContainerOrdElementConstraintTest {

    /** True iff type-checking `src` rejects it (a `Container Color < …` must). */
    private fun rejects(src: String): Boolean =
        try { MezzeTestHelper.typeCheck(src.trimIndent()).let { false } }
        catch (t: Throwable) { true }

    // ── rejected: container of a non-Ord element ─────────────────────────

    @Test fun `List of a non-Ord element rejects less-than`() {
        assertTrue(rejects("""
            type Color = | Red, Green |
            let f: { a: List Color, b: List Color } -> Bool = { a, b } -> a < b
        """), "List Color has no Ord (Color lacks Ord); `<` must be rejected")
    }

    @Test fun `Deque of a non-Ord element rejects less-than`() {
        assertTrue(rejects("""
            use std::collections::deque::{ Deque }
            type Color = | Red, Green |
            let f: { a: Deque Color, b: Deque Color } -> Bool = { a, b } -> a < b
        """))
    }

    @Test fun `NonEmptyList of a non-Ord element rejects less-than`() {
        assertTrue(rejects("""
            use std::collections::nonemptylist::{ NonEmptyList }
            type Color = | Red, Green |
            let f: { a: NonEmptyList Color, b: NonEmptyList Color } -> Bool = { a, b } -> a < b
        """))
    }

    @Test fun `SortedSet of a non-Ord element rejects less-than`() {
        assertTrue(rejects("""
            use std::collections::sorted_set::{ SortedSet }
            type Color = | Red, Green |
            let f: { a: SortedSet Color, b: SortedSet Color } -> Bool = { a, b } -> a < b
        """))
    }

    @Test fun `SortedMap of a non-Ord key rejects less-than`() {
        assertTrue(rejects("""
            use std::collections::sorted_map::{ SortedMap }
            type Color = | Red, Green |
            let f: { a: SortedMap Color Int, b: SortedMap Color Int } -> Bool = { a, b } -> a < b
        """))
    }

    // ── accepted + runs: container of an Ord element ─────────────────────

    @Test fun `List of an Ord element compares and runs`() {
        // The last top-level expression is the value returned by `run`.
        val src = """
            use std::collections::list
            let a: List Int = list::cons { head = 1, tail = list::empty {} }
            let b: List Int = list::cons { head = 2, tail = list::empty {} }
            a < b
        """.trimIndent()
        assertEquals(true, MezzeTestHelper.run(src))
    }

    @Test fun `SortedSet of an Ord element accepts less-than`() {
        // Empty sets, but the point is it type-checks + runs (Int has Ord).
        val src = """
            use std::collections::sorted_set
            let a: SortedSet Int = sorted_set::empty {}
            let b: SortedSet Int = sorted_set::empty {}
            a < b
        """.trimIndent()
        assertEquals(false, MezzeTestHelper.run(src))
    }
}
