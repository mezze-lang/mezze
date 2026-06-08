package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Same semaphore-based serialization code runs under LoomAsync (real
 * virtual threads, real parking) and CoopAsync (single-threaded
 * cooperative parking).
 */
class SemaphoreUnderBothSchedulersTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun program(handler: String) = """
        use std::effects::async::{ Async, $handler }
        use std::effects::async::semaphore::{ semaphore, acquire_async, release }
        use std::effects::async::atom::{ atom, deref, reset }

        perform $handler in do
          let s = semaphore { permits = 1 }
          let counter = atom { val = 0 }
          let bump = {} -> do
            acquire_async { sem = s }
            let v = deref { atom = counter }
            let _ = reset { atom = counter, val = v + 1 }
            release { sem = s }
          let p1 = Async.spawn { f = bump }
          let p2 = Async.spawn { f = bump }
          let p3 = Async.spawn { f = bump }
          let _ = Async.await { p = p1 }
          let _ = Async.await { p = p2 }
          let _ = Async.await { p = p3 }
          deref { atom = counter }
    """.trimIndent()

    @Test
    fun `semaphore serializes increments under LoomAsync`() {
        assertEquals(3L, eval(program("LoomAsync")))
    }

    @Test
    fun `semaphore serializes increments under CoopAsync`() {
        assertEquals(3L, eval(program("CoopAsync")))
    }
}
