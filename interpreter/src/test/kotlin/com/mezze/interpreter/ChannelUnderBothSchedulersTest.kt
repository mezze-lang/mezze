package com.mezze.interpreter

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Producer/consumer using park-aware send_async / recv_async.
 *
 * Loom works today. Coop variant is currently @Disabled — the
 * CoopScheduler is non-reentrant: when a parked task (producer)
 * itself calls park, the inner parkUntil deadlocks because the
 * outer parker (main) isn't in the ready queue. The proper fix is
 * the deferred #106 (Java scheduler on EffectFiber suspension);
 * without it, Coop only supports a single parked task at a time.
 */
class ChannelUnderBothSchedulersTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun program(handler: String) = """
        use std::effects::async::{ Async, $handler }
        use std::effects::concurrent::channel::{ chan_bounded, send_async, recv_async }

        perform $handler in do
          let ch = chan_bounded { cap = 2 }
          let producer = Async.spawn { f = {} -> do
            send_async { ch = ch, value = 10 }
            send_async { ch = ch, value = 20 }
            send_async { ch = ch, value = 30 }
          }
          let v1 = recv_async { ch = ch }
          let v2 = recv_async { ch = ch }
          let v3 = recv_async { ch = ch }
          let _ = Async.await { p = producer }
          v1 + v2 + v3
    """.trimIndent()

    @Test
    fun `producer-consumer works under LoomAsync`() {
        assertEquals(60L, eval(program("LoomAsync")))
    }

    @Test
    @Disabled("CoopScheduler is non-reentrant — see class docstring; needs #106.")
    fun `producer-consumer works under CoopAsync`() {
        assertEquals(60L, eval(program("CoopAsync")))
    }
}
