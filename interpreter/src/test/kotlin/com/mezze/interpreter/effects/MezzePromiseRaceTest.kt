package com.mezze.interpreter.effects

import com.mezze.interpreter.nodes.builtin.effects.AsyncNatives.MezzePromise
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Stress tests for the MezzePromise single-outcome design (#79).
 *
 * Every terminal transition is a CAS on a single AtomicReference<Outcome>.
 * The CAS winner installs its full Outcome (state + payload atomically);
 * losing writers return false with no side effects. These tests verify
 * that under high-contention races the observable outcome always matches
 * whichever thread's CAS actually won.
 *
 * Before the fix (state and result as separate volatile fields with
 * ordered writes), the loser's payload store would clobber the winner's
 * value. These tests would fail intermittently.
 */
class MezzePromiseRaceTest {

    private val THREAD_COUNT = 16
    private val ITERATIONS = 5_000

    @Test
    @Timeout(60)
    fun `complete race — winner's value survives`() {
        repeat(ITERATIONS) { iter ->
            val promise = MezzePromise(null, null)
            val start = CountDownLatch(1)
            val winnerId = AtomicInteger(-1)
            val threads = (0 until THREAD_COUNT).map { id ->
                Thread {
                    start.await()
                    if (promise.complete(id)) {
                        winnerId.compareAndSet(-1, id)
                    }
                }.also { it.start() }
            }
            start.countDown()
            threads.forEach { it.join(5000) }

            val observed = promise.snapshotResult()
            val expected = winnerId.get()

            assertEquals(
                expected, observed,
                "iter $iter: expected winner id $expected but observed $observed"
            )
        }
    }

    @Test
    @Timeout(60)
    fun `fail race — winner's error survives`() {
        repeat(ITERATIONS) { iter ->
            val promise = MezzePromise(null, null)
            val start = CountDownLatch(1)
            val winnerErr = AtomicReference<String>(null)
            val threads = (0 until THREAD_COUNT).map { id ->
                Thread {
                    start.await()
                    val err = "err-$id"
                    if (promise.fail(err)) {
                        winnerErr.compareAndSet(null, err)
                    }
                }.also { it.start() }
            }
            start.countDown()
            threads.forEach { it.join(5000) }

            val observed = promise.snapshotResult()
            val expected = winnerErr.get()

            assertEquals(
                expected, observed,
                "iter $iter: expected winning error $expected but observed $observed"
            )
        }
    }

    @Test
    @Timeout(60)
    fun `mixed complete-fail race — winner's outcome survives`() {
        repeat(ITERATIONS) { iter ->
            val promise = MezzePromise(null, null)
            val start = CountDownLatch(1)
            val winner = AtomicReference<Pair<String, Any>>(null)
            val threads = (0 until THREAD_COUNT).map { id ->
                Thread {
                    start.await()
                    val even = id % 2 == 0
                    if (even) {
                        val v = "val-$id"
                        if (promise.complete(v)) {
                            winner.compareAndSet(null, "completed" to v)
                        }
                    } else {
                        val e = "err-$id"
                        if (promise.fail(e)) {
                            winner.compareAndSet(null, "failed" to e)
                        }
                    }
                }.also { it.start() }
            }
            start.countDown()
            threads.forEach { it.join(5000) }

            val w = winner.get()
            assertNotNull(w, "iter $iter: no thread won the CAS")
            assertEquals(
                w.second, promise.snapshotResult(),
                "iter $iter: expected winning payload ${w.second} but got ${promise.snapshotResult()}"
            )
        }
    }

    @Test
    @Timeout(60)
    fun `cancel race — winner's reason survives`() {
        repeat(ITERATIONS) { iter ->
            val promise = MezzePromise(null, null)
            val start = CountDownLatch(1)
            val winnerReason = AtomicReference<Any>(null)
            val threads = (0 until THREAD_COUNT).map { id ->
                Thread {
                    start.await()
                    val reason = "reason-$id"
                    if (promise.cancel(reason)) {
                        winnerReason.compareAndSet(null, reason)
                    }
                }.also { it.start() }
            }
            start.countDown()
            threads.forEach { it.join(5000) }

            val observed = promise.cancelReason
            val expected = winnerReason.get()

            assertEquals(
                expected, observed,
                "iter $iter: expected winning reason $expected but observed $observed"
            )
        }
    }

    // ==================== Same-thread double-call semantics ====================

    @Test
    @Timeout(30)
    fun `complete after complete is a no-op`() {
        val promise = MezzePromise(null, null)
        assertTrue(promise.complete("first"))
        assertFalse(promise.complete("second"))
        assertEquals("first", promise.snapshotResult())
    }

    @Test
    @Timeout(30)
    fun `fail after complete is a no-op`() {
        val promise = MezzePromise(null, null)
        assertTrue(promise.complete(42))
        assertFalse(promise.fail("boom"))
        assertEquals(42, promise.snapshotResult())
    }

    @Test
    @Timeout(30)
    fun `cancel after complete is a no-op — no reason overwrite`() {
        val promise = MezzePromise(null, null)
        assertTrue(promise.complete("val"))
        assertFalse(promise.cancel("reason"))
        assertNull(promise.cancelReason)  // winner was complete, not cancel
    }

    @Test
    @Timeout(30)
    fun `complete with null value is legal and observable`() {
        // The unhandled-op path in spawn calls promise.complete(null)
        // and expects observers to see COMPLETED state with a null payload.
        val promise = MezzePromise(null, null)
        assertTrue(promise.complete(null))
        assertTrue(promise.isCompleted)
        assertNull(promise.snapshotResult())
    }

    @Test
    @Timeout(30)
    fun `cancel with null reason is legal`() {
        // Cancel with no reason ⇒ downstream substitutes ExternalInterrupt.
        val promise = MezzePromise(null, null)
        assertTrue(promise.cancel(null))
        assertTrue(promise.isCancelled)
        assertNull(promise.cancelReason)
    }
}
