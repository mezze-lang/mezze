package com.mezze.interpreter

import org.junit.jupiter.api.Disabled
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
            use std::effects::async::{ Async, LoomAsync }
            use std::effects::concurrent::atom::{ atom, deref, reset }

            perform LoomAsync in do
              let counter = atom { value = 0 }
              let fast = {} -> 42
              let slow = {} -> do
                Async.sleep { ms = 200 }
                let _ = reset { atom = counter, value = 1 }
                0
              let pf = Async.spawn { f = fast }
              let ps = Async.spawn { f = slow }
              let winner = Async.race { ps = [pf, ps] }
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
            use std::effects::async::{ Async, CoopAsync }
            use std::effects::concurrent::atom::{ atom, deref, reset }

            perform CoopAsync in do
              let counter = atom { value = 0 }
              let fast = {} -> 42
              let slow = {} -> do
                Async.sleep { ms = 200 }
                let _ = reset { atom = counter, value = 1 }
                0
              let pf = Async.spawn { f = fast }
              let ps = Async.spawn { f = slow }
              let winner = Async.race { ps = [pf, ps] }
              Async.sleep { ms = 400 }
              deref { atom = counter }
        """.trimIndent()
        assertEquals(0L, eval(source))
    }

    // ─── join (two siblings, one fails) ───────────────────────────────────
    //
    // NOTE on shape: Async.join's effect-op returns raw `{l: 'a, r: 'b}` —
    // a failure on either side currently raises through await. The free
    // function `std::effects::async::join` returns Result on each side.
    // Once #122e settles the precise failure surface, revisit the
    // assertions. The cancel-sibling intent is independent of that
    // surface choice: when one fiber commits to failure, the other
    // fiber must be cancelled before counter has time to mutate.

    @Test
    @Disabled("moves to Async.try_join (#122f / #169) — Async.join is now Rust-style: each side runs to completion, no auto-cancel. Re-enable against try_join once it lands.")
    fun `join cancels surviving sibling when one fails under LoomAsync`() {
        // failer uses crash {} (an unrecoverable host error) — the spawn
        // boundary catches the throwable and records AsyncFailed on the
        // l side of the join result. First-failure auto-cancel then
        // interrupts slow before it can bump the counter (slow is in
        // Thread.sleep, so the interrupt takes effect immediately).
        val source = """
            use std::effects::async::{ Async, LoomAsync }
            use std::effects::concurrent::atom::{ atom, deref, reset }

            perform LoomAsync in do
              let counter = atom { value = 0 }
              let failer = {} -> crash { msg = "boom" }
              let slow = {} -> do
                Async.sleep { ms = 200 }
                let _ = reset { atom = counter, value = 1 }
                0
              let _ = Async.join { l = failer, r = slow }
              Async.sleep { ms = 400 }
              deref { atom = counter }
        """.trimIndent()
        assertEquals(0L, eval(source))
    }

    @Test
    @Disabled("moves to Async.try_join (#122f / #169) — Async.join no longer auto-cancels.")
    fun `join cancels surviving sibling when one fails under CoopAsync`() {
        val source = """
            use std::effects::async::{ Async, CoopAsync }
            use std::effects::concurrent::atom::{ atom, deref, reset }
            use std::effects::exception::{ Exception, throw }

            perform CoopAsync in do
              let counter = atom { value = 0 }
              let failer = {} -> throw { error = "boom" }
              let slow = {} -> do
                Async.sleep { ms = 200 }
                let _ = reset { atom = counter, value = 1 }
                0
              let _ = Async.join { l = failer, r = slow }
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
            use std::effects::async::{ Async, LoomAsync }
            use std::effects::concurrent::atom::{ atom, deref, swap }

            perform LoomAsync in do
              let counter = atom { value = 0 }
              let bump = {} -> do
                let _ = swap { atom = counter, f = { current } -> current + 1 }
                0
              let _ = Async.join { l = bump, r = bump }
              deref { atom = counter }
        """.trimIndent()
        assertEquals(2L, eval(source))
    }

    // ─── join3 — first failure cancels the other two ──────────────────────

    @Test
    @Disabled("moves to Async.try_join3 (#122f / #169) — Async.join3 no longer auto-cancels.")
    fun `join3 cancels two surviving siblings when one fails under LoomAsync`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync }
            use std::effects::concurrent::atom::{ atom, deref, swap }

            perform LoomAsync in do
              let counter = atom { value = 0 }
              let failer = {} -> crash { msg = "boom" }
              let slow1 = {} -> do
                Async.sleep { ms = 200 }
                let _ = swap { atom = counter, f = { current } -> current + 1 }
                0
              let slow2 = {} -> do
                Async.sleep { ms = 200 }
                let _ = swap { atom = counter, f = { current } -> current + 1 }
                0
              let _ = Async.join3 { a = failer, b = slow1, c = slow2 }
              Async.sleep { ms = 400 }
              deref { atom = counter }
        """.trimIndent()
        assertEquals(0L, eval(source))
    }
}
