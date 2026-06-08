package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * The killer demo for P5: SAME code using mutexes runs correctly
 * under both LoomAsync (real virtual threads, real parking) and
 * CoopAsync (single-threaded scheduler, cooperative parking via the
 * ready queue).
 */
class MutexUnderBothSchedulersTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun program(handler: String) = """
        use std::effects::async::{ Async, $handler }
        use std::effects::async::mutex::{ mutex, lock_async, unlock }
        use std::effects::async::atom::{ atom, deref, reset }

        perform $handler in do
          let m = mutex {}
          let counter = atom { val = 0 }
          let bump = {} -> do
            lock_async { mutex = m }
            let v = deref { atom = counter }
            let _ = reset { atom = counter, val = v + 1 }
            unlock { mutex = m }
          let p1 = Async.spawn { f = bump }
          let p2 = Async.spawn { f = bump }
          let p3 = Async.spawn { f = bump }
          let _ = Async.await { p = p1 }
          let _ = Async.await { p = p2 }
          let _ = Async.await { p = p3 }
          deref { atom = counter }
    """.trimIndent()

    @Test
    fun `mutex serializes increments under LoomAsync`() {
        assertEquals(3L, eval(program("LoomAsync")))
    }

    @Test
    fun `mutex serializes increments under CoopAsync`() {
        assertEquals(3L, eval(program("CoopAsync")))
    }
}
