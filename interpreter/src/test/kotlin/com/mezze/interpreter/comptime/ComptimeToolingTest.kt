package com.mezze.interpreter.comptime

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.comptime.TruffleComptimeEvaluator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The properties that make `comptime` safe to have in an editor.
 *
 * All of them rest on one fact: type checking needs a comptime block's TYPE,
 * never its VALUE. Inference reads the type off the thunk, and `Embeddable` is a
 * type-level obligation — the value never feeds back into typing, which is also
 * why comptime is barred from type position. So a tool that stops after type
 * checking never executes user code.
 *
 * Each of these fails silently if it regresses, which is why they are asserted
 * rather than assumed.
 */
class ComptimeToolingTest {

    private fun evaluations(): Long = TruffleComptimeEvaluator.evaluations.get()

    /**
     * The load-bearing one. A comptime that writes files, calls the network, or
     * loops forever must not do any of that on every keystroke — and a
     * dependency's code must run only when you actually build.
     *
     * This mirrors Rust: rust-analyzer expands proc macros but does NOT do real
     * const evaluation, which lives in rustc.
     */
    @Test
    @Timeout(120)
    fun `type checking a module full of comptime evaluates nothing`() {
        val before = evaluations()
        MezzeTestHelper.typeCheck(
            """
            use std::comptime::{ comptime }
            use std::effects::noop::{ noop }
            let a = comptime { eval = {} -> do
                noop {}
                1 + 1
            }
            let b = comptime { eval = {} -> do
                noop {}
                "x"
            }
            let c = comptime { eval = {} -> do
                noop {}
                { k = 3 }
            }
            """.trimIndent()
        )
        assertEquals(before, evaluations(), "type checking executed user code")
    }

    /**
     * Nothing degrades from skipping evaluation: the block's type is known
     * without running it, so hover, completion and go-to-definition still work.
     * A type is what the editor asks for, and it is present.
     */
    @Test
    @Timeout(120)
    fun `a comptime binding still has a type after a check-only pass`() {
        val before = evaluations()
        val result = MezzeTestHelper.typeCheck(
            """
            use std::comptime::{ comptime }
            use std::effects::noop::{ noop }
            let answer = comptime { eval = {} -> do
                noop {}
                6 * 7
            }
            """.trimIndent()
        )
        assertNotNull(result.getVariableType("answer"), "no type for a comptime binding")
        assertEquals(before, evaluations(), "resolving the type required evaluating it")
    }

    /**
     * The accepted cost of the split, recorded so it is not filed as a bug:
     * `check` is strictly weaker than `build`. A comptime that CRASHES
     * type-checks clean and fails at build time.
     *
     * `Embeddable` is checked by both, so the gap is exactly "crashes" and
     * "does not terminate" — the same relationship type checking has to running
     * anywhere else.
     */
    @Test
    @Timeout(120)
    fun `a crashing comptime passes check and fails build`() {
        val src = """
            use std::comptime::{ comptime }
            use std::effects::noop::{ noop }
            let boom = comptime { eval = {} -> do
                noop {}
                1 / 0
            }
        """.trimIndent()

        // check: clean.
        MezzeTestHelper.typeCheck(src)

        // build: fails.
        val failed = try {
            MezzeTestHelper.run("$src\nboom")
            false
        } catch (e: Throwable) {
            true
        }
        assertEquals(true, failed, "a crashing comptime reached run time instead of failing the build")
    }
}
