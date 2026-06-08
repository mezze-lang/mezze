package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests CoopAsync.park, the cooperative parking primitive. Park must
 * run ready tasks between predicate checks so a parked task's
 * condition has a chance to flip.
 */
class CoopParkTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `park returns immediately when predicate already True`() {
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
              Async.park { predicate = {} -> True }
              42
        """.trimIndent()
        assertEquals(42L, eval(source))
    }

    @Test
    fun `park observes flag flipped by spawned coop task`() {
        // Main parks. A spawned task flips an atom. Park's polling +
        // running-ready-tasks should give the spawned task a chance to
        // run; after it runs, the atom is True and park exits.
        val source = """
            use std::effects::async::{Async, CoopAsync}
            use std::effects::async::atom::{atom, deref, reset}

            perform CoopAsync in do
              let flag = atom { val = 0 }
              let _ = Async.spawn { f = {} -> reset { atom = flag, val = 1 } }
              Async.park { predicate = {} -> deref { atom = flag } == 1 }
              deref { atom = flag }
        """.trimIndent()
        assertEquals(1L, eval(source))
    }

    @Test
    fun `park deadlock-detects when no ready tasks can flip predicate`() {
        // No spawn. Main parks on a predicate that will never become True.
        // Should fail loudly with a deadlock error, not hang.
        val source = """
            use std::effects::async::{Async, CoopAsync}

            perform CoopAsync in do
              Async.park { predicate = {} -> False }
              0
        """.trimIndent()
        val ex = assertThrows(RuntimeException::class.java) { eval(source) }
        assertTrue(
            ex.message?.contains("deadlock", ignoreCase = true) == true ||
            ex.cause?.message?.contains("deadlock", ignoreCase = true) == true,
            "Expected deadlock message; got: ${ex.message}"
        )
    }
}
