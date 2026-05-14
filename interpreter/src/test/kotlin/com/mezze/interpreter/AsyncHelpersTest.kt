package com.mezze.interpreter

import com.mezze.datastructures.seq.MezzeSeq
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for the park-based Async helper functions (P1).
 *
 * These are the userland equivalents of the Async.* effect ops. They
 * coexist with the effect ops for now — Phase 7 will migrate user code
 * to the helpers and delete the redundant ops.
 */
class AsyncHelpersTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `join runs two thunks and returns both values`() {
        val source = """
            use std::effects::async::{Async, LoomAsync, join}

            perform LoomAsync in do
              let r = join {
                l = {} -> 10,
                r = {} -> 20,
              }
              r.l + r.r
        """.trimIndent()
        assertEquals(30L, eval(source))
    }

    @Test
    fun `join3 runs three thunks`() {
        val source = """
            use std::effects::async::{Async, LoomAsync, join3}

            perform LoomAsync in do
              let r = join3 {
                a = {} -> 1,
                b = {} -> 2,
                c = {} -> 3,
              }
              r.a + r.b + r.c
        """.trimIndent()
        assertEquals(6L, eval(source))
    }

    @Test
    fun `all awaits all promises in input order`() {
        val source = """
            use std::effects::async::{Async, LoomAsync, all}

            perform LoomAsync in do
              let p1 = Async.spawn { f = {} -> 100 }
              let p2 = Async.spawn { f = {} -> 200 }
              let p3 = Async.spawn { f = {} -> 300 }
              all { ps = [p1, p2, p3] }
        """.trimIndent()
        val result = eval(source)
        assertTrue(result is MezzeSeq<*>)
        val seq = result as MezzeSeq<*>
        assertEquals(3, seq.size())
        assertEquals(100L, seq.get(0))
        assertEquals(200L, seq.get(1))
        assertEquals(300L, seq.get(2))
    }

    @Test
    fun `race returns the first completer's value`() {
        // Spawn a fast and slow task. race should pick the fast one.
        val source = """
            use std::effects::async::{Async, LoomAsync, race}

            perform LoomAsync in do
              let fast = Async.spawn { f = {} -> 42 }
              let slow = Async.spawn { f = {} -> do
                Async.sleep { ms = 100 }
                99
              }
              race { ps = [fast, slow] }
        """.trimIndent()
        // The fast one resolves immediately; race should observe it first.
        assertEquals(42L, eval(source))
    }

    @Test
    fun `par_map runs callback on each element concurrently`() {
        val source = """
            use std::effects::async::{Async, LoomAsync, par_map}

            perform LoomAsync in do
              let xs = [1, 2, 3, 4, 5]
              par_map { xs = xs, f = { x } -> x * 10 }
        """.trimIndent()
        val result = eval(source)
        assertTrue(result is MezzeSeq<*>)
        val seq = result as MezzeSeq<*>
        assertEquals(5, seq.size())
        assertEquals(10L, seq.get(0))
        assertEquals(20L, seq.get(1))
        assertEquals(30L, seq.get(2))
        assertEquals(40L, seq.get(3))
        assertEquals(50L, seq.get(4))
    }

    @Test
    fun `par_for_each runs callback on each element, discarding results`() {
        // Use an atom-style counter to observe that each element was processed.
        val source = """
            use std::effects::async::{Async, LoomAsync, par_for_each}
            use std::effects::concurrent::atom::{atom, deref, swap}

            perform LoomAsync in do
              let counter = atom { value = 0 }
              par_for_each { xs = [1, 2, 3, 4, 5], f = { x } -> do
                let _ = swap { atom = counter, f = { current } -> current + x }
                {}
              }
              deref { atom = counter }
        """.trimIndent()
        // 1+2+3+4+5 = 15
        assertEquals(15L, eval(source))
    }

    @Test
    fun `par_filter keeps elements where predicate is True`() {
        val source = """
            use std::effects::async::{Async, LoomAsync, par_filter}

            perform LoomAsync in do
              par_filter { xs = [1, 2, 3, 4, 5], f = { x } -> x > 2 }
        """.trimIndent()
        val result = eval(source)
        assertTrue(result is MezzeSeq<*>)
        val seq = result as MezzeSeq<*>
        assertEquals(3, seq.size())
        assertEquals(3L, seq.get(0))
        assertEquals(4L, seq.get(1))
        assertEquals(5L, seq.get(2))
    }

    @Test
    fun `with_timeout returns Some on success`() {
        val source = """
            use std::effects::async::{Async, LoomAsync, with_timeout}

            perform LoomAsync in do
              let p = Async.spawn { f = {} -> 42 }
              match with_timeout { ms = 1000, p = p } is
                Some { val } -> val
                None -> 0
        """.trimIndent()
        assertEquals(42L, eval(source))
    }
}
