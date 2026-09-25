package com.mezze.interpreter.comptime

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Effects inside a comptime block.
 *
 * There is no compile-time effect runtime: the evaluator IS the interpreter, so
 * a handler at compile time is an ordinary handler. What the `eval` slot buys is
 * the guarantee that **nothing escapes** — an effect the thunk does not discharge
 * stays in its row and fails to match, through ordinary unification rather than
 * any comptime-specific rule.
 */
class ComptimeEffectsTest {

    private val use = "use std::comptime::{ comptime }"

    private fun run(src: String): Any = MezzeTestHelper.run("$use\n${src.trimIndent()}")

    private fun rejected(src: String, because: String) {
        val m = try {
            run(src); fail<Nothing>("expected a rejection, but this compiled:\n$src")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            e.message.orEmpty()
        }
        assertTrue(m.contains(because), "expected '$because' in:\n$m")
    }

    @Test
    @Timeout(120)
    fun `a discharged Mut body folds`() {
        assertEquals(12L, run("""
            use std::effects::mutation::{ Mut, LocalMut, MutInt, int_to_mut, read_int, incr1 }
            comptime { eval = {} ->
                perform LocalMut in do
                    let c = int_to_mut { n = 11 }
                    let _ = incr1 { n = c }
                    read_int { n = c }
            }
        """))
    }

    @Test
    @Timeout(120)
    fun `a user-defined effect with a user-defined handler folds`() {
        assertEquals(99L, run("""
            effect Config where { port: {} -> Int <Config> }
            impl Config for FixedConfig where { port = {} -> resume { val = 99 } }
            comptime { eval = {} -> perform FixedConfig in Config.port {} }
        """))
    }

    /** Which handler you perform is part of the block's content hash, so
     *  determinism is the author's choice rather than compiler policy. */
    @Test
    @Timeout(120)
    fun `different handlers over the same body give different folded values`() {
        val src = { performer: String ->
            """
            effect Config where { port: {} -> Int <Config> }
            impl Config for Dev where { port = {} -> resume { val = 1 } }
            impl Config for Prod where { port = {} -> resume { val = 2 } }
            comptime { eval = {} -> perform $performer in Config.port {} }
            """
        }
        assertEquals(1L, run(src("Dev")))
        assertEquals(2L, run(src("Prod")))
    }

    /**
     * `<*>` has a LOWER bound as well as an upper one: the body must perform
     * something. A pure body and a fully-discharged one leave the same empty
     * residual row, so the residual is exactly what cannot tell them apart —
     * `performed` is what does, and the slot asks for it.
     */
    @Test
    @Timeout(120)
    fun `a thunk that performs nothing is rejected`() {
        // The message shows `<*>` on the expected side and its absence on the
        // actual, which is what tells the author what to change.
        rejected("""comptime { eval = {} -> 2 + 2 }""", because = "<*>")
    }

    /** …and `noop` is how a computation that needs no effects says so. The
     *  `perform` is inside `noop`, not here, so this also pins that `performed`
     *  propagates through a CALL. */
    @Test
    @Timeout(120)
    fun `discharging NoOp satisfies the slot`() {
        assertEquals(4L, run("""
            use std::effects::noop::{ noop }
            comptime { eval = {} -> do
                noop {}
                2 + 2
            }
        """))
    }

    @Test
    @Timeout(120)
    fun `an undischarged effect is rejected`() {
        rejected(
            """
            use std::effects::mutation::{ Mut, MutInt, int_to_mut, read_int }
            comptime { eval = {} -> do
                let c = int_to_mut { n = 1 }
                read_int { n = c }
            }
            """,
            because = "Mut",
        )
    }

    @Test
    @Timeout(120)
    fun `a partially discharged body is still rejected`() {
        rejected(
            """
            use std::effects::mutation::{ Mut, MutInt, int_to_mut, read_int }
            effect Config where { port: {} -> Int <Config> }
            impl Config for Dev where { port = {} -> resume { val = 1 } }
            comptime { eval = {} -> perform Dev in do
                let c = int_to_mut { n = 1 }
                read_int { n = c } + Config.port {}
            }
            """,
            because = "Mut",
        )
    }
}
