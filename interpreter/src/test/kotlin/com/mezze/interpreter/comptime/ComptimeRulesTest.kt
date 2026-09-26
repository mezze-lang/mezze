package com.mezze.interpreter.comptime

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The rules `comptime`'s signature cannot express. Each asserts the message the
 * author actually sees, so a regression that keeps the rejection but loses the
 * explanation still fails.
 *
 * Every one of these is decided while type checking, which is the property that
 * lets `mez check` and the editor stay honest: the only failures left for build
 * time are a body that crashes or never terminates.
 */
class ComptimeRulesTest {

    private val use =
        "use std::comptime::{ Comptime, ComptimeEval }\nuse std::effects::noop::{ noop }"

    private fun rejectedWith(src: String, vararg mustSay: String) {
        val message = try {
            MezzeTestHelper.run("$use\n${src.trimIndent()}")
            fail<Nothing>("expected a rejection, but this compiled:\n$src")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            e.message.orEmpty()
        }
        for (fragment in mustSay) {
            assertTrue(message.contains(fragment), "expected message to mention '$fragment', got:\n$message")
        }
    }

    // ── rule 1: applied, never referenced ─────────────────────────────────

    @Test
    @Timeout(120)
    fun `a bare reference to comptime is rejected`() {
        // As an effect OPERATION rather than a function, this is no longer a
        // bespoke rule: `comptime` is simply not a name in scope, and ordinary
        // name resolution rejects it — with a better message than the rule had,
        // because it points at the spelling that does work.
        rejectedWith("""let f = comptime""", "Comptime.comptime")
    }

    @Test
    @Timeout(120)
    fun `comptime stored in a record is rejected`() {
        rejectedWith("""let r = { go = comptime }""", "Comptime.comptime")
    }

    @Test
    @Timeout(120)
    fun `partial application of comptime is rejected`() {
        rejectedWith("""let p = (perform ComptimeEval in Comptime.comptime {})""", "applied directly")
    }

    // ── rule 2: the eval thunk must be closed ─────────────────────────────

    @Test
    @Timeout(120)
    fun `capturing an enclosing parameter is rejected, and the message names it`() {
        rejectedWith(
            """
            let scale = { n } -> (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                n * 2
            })
            scale { n = 3 }
            """,
            "cannot capture", "`n`", "run-time value",
        )
    }

    @Test
    @Timeout(120)
    fun `capturing an enclosing local is rejected`() {
        rejectedWith(
            """
            let go = {} -> do
                let k = 10
                (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                k + 1
            })
            go {}
            """,
            "cannot capture", "`k`",
        )
    }

    /** The admitted half: a top-level function is reached by hash, not captured,
     *  so calling one from inside the thunk must stay legal. */
    @Test
    @Timeout(120)
    fun `calling a top-level function from the thunk is NOT a capture`() {
        val r = MezzeTestHelper.run(
            """
            $use
            let double = { n } -> n * 2
            (perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                double { n = 21 }
            })
            """.trimIndent()
        )
        assertTrue(r == 42L, "expected 42, got $r")
    }

    // Rule 3 was removed: an unresolved result type means the call cannot be
    // FOLDED, not that it is wrong. See ComptimeRules.

    // ── rule 5: the name is reserved ──────────────────────────────────────

    @Test
    @Timeout(120)
    fun `a user binding may be called comptime`() {
        // It was a reserved name while `comptime` was a top-level function that
        // the compiler recognised at call sites. The operation is namespaced by
        // its effect — you reach it as `Comptime.comptime` — so a binding of that
        // name shadows nothing and the reservation is no longer needed.
        assertDoesNotThrow {
            MezzeTestHelper.typeCheck("$use\nlet comptime = { eval } -> 1\ncomptime { eval = 2 }")
        }
    }
}
