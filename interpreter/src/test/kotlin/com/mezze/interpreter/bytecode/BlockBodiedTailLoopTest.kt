package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A self-tail loop whose recursing branch is a BLOCK ending in the tail call,
 * which is how an imperative loop reads and the shape every `mut` loop has:
 *
 *     go = { i } -> if i <= 0 then 0
 *                   else do incr { n = c, delta = 1 }
 *                           go { i = i - 1 }
 *
 * Both the fused tiers and the direct-while path below them used to require
 * `elseBr instanceof CallDirectIR`, so this fell to the flag-based fallback.
 * MEASURED on `bench/cases/mutvsimmut` at n=2e7, min of 5:
 *
 * | | fallback | direct-while |
 * |---|---|---|
 * | mut counter | 784 ps/iter | **572** (1.37x) |
 * | mut seq write | 6 612 | **5 818** (1.14x) |
 * | map put | 13 355 | **11 700** (1.14x) |
 *
 * Confirmed in the listing with `MEZZE_DUMP_BC`: the loop body goes from 27
 * instructions per iteration to 14. The fallback spends its extra ops on two
 * `_stl_cont` flag writes, a `_stl_res` store, a `dup` and a
 * `merge.conditional`.
 *
 * MEASURE THIS AT A REAL SIZE. The first reading said direct-while was 1.34x
 * SLOWER, from `bench/cases/mutvsimmut` defaulting to n=2e6 — which at ~800
 * ps/iter is a 1.6 ms run, i.e. warmup, not steady state. At 2e7 and 1e8 the
 * numbers agree within 5% and the sign is the other way. The bench default is
 * now 2e7.
 */
class BlockBodiedTailLoopTest {

    private val emitted = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        BytecodeCompiler.FUSED_EMITS.set(emitted)
    }

    @org.junit.jupiter.api.AfterEach
    fun tearDown() {
        BytecodeCompiler.FUSED_EMITS.remove()
    }

    /**
     * THE PATH, not just the answer. Both paths compute the same result — the
     * flag fallback is correct, only slower — so every value test below passes
     * with this tier disabled. Verified by disabling the recognition: all four
     * stayed green. Without this assertion, losing the tier is silent, which is
     * exactly how `KnownMutOps` managed to be dead from the day it was written.
     */
    @Test
    fun `a block-bodied mut loop takes the direct-while path`() {
        MezzeTestHelper.run(
            """
            use std::effects::mutation::{ LocalMut, int_to_mut, read_int, incr }

            let main = {} -> perform LocalMut in do
              let c = int_to_mut { n = 0 }
              let go = { i } ->
                if i <= 0 then 0
                else do
                  incr { n = c, delta = 1 }
                  go { i = i - 1 }
              go { i = 5 }
              read_int { n = c }

            main {}
            """.trimIndent(),
        )
        kotlin.test.assertTrue(
            emitted.any { it.startsWith("block-while:") },
            "the loop fell back to the flag-based path; expected block-while. " +
                "Emitted: $emitted",
        )
    }

    /** The loop runs the right number of times and the statement takes effect
     *  on every one of them. */
    @Test
    fun `a mut statement before the tail call runs once per iteration`() {
        assertEquals(
            1000L,
            MezzeTestHelper.run(
                """
                use std::effects::mutation::{ LocalMut, int_to_mut, read_int, incr }

                let main = {} -> perform LocalMut in do
                  let c = int_to_mut { n = 0 }
                  let go = { i } ->
                    if i <= 0 then 0
                    else do
                      incr { n = c, delta = 1 }
                      go { i = i - 1 }
                  go { i = 1000 }
                  read_int { n = c }

                main {}
                """.trimIndent(),
            ),
        )
    }

    /** Several statements, in order, and the base case still returns its own
     *  value rather than the loop's. */
    @Test
    fun `several statements keep their order and the base case its value`() {
        assertEquals(
            // 100 iterations x (+3 then x1) with a final read; the base case
            // returns 7, which the caller discards.
            300L,
            MezzeTestHelper.run(
                """
                use std::effects::mutation::{ LocalMut, int_to_mut, read_int, incr, decr }

                let main = {} -> perform LocalMut in do
                  let c = int_to_mut { n = 0 }
                  let go = { i } ->
                    if i <= 0 then 7
                    else do
                      incr { n = c, delta = 4 }
                      decr { n = c, delta = 1 }
                      go { i = i - 1 }
                  go { i = 100 }
                  read_int { n = c }

                main {}
                """.trimIndent(),
            ),
        )
    }

    /** The two-parameter form, where the loop also carries an accumulator. */
    @Test
    fun `a statement coexists with parameter updates`() {
        assertEquals(
            // acc counts down 10..1 summed = 55; the cell counts the iterations.
            "55|10",
            MezzeTestHelper.run(
                """
                use std::effects::mutation::{ LocalMut, int_to_mut, read_int, incr }
                use std::int

                let main = {} -> perform LocalMut in do
                  let c = int_to_mut { n = 0 }
                  let go = { i, acc } ->
                    if i <= 0 then acc
                    else do
                      incr { n = c, delta = 1 }
                      go { i = i - 1, acc = acc + i }
                  let total = go { i = 10, acc = 0 }
                  "${'$'}(int::to_str { n = total })|${'$'}(int::to_str { n = read_int { n = c } })"

                main {}
                """.trimIndent(),
            ),
        )
    }

    /**
     * THE CORRECTNESS CASE, and why it is pinned this way rather than directly.
     *
     * A BDSL block discards a bare statement's value, so a statement returning
     * a `MezzeSuspend` has to be intercepted by `CheckSuspendedThrow` before
     * the drop or the suspension is silently swallowed (#361). The first
     * version of this change emitted the statement bare and left a plain `pop`
     * where that check had been — nothing in the suite caught it, and it was
     * visible only in `MEZZE_DUMP_BC` output. The emission now mirrors the
     * ordinary block emitter: `canYieldSuspended(stmt)` decides, and that is
     * unconditionally true for a `CallNativeIR`, so every statement this path
     * admits gets the check.
     *
     * I could not write a FAILING case for it. The guard admits only native
     * calls, the suspending natives are the async ones, and `Mut` is
     * HERMETIC — the typechecker refuses `Mut` in scope with `Async` outright,
     * so a mut loop cannot contain a suspending statement by construction. The
     * check earns its place for the non-mut loops this path also admits, where
     * no such exclusion applies.
     *
     * So this pins the hermeticity instead, since that is what makes the mut
     * case safe and would quietly widen the exposure if it ever relaxed.
     */
    @Test
    fun `Mut is hermetic, so a mut loop cannot contain a suspending statement`() {
        val err = kotlin.runCatching {
            MezzeTestHelper.run(
                """
                use std::effects::async::{ Async, LoomAsyncWith, await }
                use std::effects::mutation::{ LocalMut, int_to_mut, read_int, incr }

                let go = { i, c } ->
                  if i <= 0 then 0
                  else do
                    incr {
                      n = c,
                      delta = match await { p = Async.spawn { f = {} -> 10 } } is
                        Ok { val } -> val
                        Err { error = _ } -> 0,
                    }
                    go { i = i - 1, c = c }

                let main = {} -> perform LoomAsyncWith { max_concurrent = 2 } in
                  perform LocalMut in do
                    let c = int_to_mut { n = 0 }
                    go { i = 3, c = c }
                    read_int { n = c }

                main {}
                """.trimIndent(),
            )
        }.exceptionOrNull()
        kotlin.test.assertNotNull(err, "Mut coexisting with Async should be refused")
        kotlin.test.assertTrue(
            err.message!!.contains("hermetic"),
            "expected the hermeticity refusal, got: ${err.message}",
        )
    }
}
