package com.mezze.interpreter.continuation

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.nodes.continuation.TaskMachine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * BUG 77: the machine's promise/task maps grew without bound — nothing removed a completed
 * promise — so every lookup, resize and GC scan paid for every promise ever created
 * (spawn+await 1.4 → 2.8 us per element from 20k to 200k). Completed work is forgotten.
 */
class MachineBookkeepingTest {
    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    @Test
    fun `three thousand spawned and awaited promises leave the maps empty`() {
        val r = MezzeTestHelper.run("""
            use std::effects::async::{ Async, CoopAsync, await }
            use std::effects::time::{ SystemTime }
            use std::result
            let go = { i, acc } -> if i <= 0 then acc else do
              let p = Async.spawn { f = {} -> i }
              go { i = i - 1, acc = acc + result::get_or { result = await { p = p }, def = 0 } }
            perform SystemTime in perform CoopAsync in go { i = 3000, acc = 0 }
        """.trimIndent())
        assertEquals(4501500L, r)
        val left = TaskMachine.LAST_BOOKKEEPING
        assertTrue(left in 0..8, "bookkeeping entries left after the boundary closed: $left")
    }

    @Test
    fun `awaiting a promise after it was forgotten still reads as settled`() = assertEquals(
        2001000L,
        MezzeTestHelper.run("""
            use std::effects::async::{ Async, CoopAsync, await, yield_now }
            use std::effects::time::{ SystemTime }
            use std::collections::seq
            use std::result
            let xs = seq::range { start = 1, end = 2001 }
            perform SystemTime in perform CoopAsync in do
              let ps = seq::map { xs = xs, f = { x } -> Async.spawn { f = {} -> x } }
              yield_now {}
              yield_now {}
              seq::fold { xs = ps, init = 0, f = { acc, x } -> acc + result::get_or { result = await { p = x }, def = 0 } }
        """.trimIndent()),
    )
}
