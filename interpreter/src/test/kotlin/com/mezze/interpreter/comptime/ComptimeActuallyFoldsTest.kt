package com.mezze.interpreter.comptime

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.comptime.TruffleComptimeEvaluator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The tests that separate real compile-time evaluation from the run-time
 * fallback.
 *
 * Every other comptime test asserts the program's answer — and a folded program
 * and an unfolded one give the SAME answer, by design, because the declaration's
 * `eval {}` body means an unreduced call still computes correctly. That safety
 * property is also what makes those tests blind to whether folding happened at
 * all. These are not.
 */
class ComptimeActuallyFoldsTest {

    private fun evaluations(): Long = TruffleComptimeEvaluator.evaluations.get()

    @Test
    @Timeout(120)
    fun `a comptime block is evaluated while compiling`() {
        val before = evaluations()
        val r = MezzeTestHelper.run(
            """
            use std::comptime::{ Comptime, ComptimeEval }
            use std::effects::noop::{ noop }
            (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                6 * 7
            })
            """.trimIndent()
        )
        assertEquals(42L, r)
        assertTrue(evaluations() > before, "the thunk was never evaluated at compile time")
    }

    /**
     * A comptime inside a comptime thunk. Both fold, independently.
     *
     * Not "the outer folds and carries the inner with it", which is the natural
     * guess and is wrong: a thunk is lowered to its OWN `FunctionIR`, and the
     * reduction pass iterates functions. So the inner block is a top-level
     * stamped site in the thunk's function and is reduced on its own terms —
     * nesting in the source does not nest the reduction. Hence two evaluations,
     * not one, and the count is asserted exactly so that a change here is
     * noticed rather than absorbed.
     *
     * `Comptime.isEvaluating()` is a different mechanism and still required: it
     * stops the nested RUNTIME re-entry — evaluating a thunk re-enters the
     * runtime with a program that still contains the call being evaluated — from
     * reducing again, which otherwise recurses without end.
     */
    @Test
    @Timeout(120)
    fun `a nested comptime folds at both sites, independently`() {
        val before = evaluations()
        val r = MezzeTestHelper.run(
            """
            use std::comptime::{ Comptime, ComptimeEval }
            use std::effects::noop::{ noop }
            (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                3 * (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                    noop {}
                    5 + 9
                })
            })
            """.trimIndent()
        )
        assertEquals(42L, r, "the nested block must still compute the right answer")
        assertEquals(
            2L, evaluations() - before,
            "expected both sites to be evaluated: each thunk is its own FunctionIR and " +
                "the pass iterates functions, so the inner block is a site in its own right",
        )
    }

    @Test
    @Timeout(120)
    fun `a program with no comptime evaluates nothing`() {
        val before = evaluations()
        MezzeTestHelper.run("""1 + 1""")
        assertEquals(before, evaluations(), "a program without comptime paid for the pass")
    }

    /**
     * Identical thunks are the same function — the hash is over content — so a
     * body written twice is evaluated once.
     *
     * Sound within one compilation even for an effectful thunk, since nothing can
     * change underneath it. Across builds it would NOT be: a thunk's hash covers
     * its code, not the file it read. That is why the memo is per-compilation.
     */
    @Test
    @Timeout(120)
    fun `identical thunks are evaluated once`() {
        val before = evaluations()
        val r = MezzeTestHelper.run(
            """
            use std::comptime::{ Comptime, ComptimeEval }
            use std::effects::noop::{ noop }
            let a = {} -> (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                6 * 7
            })
            let b = {} -> (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                6 * 7
            })
            a {} + b {}
            """.trimIndent()
        )
        assertEquals(84L, r)
        assertEquals(1L, evaluations() - before, "the identical thunk was evaluated twice")
    }

    /**
     * The guarantee that makes `comptime` safe to have in an editor: type
     * checking needs the block's TYPE, never its VALUE, so a tool that stops
     * after type checking never runs user code. No side effects while you type,
     * no hang from a runaway thunk, and a dependency's code runs only when you
     * actually build.
     */
    @Test
    @Timeout(120)
    fun `type checking alone evaluates nothing`() {
        val before = evaluations()
        MezzeTestHelper.typeCheck(
            """
            use std::comptime::{ Comptime, ComptimeEval }
            use std::effects::noop::{ noop }
            let answer = {} -> (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                6 * 7
            })
            """.trimIndent()
        )
        assertEquals(before, evaluations(), "mez check / the LSP executed user code")
    }
}
