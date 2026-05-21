package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Latch await_async parks via Async.park; same code runs under both
 * LoomAsync and CoopAsync.
 */
class LatchUnderBothSchedulersTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun program(handler: String) = """
        use std::effects::async::{ Async, $handler }
        use std::effects::concurrent::latch::{ latch, count_down, await_latch_async }

        perform $handler in do
          let l = latch { count = 3 }
          let tick = {} -> count_down { latch = l }
          let p1 = Async.spawn { f = tick }
          let p2 = Async.spawn { f = tick }
          let p3 = Async.spawn { f = tick }
          await_latch_async { latch = l }
          let _ = Async.await { p = p1 }
          let _ = Async.await { p = p2 }
          let _ = Async.await { p = p3 }
          42
    """.trimIndent()

    @Test
    fun `latch releases on countdown under LoomAsync`() {
        assertEquals(42L, eval(program("LoomAsync")))
    }

    @Test
    fun `latch releases on countdown under CoopAsync`() {
        assertEquals(42L, eval(program("CoopAsync")))
    }
}
