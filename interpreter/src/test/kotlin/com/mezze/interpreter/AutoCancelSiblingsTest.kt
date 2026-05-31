package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Spec tests for #122: race/join/join3 must cancel losing/sibling
 * tasks once the combinator commits to a result. Today the siblings
 * keep running until the surrounding scope ends — these tests are
 * red until #159 (Loom) / #160 (Coop) implement auto-cancel.
 *
 * Each test spawns:
 *   - a fast task that produces a value (or fails fast)
 *   - one or more slow tasks that, after a sleep, mutate a shared
 *     counter
 *
 * If the slow task is auto-cancelled when the combinator returns,
 * the counter stays at 0. If not, the slow task wakes up later and
 * bumps the counter to 1.
 *
 * Tests sleep briefly *after* the combinator returns to give an
 * un-cancelled loser time to bump the counter.
 */
class AutoCancelSiblingsTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ─── race ─────────────────────────────────────────────────────────────

    @Test
    fun `race cancels losing sibling under LoomAsync`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, race, with_timeout, join, join3 }
            use std::effects::async::atom::{ atom, deref, reset }

            perform LoomAsync in do
              let counter = atom { value = 0 }
              let fast = {} -> 42
              let slow = {} -> do
                Async.sleep { ms = 200 }
                let _ = reset { atom = counter, value = 1 }
                0
              let pf = Async.spawn { f = fast }
              let ps = Async.spawn { f = slow }
              let winner = race { ps = [pf, ps] }
              Async.sleep { ms = 400 }
              deref { atom = counter }
        """.trimIndent()
        // Expected: 0 (slow was cancelled before bumping counter)
        // Today: 1 (slow ran to completion in the background)
        assertEquals(0L, eval(source))
    }

    @Test
    fun `race cancels losing sibling under CoopAsync`() {
        val source = """
            use std::effects::async::{ Async, CoopAsync, race, with_timeout, join, join3 }
            use std::effects::async::atom::{ atom, deref, reset }

            perform CoopAsync in do
              let counter = atom { value = 0 }
              let fast = {} -> 42
              let slow = {} -> do
                Async.sleep { ms = 200 }
                let _ = reset { atom = counter, value = 1 }
                0
              let pf = Async.spawn { f = fast }
              let ps = Async.spawn { f = slow }
              let winner = race { ps = [pf, ps] }
              Async.sleep { ms = 400 }
              deref { atom = counter }
        """.trimIndent()
        assertEquals(0L, eval(source))
    }

    // ─── join (two siblings, one fails) ───────────────────────────────────
    //
    // NOTE on shape: join's effect-op returns raw `{l: 'a, r: 'b}` —
    // a failure on either side currently raises through await. The free
    // function `std::effects::async::join` returns Result on each side.
    // Once #122e settles the precise failure surface, revisit the
    // assertions. The cancel-sibling intent is independent of that
    // surface choice: when one fiber commits to failure, the other
    // fiber must be cancelled before counter has time to mutate.

    @Test
    fun `try_join cancels surviving sibling when one fails under LoomAsync`() {
        // failer uses crash {} (an unrecoverable host error) — the spawn
        // boundary catches the throwable and records AsyncFailed on the
        // l side. try_join's fail-fast semantics then cancels slow before
        // it can bump the counter (slow is in Thread.sleep, so the
        // interrupt takes effect immediately).
        //
        // We use `crash` rather than `throw` here because `throw` raises
        // an Exception effect that the typechecker tracks through the
        // spawned thunk's effect row. With no Exception handler in scope,
        // the program won't type-check — even though the runtime spawn
        // boundary would intercept the throw before it left the task.
        // `crash` is host-level and side-steps the effect row.
        val source = """
            use std::effects::async::{ Async, LoomAsync, try_join }
            use std::effects::async::atom::{ atom, deref, reset }

            perform LoomAsync in do
              let counter = atom { value = 0 }
              let failer = {} -> crash { msg = "boom" }
              let slow = {} -> do
                Async.sleep { ms = 200 }
                reset { atom = counter, value = 1 }
                0
              try_join { l = failer, r = slow }
              Async.sleep { ms = 400 }
              deref { atom = counter }
        """.trimIndent()
        assertEquals(0L, eval(source))
    }

    @Test
    fun `try_join cancels surviving sibling when one fails under CoopAsync`() {
        // Same shape as the Loom test but under CoopAsync. The
        // cooperative scheduler must observe the spawn boundary's
        // AsyncFailed and cancel `slow` before its sleep resumes.
        val source = """
            use std::effects::async::{ Async, CoopAsync, try_join }
            use std::effects::async::atom::{ atom, deref, reset }

            perform CoopAsync in do
              let counter = atom { value = 0 }
              let failer = {} -> crash { msg = "boom" }
              let slow = {} -> do
                Async.sleep { ms = 200 }
                reset { atom = counter, value = 1 }
                0
              try_join { l = failer, r = slow }
              Async.sleep { ms = 400 }
              deref { atom = counter }
        """.trimIndent()
        assertEquals(0L, eval(source))
    }

    // ─── join with success — both effects observable ────────────────────────

    @Test
    fun `join with all success does NOT cancel anyone under LoomAsync`() {
        // Under the Rust-style join semantics (no auto-cancel; each side
        // runs to completion), both bumps fire and the counter is 2.
        // Uses `swap` for an atomic increment so the read-modify-write is
        // safe under real concurrency.
        val source = """
            use std::effects::async::{ Async, LoomAsync, race, with_timeout, join, join3 }
            use std::effects::async::atom::{ atom, deref, swap }

            perform LoomAsync in do
              let counter = atom { value = 0 }
              let bump = {} -> do
                let _ = swap { atom = counter, f = { current } -> current + 1 }
                0
              let _ = join { l = bump, r = bump }
              deref { atom = counter }
        """.trimIndent()
        assertEquals(2L, eval(source))
    }

    @Test
    fun `join with all success does NOT cancel anyone under CoopAsync`() {
        // Same as the LoomAsync variant — under CoopAsync the cooperative
        // scheduler must still run both bumps to completion when neither
        // fails. Confirms join doesn't ALSO cancel-on-success.
        val source = """
            use std::effects::async::{ Async, CoopAsync, race, with_timeout, join, join3 }
            use std::effects::async::atom::{ atom, deref, swap }

            perform CoopAsync in do
              let counter = atom { value = 0 }
              let bump = {} -> do
                let _ = swap { atom = counter, f = { current } -> current + 1 }
                0
              let _ = join { l = bump, r = bump }
              deref { atom = counter }
        """.trimIndent()
        assertEquals(2L, eval(source))
    }

    // ─── join3 — first failure cancels the other two ──────────────────────

    @Test
    fun `try_join3 cancels two surviving siblings when one fails under LoomAsync`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, try_join3 }
            use std::effects::async::atom::{ atom, deref, swap }

            perform LoomAsync in do
              let counter = atom { value = 0 }
              let failer = {} -> crash { msg = "boom" }
              let slow1 = {} -> do
                Async.sleep { ms = 200 }
                swap { atom = counter, f = { current } -> current + 1 }
                0
              let slow2 = {} -> do
                Async.sleep { ms = 200 }
                swap { atom = counter, f = { current } -> current + 1 }
                0
              try_join3 { a = failer, b = slow1, c = slow2 }
              Async.sleep { ms = 400 }
              deref { atom = counter }
        """.trimIndent()
        assertEquals(0L, eval(source))
    }

    @Test
    fun `try_join3 cancels two surviving siblings when one fails under CoopAsync`() {
        // CoopAsync counterpart — cooperative scheduler must observe the
        // failer's AsyncFailed and cancel both slow1 and slow2 before
        // their sleeps resume.
        val source = """
            use std::effects::async::{ Async, CoopAsync, try_join3 }
            use std::effects::async::atom::{ atom, deref, swap }

            perform CoopAsync in do
              let counter = atom { value = 0 }
              let failer = {} -> crash { msg = "boom" }
              let slow1 = {} -> do
                Async.sleep { ms = 200 }
                swap { atom = counter, f = { current } -> current + 1 }
                0
              let slow2 = {} -> do
                Async.sleep { ms = 200 }
                swap { atom = counter, f = { current } -> current + 1 }
                0
              try_join3 { a = failer, b = slow1, c = slow2 }
              Async.sleep { ms = 400 }
              deref { atom = counter }
        """.trimIndent()
        assertEquals(0L, eval(source))
    }

    // ─── join3 — all-success doesn't cancel anyone ────────────────────────

    @Test
    fun `join3 with all success does NOT cancel anyone under LoomAsync`() {
        // All three siblings run to completion when none fail — counter = 3.
        val source = """
            use std::effects::async::{ Async, LoomAsync, join3 }
            use std::effects::async::atom::{ atom, deref, swap }

            perform LoomAsync in do
              let counter = atom { value = 0 }
              let bump = {} -> do
                let _ = swap { atom = counter, f = { current } -> current + 1 }
                0
              let _ = join3 { a = bump, b = bump, c = bump }
              deref { atom = counter }
        """.trimIndent()
        assertEquals(3L, eval(source))
    }

    @Test
    fun `join3 with all success does NOT cancel anyone under CoopAsync`() {
        val source = """
            use std::effects::async::{ Async, CoopAsync, join3 }
            use std::effects::async::atom::{ atom, deref, swap }

            perform CoopAsync in do
              let counter = atom { value = 0 }
              let bump = {} -> do
                let _ = swap { atom = counter, f = { current } -> current + 1 }
                0
              let _ = join3 { a = bump, b = bump, c = bump }
              deref { atom = counter }
        """.trimIndent()
        assertEquals(3L, eval(source))
    }
}
