package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A seq bound out of a VARIANT PAYLOAD in a match arm reached `Sized` dispatch
 * with no resolvable impl and fell through to the dict path, where no slot
 * exists — `UnsupportedSpecializationException … CallIndirect`. Every other way
 * of binding the same seq worked, and inserting any call between the binding and
 * the method (`.map{…}.size{}`) also worked, so the receiver arriving at dispatch
 * differed by exactly one step.
 *
 * It no longer reproduces: the dispatch work in this effort closed it. These are
 * the lock, covering both the method that crashed and the shapes that always
 * worked, so a regression is caught as a regression rather than rediscovered.
 */
class MatchArmSizeDispatchTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @Test
    fun `size on a seq bound from a variant payload`() {
        assertEquals(3L, MezzeTestHelper.run(
            """
            use std::collections::seq
            type T = | Wrap { items: [Int] } |
            let n = { t : T } -> match t is
              Wrap { items } -> items.size {}
            n { t = Wrap { items = [1, 2, 3] } }
            """.trimIndent()
        ))
    }

    @Test
    fun `is_empty on a seq bound from a variant payload`() {
        assertEquals(true, MezzeTestHelper.run(
            """
            use std::collections::seq
            type T = | Wrap { items: [Int] } |
            let e = { t : T } -> match t is
              Wrap { items } -> items.is_empty {}
            e { t = Wrap { items = [] } }
            """.trimIndent()
        ))
    }

    @Test
    fun `the paths that already worked still work`() {
        assertEquals(3L, MezzeTestHelper.run(
            """
            use std::collections::seq
            type T = | Wrap { items: [Int] } |
            let viaMap = { t : T } -> match t is
              Wrap { items } -> items.map { f = { x } -> x }.size {}
            viaMap { t = Wrap { items = [1, 2, 3] } }
            """.trimIndent()
        ))
    }

    @Test
    fun `the report's exact source, verbatim`() {
        // The report's snippet carries no `use std::collections::seq`. Kept
        // exactly as written so this file matches the bug it closes — if the
        // crash ever returns, it returns on the source that was reported.
        assertEquals(3L, MezzeTestHelper.run(
            """
            type T = | Wrap { items: [Int] } |
            let n = { t : T } -> match t is
              Wrap { items } -> items.size {}
            n { t = Wrap { items = [1, 2, 3] } }
            """.trimIndent()
        ))
    }
}
