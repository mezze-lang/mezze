package com.mezze.interpreter.continuation

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.nodes.builtin.collections.IterNatives
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * BUG 71: a PURE iter chain must never take the cursor route. It did, once per
 * fresh lambda, because an un-inflated lazy stub answered "may suspend"; that one
 * answer failed `IterFoldDynamic.doCached`'s guard into the `replaces` generic
 * path and demoted the node for the rest of the process (+30% on every later
 * call, measured in bench/cases/contsurface kernel 7). The counter is bumped only
 * when the route is taken, so this is a direct observation, not a timing.
 */
class IterRouteDecisionTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    @Test
    fun `a pure iter chain over fresh lambdas never takes the resumable route`() {
        val before = IterNatives.RESUMABLE_DECISIONS.get()
        val r = MezzeTestHelper.run("""
            use std::collections::iter
            use std::collections::seq
            let xs = seq::range { start = 1, end = 101 }
            let block = { acc } -> iter::fold { it = iter::filter { it = iter::map { it = iter::from_seq { xs = xs }, f = { x } -> x * 2 }, p = { x } -> x > 0 }, init = acc, f = { acc, x } -> acc + x }
            let outer = { b, acc } -> if b <= 0 then acc else outer { b = b - 1, acc = block { acc = acc } }
            outer { b = 20, acc = 0 }
        """.trimIndent())
        assertEquals(20L * 10100L, r)
        assertEquals(0L, IterNatives.RESUMABLE_DECISIONS.get() - before, "a pure chain took the cursor route")
    }

    /** An effectful callback with NO cont-capable performer installed cannot park, so it
     *  runs the plain route too — the capability tax is paid only inside a boundary. */
    @Test
    fun `an effectful callback outside any cont-capable boundary stays on the plain route`() {
        val before = IterNatives.RESUMABLE_DECISIONS.get()
        val r = MezzeTestHelper.run("""
            use std::collections::iter
            use std::collections::seq
            effect Tick where { tick: {} -> Int <Tick>, }
            impl Tick for One where { tick = {} -> 1, }
            let xs = seq::range { start = 1, end = 11 }
            perform One in iter::fold { it = iter::map { it = iter::from_seq { xs = xs }, f = { x } -> x + Tick.tick {} }, init = 0, f = { acc, x } -> acc + x }
        """.trimIndent())
        assertEquals(65L, r)
        assertEquals(0L, IterNatives.RESUMABLE_DECISIONS.get() - before, "no boundary, no cursor route")
    }

    @Test
    fun `an effectful callback inside a cont-capable boundary takes the resumable route`() {
        val before = IterNatives.RESUMABLE_DECISIONS.get()
        val r = MezzeTestHelper.run("""
            use std::collections::iter
            use std::collections::seq
            use std::effects::async::{ Async, CoopAsync, yield_now }
            use std::effects::time::{ SystemTime }
            let xs = seq::range { start = 1, end = 11 }
            perform SystemTime in perform CoopAsync in iter::fold { it = iter::map { it = iter::from_seq { xs = xs }, f = { x } -> do
                yield_now {}
                x }, init = 0, f = { acc, x } -> acc + x }
        """.trimIndent())
        assertEquals(55L, r)
        assertTrue(IterNatives.RESUMABLE_DECISIONS.get() > before, "inside a boundary the parking chain must be routed to the cursor")
    }
}
