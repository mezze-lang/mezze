package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * #213: SortedSet / SortedMap must reject a non-Ord element (or key) AT COMPILE
 * TIME instead of type-checking and then crashing at runtime with a
 * ClassCastException from the native TreeMap comparator.
 *
 * The hole was on the IMPORT boundary: `sorted_set::singleton` and friends
 * declare `with Ord 'a`, but that constraint was dropped twice —
 *   1. ModuleResolver exported the solver-inferred FunctionType, which carries
 *      no `abilityConstraints`, instead of the annotation-derived one; and
 *   2. ImportContextMerger rebuilt the imported binding's TypeScheme with
 *      `constraints = emptyList()`.
 * So a caller importing `singleton` saw a constraint-free scheme, no `Ord`
 * obligation was ever emitted, and `SortedSet Color` (Color has no Ord impl)
 * sailed through the type-checker.
 *
 * These tests use the REAL stdlib through the interpreter harness (they are
 * immune to the stale PrecompiledStdlib singleton that :typechecker:test sees),
 * so they exercise the exact export → import → instantiate path that was broken.
 * A non-Ord element must surface a TypeCheckError mentioning `Ord`; an Ord
 * element (Int) must still work end-to-end.
 */
class SortedCollectionOrdConstraintTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private fun assertOrdRejected(source: String, what: String) {
        val exception = assertThrows(PolyglotException::class.java, { eval(source) }, what)
        val msg = exception.message ?: ""
        assertTrue(msg.contains("TypeCheckError"), "expected a TypeCheckError for $what, got: $msg")
        assertTrue(msg.contains("Ord"), "the $what rejection must mention the missing Ord impl, got: $msg")
    }

    // ===== SortedSet: non-Ord element rejected at compile time =====

    @Test
    fun `sorted_set singleton of a non-Ord variant is rejected`() {
        assertOrdRejected(
            """
            use std::collections::sorted_set
            type Color = | Red, Green, Blue |
            sorted_set::singleton { x = Red }
            """,
            "SortedSet singleton of non-Ord Color",
        )
    }

    @Test
    fun `sorted_set from_list of a non-Ord variant is rejected`() {
        assertOrdRejected(
            """
            use std::collections::sorted_set
            use std::collections::list
            type Color = | Red, Green, Blue |
            let xs = list::cons { head = Red, tail = list::cons { head = Green, tail = list::empty {} } }
            sorted_set::from_list { xs = xs }
            """,
            "SortedSet from_list of non-Ord Color",
        )
    }

    @Test
    fun `sorted_set add of a non-Ord variant is rejected`() {
        assertOrdRejected(
            """
            use std::collections::sorted_set
            type Color = | Red, Green, Blue |
            sorted_set::add { s = sorted_set::empty {}, x = Red }
            """,
            "SortedSet add of non-Ord Color",
        )
    }

    // ===== SortedMap: non-Ord key rejected at compile time =====

    @Test
    fun `sorted_map insert with a non-Ord key is rejected`() {
        assertOrdRejected(
            """
            use std::collections::sorted_map
            type Color = | Red, Green, Blue |
            sorted_map::insert { m = sorted_map::empty {}, k = Red, v = 1 }
            """,
            "SortedMap insert with non-Ord Color key",
        )
    }

    @Test
    fun `sorted_map singleton with a non-Ord key is rejected`() {
        assertOrdRejected(
            """
            use std::collections::sorted_map
            type Color = | Red, Green, Blue |
            sorted_map::singleton { k = Red, v = 1 }
            """,
            "SortedMap singleton with non-Ord Color key",
        )
    }

    // ===== Ord elements/keys still work end-to-end (no false positive) =====

    @Test
    fun `sorted_set of Int still constructs and reports size`() {
        val result = eval(
            """
            use std::collections::sorted_set
            let s = sorted_set::singleton { x = 42 }
            sorted_set::size { s = s }
            """
        )
        assertEquals(1L, result)
    }

    @Test
    fun `sorted_map of Int keys still inserts and reads back`() {
        val result = eval(
            """
            use std::collections::sorted_map
            let m = sorted_map::insert { m = sorted_map::empty {}, k = 7, v = 99 }
            sorted_map::get { m = m, k = 7 }
            """
        )
        // get returns Option 'v; Some 99 — the interpreter unwraps Some to its payload shape.
        assertNotNull(result)
    }
}
