package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Producer/consumer using park-aware send / recv.
 *
 * After #113 (CoopScheduler on EffectFiber suspension), both producer
 * and consumer can park concurrently — each runs on its own JDK
 * delimited continuation, so nested park no longer deadlocks.
 *
 * Post-#115: `send`/`recv` are the park-aware free functions on `Chan`;
 * the old separate `send_async`/`recv_async` names are gone since the
 * blocking variants are gone too.
 */
class ChannelUnderBothSchedulersTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun program(handler: String) = """
        use std::effects::async::{ Async, $handler }
        use std::effects::concurrent::channel::{ chan_bounded, send, recv }

        perform $handler in do
          let ch = chan_bounded { cap = 2 }
          let producer = Async.spawn { f = {} -> do
            send { ch = ch, value = 10 }
            send { ch = ch, value = 20 }
            send { ch = ch, value = 30 }
          }
          let v1 = recv { ch = ch }
          let v2 = recv { ch = ch }
          let v3 = recv { ch = ch }
          let _ = Async.await { p = producer }
          v1 + v2 + v3
    """.trimIndent()

    @Test
    fun `producer-consumer works under LoomAsync`() {
        assertEquals(60L, eval(program("LoomAsync")))
    }

    @Test
    fun `producer-consumer works under CoopAsync`() {
        assertEquals(60L, eval(program("CoopAsync")))
    }
}
