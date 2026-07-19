package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * #125 — Tests for `park_any` + `select`: wait on multiple
 * heterogeneous sources, run the action for whichever fires first.
 *
 * Both return Option to handle the empty-branches case without
 * crashing or deadlocking.
 */
@Execution(ExecutionMode.SAME_THREAD)
class SelectTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `park_any returns the index of the first true predicate`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await, park_any }
            use std::option

            perform LoomAsync in do
              let r = park_any { predicates = [{} -> False, {} -> True, {} -> False] }
              option::get_or_else { opt = r, def = -1 }
        """.trimIndent()
        assertEquals(1L, eval(source))
    }

    @Test
    fun `park_any returns None for empty list`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await, park_any }
            use std::option

            perform LoomAsync in do
              option::is_none { opt = park_any { predicates = [] } }
        """.trimIndent()
        assertEquals(true, eval(source))
    }

    @Test
    fun `park_any waits for a flag set by another task`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await, park_any }
            use std::effects::async::atom::{ atom, deref, reset }
            use std::option

            perform LoomAsync in do
              let flag = atom { val = 0 }
              let _ = Async.spawn { f = {} -> reset { atom = flag, val = 1 } }
              let r = park_any { predicates = [
                {} -> deref { atom = flag } == 99,
                {} -> deref { atom = flag } == 1,
              ] }
              option::get_or_else { opt = r, def = -1 }
        """.trimIndent()
        assertEquals(1L, eval(source))
    }

    @Test
    fun `select runs the branch whose when predicate fires`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await, select }
            use std::option

            perform LoomAsync in do
              let r = select { branches = [
                { when = {} -> False, run = {} -> 10 },
                { when = {} -> True,  run = {} -> 20 },
                { when = {} -> True,  run = {} -> 30 },
              ] }
              option::get_or_else { opt = r, def = -1 }
        """.trimIndent()
        assertEquals(20L, eval(source))
    }

    @Test
    fun `select returns None on empty branches`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, SelectBranch, await, select }
            use std::option

            perform LoomAsync in do
              # Empty branch list typed via annotation since literal [] needs context.
              let bs : [SelectBranch Int] = []
              option::is_none { opt = select { branches = bs } }
        """.trimIndent()
        assertEquals(true, eval(source))
    }

    @Test
    fun `select waits for a channel to have data`() {
        // Predicates must NOT consume. Pattern: spawn a recv-promise per
        // source, use `p.is_done {}` (non-consuming) in `when`, then
        // await-the-result inside `run`. Both branches use spawned
        // promises so effect rows align.
        val source = """
            use std::effects::async::{ Async, LoomAsync, await, select }
            use std::effects::async::channel::{ chan_bounded, send, recv }
            use std::result
            use std::option

            perform LoomAsync in do
              let ch = chan_bounded { cap = 1 }
              let _ = Async.spawn { f = {} -> send { ch = ch, val = 7 } }
              let unused = chan_bounded { cap = 1 }
              let pa = Async.spawn { f = {} -> recv { ch = unused } }
              let pb = Async.spawn { f = {} -> recv { ch = ch } }
              let r = select { branches = [
                { when = {} -> pa.is_done {},
                  run  = {} -> result::get_or_else { result = await { p = pa }, def = -1 } },
                { when = {} -> pb.is_done {},
                  run  = {} -> result::get_or_else { result = await { p = pb }, def = -1 } },
              ] }
              option::get_or_else { opt = r, def = -2 }
        """.trimIndent()
        assertEquals(7L, eval(source))
    }

    @Test
    fun `select under LoomAsync`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await, select }
            use std::option

            perform LoomAsync in do
              let r = select { branches = [
                { when = {} -> False, run = {} -> 1 },
                { when = {} -> True,  run = {} -> 2 },
              ] }
              option::get_or_else { opt = r, def = -1 }
        """.trimIndent()
        assertEquals(2L, eval(source))
    }

    @Test
    fun `park_any under LoomAsync`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await, park_any }
            use std::effects::async::atom::{ atom, deref, reset }
            use std::option

            perform LoomAsync in do
              let flag = atom { val = 0 }
              let _ = Async.spawn { f = {} -> reset { atom = flag, val = 5 } }
              let r = park_any { predicates = [
                {} -> deref { atom = flag } == 99,
                {} -> deref { atom = flag } == 5,
              ] }
              option::get_or_else { opt = r, def = -1 }
        """.trimIndent()
        assertEquals(1L, eval(source))
    }
}
