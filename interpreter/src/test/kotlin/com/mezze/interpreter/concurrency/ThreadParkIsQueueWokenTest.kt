package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.TimingSensitive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Under a thread-per-task scheduler (LoomAsync, OSThreadAsync), a `park` on a predicate
 * that NAMES its wait queue (`wait_on`, which the STM, channels, locks, semaphores,
 * latches and wait groups use — a latch here) is woken by the primitive's signal — as it already was
 * under CoopAsync — not polled. Before, the thread re-ran the predicate on a 1µs..1ms
 * backoff: a ~300ms wait called it hundreds of times. Woken, it is called a handful of
 * times (the first check, then once per signal).
 */
@TimingSensitive
class ThreadParkIsQueueWokenTest {

    private fun predicateCalls(scheduler: String): Long = MezzeTestHelper.run("""
        use std::effects::async::{ Async, $scheduler, await, park, wait_on, sleep }
        use std::effects::async::atom::{ atom, deref, swap }
        use std::effects::async::latch::{ latch, count_down, get_count }
        use std::effects::time::{ SystemTime }

        perform SystemTime in perform $scheduler in do
          let done = latch { count = 1 }
          let calls = atom { val = 0 }
          let waiter = Async.spawn { f = {} ->
            park { predicate = wait_on { on = done, test = {} -> do
              swap { atom = calls, f = { current } -> current + 1 }
              get_count { latch = done } == 0
            } }
          }
          sleep { ms = 300 }
          count_down { latch = done }
          await { p = waiter }
          deref { atom = calls }
    """.trimIndent()) as Long

    @Test fun `LoomAsync wakes a queue wait instead of polling it`() {
        val n = predicateCalls("LoomAsync")
        assertTrue(n in 1..10, "a 300ms queue wait re-checked $n times: it is being polled, not woken")
    }

    @Test fun `OSThreadAsync wakes a queue wait instead of polling it`() {
        val n = predicateCalls("OSThreadAsync")
        assertTrue(n in 1..10, "a 300ms queue wait re-checked $n times: it is being polled, not woken")
    }
}
