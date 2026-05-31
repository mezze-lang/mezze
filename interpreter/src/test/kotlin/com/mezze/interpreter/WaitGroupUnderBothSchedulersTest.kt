package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * WaitGroup wait_async parks via Async.park; same code runs under both
 * LoomAsync and CoopAsync.
 */
class WaitGroupUnderBothSchedulersTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun program(handler: String) = """
        use std::effects::async::{ Async, $handler }
        use std::effects::async::waitgroup::{ wait_group, add, done, wait_async }

        perform $handler in do
          let wg = wait_group {}
          let _ = add { wg = wg, delta = 3 }
          let tick = {} -> done { wg = wg }
          let p1 = Async.spawn { f = tick }
          let p2 = Async.spawn { f = tick }
          let p3 = Async.spawn { f = tick }
          wait_async { wg = wg }
          let _ = Async.await { p = p1 }
          let _ = Async.await { p = p2 }
          let _ = Async.await { p = p3 }
          42
    """.trimIndent()

    @Test
    fun `wait group releases on done under LoomAsync`() {
        assertEquals(42L, eval(program("LoomAsync")))
    }

    @Test
    fun `wait group releases on done under CoopAsync`() {
        assertEquals(42L, eval(program("CoopAsync")))
    }
}
