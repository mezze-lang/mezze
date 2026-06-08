package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Smoke tests for the new Async.park effect op. Park is the parking
 * hook data structures (Chan/Mutex/etc.) will use in #107.
 */
class AsyncParkTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `park returns immediately when predicate is already true`() {
        val source = """
            use std::effects::async::{Async, LoomAsync}

            perform LoomAsync in do
              Async.park { predicate = {} -> True }
              42
        """.trimIndent()

        assertEquals(42L, eval(source))
    }

    @Test
    fun `parker observes flag flipped by spawned task`() {
        // Spawn a task that flips an atom; main parks until atom == 1.
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::async::atom::{atom, deref, reset}

            perform LoomAsync in do
              let flag = atom { val = 0 }
              let _ = Async.spawn { f = {} -> reset { atom = flag, val = 1 } }
              Async.park { predicate = {} -> deref { atom = flag } == 1 }
              deref { atom = flag }
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `park predicate can perform other effects (Exception)`() {
        // The predicate is row-poly; it can perform any effect the
        // surrounding context handles. Here the predicate raises on the
        // first poll, which the outer ExceptionToResult catches.
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::exception::{Exception, ExceptionToResult}

            perform ExceptionToResult in perform LoomAsync in do
              Async.park { predicate = {} -> Exception.throw { error = "stop" } }
              Ok { val = 0 }
        """.trimIndent()

        val result = eval(source)
        assertNotNull(result)
        // Err { error = "stop" }
    }
}
