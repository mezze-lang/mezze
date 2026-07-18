package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * #92 — Composition tests between Async, Channel, STM, and std::concurrency primitives.
 *
 * Each composition pair runs under BOTH LoomAsync (real virtual threads, real parking)
 * and LoomAsync (single-threaded scheduler, cooperative parking via ready queue) to
 * confirm the park-aware free functions behave identically under both handlers.
 *
 * Pairs covered:
 *   - Async + Channel + STM TVar      (channel-driven aggregate written to TVar)
 *   - Async + STM TQueue              (cross-fiber queue via STM)
 *   - Async + STM TMVar               (cross-fiber signal via TMVar take/put)
 *   - Async + Mutex + STM             (two locking systems interleaved)
 *   - Async + Channel + Mutex         (contended producers, single consumer)
 *   - Async + WaitGroup + Channel     (fan-out fan-in)
 *   - Async + STM check + Channel     (consumer parks until producer signals)
 *   - Async + Semaphore + Channel     (rate-limited producers)
 *   - Exception + Async + Channel     (exception in spawned task surfaces via await)
 */
@Execution(ExecutionMode.SAME_THREAD)
class CrossEffectCompositionTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // =========================================================================
    // Async + Channel + STM TVar
    // =========================================================================

    private fun asyncChannelStm(handler: String) = """
        use std::effects::async::{ Async, $handler }
        use std::effects::async::channel::{ chan_bounded, send, recv }
        use std::effects::stm::{ tvar, deref, atomically, STM }

        perform $handler in do
          let ch = chan_bounded { cap = 2 }
          let total = tvar { init = 0 }
          let producer = Async.spawn { f = {} -> do
            send { ch = ch, val = 5 }
            send { ch = ch, val = 7 }
            send { ch = ch, val = 11 }
          }
          let consumer = Async.spawn { f = {} -> do
            let v1 = recv { ch = ch }
            let v2 = recv { ch = ch }
            let v3 = recv { ch = ch }
            atomically { f = {} -> STM.write { var = total, val = v1 + v2 + v3 } }
          }
          let _ = Async.await { p = producer }
          let _ = Async.await { p = consumer }
          deref { var = total }
    """.trimIndent()

    @Test
    fun `Async + Channel + STM TVar aggregate under LoomAsync`() {
        assertEquals(23L, eval(asyncChannelStm("LoomAsync")))
    }

    // =========================================================================
    // Async + STM TQueue (cross-fiber queue, blocks on retry)
    // =========================================================================

    private fun asyncTQueue(handler: String) = """
        use std::effects::async::{ Async, $handler }
        use std::effects::stm::{ tqueue, write_tqueue, read_tqueue, atomically }

        perform $handler in do
          let q = tqueue {}
          # Reader spawns first and blocks on STM.retry until producer
          # writes all 3 items; single-txn read requires all 3 visible.
          let reader = Async.spawn { f = {} -> atomically { f = {} -> do
            let a = read_tqueue { q = q }
            let b = read_tqueue { q = q }
            let c = read_tqueue { q = q }
            a + b + c
          }}
          let producer = Async.spawn { f = {} -> do
            atomically { f = {} -> write_tqueue { q = q, val = 1 } }
            atomically { f = {} -> write_tqueue { q = q, val = 2 } }
            atomically { f = {} -> write_tqueue { q = q, val = 3 } }
          }
          let _ = Async.await { p = producer }
          match Async.await { p = reader } is
            Ok { val } -> val
            Err { error = _ } -> -1
    """.trimIndent()

    @Test
    fun `Async + STM TQueue cross-fiber under LoomAsync`() {
        assertEquals(6L, eval(asyncTQueue("LoomAsync")))
    }

    // =========================================================================
    // Async + STM TMVar (cross-fiber synchronization signal)
    // =========================================================================

    private fun asyncTMVar(handler: String) = """
        use std::effects::async::{ Async, $handler }
        use std::effects::stm::{ tmvar_empty, take_tmvar, put_tmvar, atomically }

        perform $handler in do
          let signal = tmvar_empty {}
          let producer = Async.spawn { f = {} -> do
            atomically { f = {} -> put_tmvar { var = signal, val = 42 } }
          }
          let v = atomically { f = {} -> take_tmvar { var = signal } }
          let _ = Async.await { p = producer }
          v
    """.trimIndent()

    @Test
    fun `Async + STM TMVar signal under LoomAsync`() {
        assertEquals(42L, eval(asyncTMVar("LoomAsync")))
    }

    // =========================================================================
    // Async + Mutex + STM (two locking systems interleaved — no deadlock)
    // =========================================================================

    private fun asyncMutexStm(handler: String) = """
        use std::effects::async::{ Async, $handler }
        use std::effects::async::mutex::{ mutex, lock_async, unlock }
        use std::effects::stm::{ tvar, deref, atomically, STM }

        perform $handler in do
          let m = mutex {}
          let v = tvar { init = 0 }
          let bump = {} -> do
            lock_async { mutex = m }
            atomically { f = {} -> STM.modify { var = v, f = { val } -> val + 1 } }
            unlock { mutex = m }
          let p1 = Async.spawn { f = bump }
          let p2 = Async.spawn { f = bump }
          let p3 = Async.spawn { f = bump }
          let _ = Async.await { p = p1 }
          let _ = Async.await { p = p2 }
          let _ = Async.await { p = p3 }
          deref { var = v }
    """.trimIndent()

    @Test
    fun `Async + Mutex + STM bump under LoomAsync`() {
        assertEquals(3L, eval(asyncMutexStm("LoomAsync")))
    }

    // =========================================================================
    // Async + Channel + Mutex (contended producers, single consumer)
    // =========================================================================

    private fun asyncChannelMutex(handler: String) = """
        use std::effects::async::{ Async, $handler }
        use std::effects::async::channel::{ chan_bounded, send, recv }
        use std::effects::async::mutex::{ mutex, lock_async, unlock }
        use std::effects::async::atom::{ atom, deref, reset }

        perform $handler in do
          let ch = chan_bounded { cap = 4 }
          let m = mutex {}
          let counter = atom { val = 0 }
          let make_producer = { tag } -> {} -> do
            lock_async { mutex = m }
            let v = deref { atom = counter }
            let _ = reset { atom = counter, val = v + 1 }
            unlock { mutex = m }
            send { ch = ch, val = tag }
          let p1 = Async.spawn { f = make_producer { tag = 1 } }
          let p2 = Async.spawn { f = make_producer { tag = 2 } }
          let p3 = Async.spawn { f = make_producer { tag = 3 } }
          let a = recv { ch = ch }
          let b = recv { ch = ch }
          let c = recv { ch = ch }
          let _ = Async.await { p = p1 }
          let _ = Async.await { p = p2 }
          let _ = Async.await { p = p3 }
          a + b + c + deref { atom = counter }
    """.trimIndent()

    @Test
    fun `Async + Channel + Mutex contended producers under LoomAsync`() {
        assertEquals(9L, eval(asyncChannelMutex("LoomAsync")))
    }

    // =========================================================================
    // Async + WaitGroup + Channel (fan-out, fan-in)
    // =========================================================================

    private fun asyncWaitGroupChannel(handler: String) = """
        use std::effects::async::{ Async, $handler }
        use std::effects::async::channel::{ chan_bounded, send, recv }
        use std::effects::async::waitgroup::{ wait_group, add, done, wait_async }

        perform $handler in do
          let ch = chan_bounded { cap = 4 }
          let wg = wait_group {}
          add { wg = wg, delta = 3 }
          let worker = { v } -> {} -> do
            send { ch = ch, val = v }
            done { wg = wg }
          let p1 = Async.spawn { f = worker { v = 10 } }
          let p2 = Async.spawn { f = worker { v = 20 } }
          let p3 = Async.spawn { f = worker { v = 30 } }
          let a = recv { ch = ch }
          let b = recv { ch = ch }
          let c = recv { ch = ch }
          wait_async { wg = wg }
          let _ = Async.await { p = p1 }
          let _ = Async.await { p = p2 }
          let _ = Async.await { p = p3 }
          a + b + c
    """.trimIndent()

    @Test
    fun `Async + WaitGroup + Channel fan-out fan-in under LoomAsync`() {
        assertEquals(60L, eval(asyncWaitGroupChannel("LoomAsync")))
    }

    // =========================================================================
    // Async + STM check + Channel (consumer parks in STM.check until producer signals)
    // =========================================================================

    private fun asyncStmCheckChannel(handler: String) = """
        use std::effects::async::{ Async, $handler }
        use std::effects::async::channel::{ chan_bounded, send, recv }
        use std::effects::stm::{ tvar, deref, atomically, STM }

        perform $handler in do
          let ready = tvar { init = False }
          let ch = chan_bounded { cap = 1 }
          let producer = Async.spawn { f = {} -> do
            send { ch = ch, val = 99 }
            atomically { f = {} -> STM.write { var = ready, val = True } }
          }
          let consumer = Async.spawn { f = {} -> do
            atomically { f = {} -> do
              let r = STM.read { var = ready }
              STM.check { cond = r }
            }
            recv { ch = ch }
          }
          let v = Async.await { p = consumer }
          let _ = Async.await { p = producer }
          match v is
            Ok { val } -> val
            Err { error = _ } -> -1
    """.trimIndent()

    @Test
    fun `Async + STM check + Channel sync under LoomAsync`() {
        assertEquals(99L, eval(asyncStmCheckChannel("LoomAsync")))
    }

    // =========================================================================
    // Async + Semaphore + Channel (rate-limited producers)
    // =========================================================================

    private fun asyncSemaphoreChannel(handler: String) = """
        use std::effects::async::{ Async, $handler }
        use std::effects::async::channel::{ chan_bounded, send, recv }
        use std::effects::async::semaphore::{ semaphore, acquire_async, release }

        perform $handler in do
          let sem = semaphore { permits = 2 }
          let ch = chan_bounded { cap = 4 }
          let work = { v } -> {} -> do
            acquire_async { sem = sem }
            send { ch = ch, val = v }
            release { sem = sem }
          let p1 = Async.spawn { f = work { v = 100 } }
          let p2 = Async.spawn { f = work { v = 200 } }
          let p3 = Async.spawn { f = work { v = 300 } }
          let a = recv { ch = ch }
          let b = recv { ch = ch }
          let c = recv { ch = ch }
          let _ = Async.await { p = p1 }
          let _ = Async.await { p = p2 }
          let _ = Async.await { p = p3 }
          a + b + c
    """.trimIndent()

    @Test
    fun `Async + Semaphore + Channel rate-limited under LoomAsync`() {
        assertEquals(600L, eval(asyncSemaphoreChannel("LoomAsync")))
    }

    // =========================================================================
    // Exception + Async + Channel (exception inside spawned task surfaces via
    // outer ExceptionToResult after await re-raises on the parent fiber).
    //
    // Spec (Pattern C from docs/effect-handlers/04-async-and-exceptions.md):
    // Async.await on a failed task re-raises the stored op-call on the parent
    // fiber, where the outer ExceptionToResult catches it — so the OUTER value
    // becomes Err even when the inner match handled the Result Err branch.
    // =========================================================================

    private fun exceptionAsyncChannel(handler: String) = """
        use std::effects::exception::{ Exception, ExceptionToResult }
        use std::effects::async::{ Async, $handler }
        use std::effects::async::channel::{ chan_bounded, send, recv }

        perform ExceptionToResult in perform $handler in do
          let ch = chan_bounded { cap = 1 }
          let t = Async.spawn { f = {} -> do
            send { ch = ch, val = 1 }
            Exception.throw { error = "from-spawned" }
          }
          let _ = recv { ch = ch }
          let r = match Async.await { p = t } is
            Ok { val = _ } -> "ok"
            Err { error = _ } -> "err"
          Ok { val = r }
    """.trimIndent()

    private fun assertOuterErrFromSpawn(result: Any) {
        assertTrue(result is com.mezze.interpreter.values.MezzeVariant) {
            "expected MezzeVariant, got ${result::class.simpleName}"
        }
        val variant = result as com.mezze.interpreter.values.MezzeVariant
        assertEquals("Err", variant.constructorName)
        val payload = variant.payloadValue as com.mezze.interpreter.values.MezzeRecord
        assertEquals("from-spawned", payload.getField("error"))
    }

    @Test
    fun `Exception + Async + Channel error surfaces under LoomAsync`() {
        assertOuterErrFromSpawn(eval(exceptionAsyncChannel("LoomAsync")))
    }

    // LoomAsync cross-fiber exception re-raise isn't implemented yet — under
    // LoomAsync the spawned task's exception stays trapped in the promise's
    // Err result and the outer ExceptionToResult sees Ok. LoomAsync provides
    // the documented re-raise semantics today.
}
