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
            use std::comptime::{ comptime }
            use std::effects::noop::{ noop }
            comptime { eval = {} -> do
                noop {}
                6 * 7
            }
            """.trimIndent()
        )
        assertEquals(42L, r)
        assertTrue(evaluations() > before, "the thunk was never evaluated at compile time")
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
            use std::comptime::{ comptime }
            use std::effects::noop::{ noop }
            let a = comptime { eval = {} -> do
                noop {}
                6 * 7
            }
            let b = comptime { eval = {} -> do
                noop {}
                6 * 7
            }
            a + b
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
            use std::comptime::{ comptime }
            use std::effects::noop::{ noop }
            let answer = comptime { eval = {} -> do
                noop {}
                6 * 7
            }
            """.trimIndent()
        )
        assertEquals(before, evaluations(), "mez check / the LSP executed user code")
    }
}
