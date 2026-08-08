package com.mezze.interpreter

import com.mezze.interpreter.nodes.builtin.io.DateTimeNatives
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Regression test for #244: `Time.sleep` used to swallow `InterruptedException`,
 * restoring the interrupt flag but returning `{}` to Mezze callers like
 * nothing happened. That made SIGINT/SIGTERM during a long `Time.sleep`
 * silently no-op — the program kept running with no visible signal.
 *
 * Behaviour now matches `AsyncNatives.sleep`: restore the flag AND
 * surface the interrupt as a `RuntimeException` so user code observes it.
 */
class TimeSleepInterruptTest {

    @Test
    fun `sleep throws when interrupt arrives before the call`() {
        // Pre-set the interrupt flag. Thread.sleep checks this before
        // blocking and immediately throws InterruptedException.
        Thread.currentThread().interrupt()
        try {
            assertThrows(RuntimeException::class.java) {
                DateTimeNatives.sleep(DateTimeNatives.MezzeDuration(Duration.ofMillis(10_000)))
            }
        } finally {
            // Clean up: clear the flag so this test doesn't leak interrupt
            // state into whatever runs after it on this thread.
            Thread.interrupted()
        }
    }

    @Test
    fun `sleepNanos throws when interrupt arrives before the call`() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(RuntimeException::class.java) {
                DateTimeNatives.sleep(DateTimeNatives.MezzeDuration(Duration.ofNanos(10_000_000_000L)))
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `sleep throws when interrupted mid-call from another thread`() {
        val main = Thread.currentThread()
        val interrupter = Thread {
            Thread.sleep(50)
            main.interrupt()
        }
        interrupter.start()
        try {
            assertThrows(RuntimeException::class.java) {
                DateTimeNatives.sleep(DateTimeNatives.MezzeDuration(Duration.ofMillis(10_000)))
            }
        } finally {
            Thread.interrupted()
            interrupter.join()
        }
    }

    @Test
    fun `sleep returns normally with no interrupt`() {
        // Sanity check: no interrupt → no exception, returns MezzeUnit.
        val result = DateTimeNatives.sleep(DateTimeNatives.MezzeDuration(Duration.ofMillis(1)))
        assertNotNull(result)
        assertFalse(Thread.currentThread().isInterrupted)
    }
}
