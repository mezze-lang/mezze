package com.mezze.interpreter.comptime

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * `std::comptime::comptime` as a *declaration* — its signature, its
 * `Embeddable` obligation, and what its effect slot admits.
 *
 * The reduction pass (task #7) does not exist yet, so these run through the
 * declaration's runtime-fallback body (`eval {}`). That is the point of the
 * fallback: the meaning is the same either way, so the feature lands in stages
 * and every one of these tests stays valid once reduction is added.
 */
class ComptimeDeclarationTest {

    private val use =
        "use std::comptime::{ Comptime, ComptimeEval }\nuse std::effects::noop::{ noop }"

    private fun run(src: String): Any = MezzeTestHelper.run("$use\n${src.trimIndent()}")

    private fun rejects(src: String, because: String): Boolean =
        try {
            run(src); false
        } catch (e: Throwable) {
            val m = e.message.orEmpty()
            if (!m.contains(because)) throw AssertionError("wrong failure: $m", e)
            true
        }

    @Test
    @Timeout(120)
    fun `a pure thunk needs no ceremony`() {
        assertEquals(4L, run("""(perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                2 + 2
            })"""))
    }

    @Test
    @Timeout(120)
    fun `calls a top-level function`() {
        assertEquals(120L, run("""
            let factorial = { n } -> if n <= 1 then 1 else n * factorial { n = n - 1 }
            (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                factorial { n = 5 }
            })
        """))
    }

    @Test
    @Timeout(120)
    fun `returns composite data`() {
        assertEquals(2L, run("""(perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                { x = 1, y = 2 }
            }).y"""))
    }

    /** The effect slot is a plain thunk, and that IS the contract: a discharged
     *  body matches, an escaping one does not. No `<*>` syntax required. */
    @Test
    @Timeout(120)
    fun `effects are allowed when the thunk discharges them itself`() {
        assertEquals(7L, run("""
            use std::effects::mutation::{ Mut, LocalMut, MutInt, int_to_mut, read_int }
            (perform ComptimeEval in Comptime.comptime { eval = {} ->
                perform LocalMut in do
                    let c = int_to_mut { n = 7 }
                    read_int { n = c }
            })
        """))
    }

    @Test
    @Timeout(120)
    fun `an effect that escapes the thunk is rejected`() {
        assertTrue(rejects("""
            use std::effects::mutation::{ Mut, MutInt, int_to_mut, read_int }
            (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                let c = int_to_mut { n = 7 }
                read_int { n = c }
            })
        """, because = "Mut"))
    }

    // ── the Embeddable obligation reaches through comptime ────────────────

    @Test
    @Timeout(120)
    fun `a non-embeddable result is rejected, naming the impl to write`() {
        assertTrue(rejects(
            """(perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                { x } -> x + 1
            })""",
            because = "Embeddable",
        ), "function result")
    }

    @Test
    @Timeout(120)
    fun `a newtype result is rejected until its author opts in`() {
        assertTrue(rejects("""
            newtype Meters = Int
            (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                Meters.(5)
            })
        """, because = "Embeddable"))

        // ...and accepted once the impl exists.
        assertEquals(5L, run("""
            use std::abilities::embed::{ Embeddable }
            newtype Meters = Int
            impl Embeddable for Meters where { }
            match (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                Meters.(5)
            }) is
                Meters.(v) -> v
        """))
    }
}
