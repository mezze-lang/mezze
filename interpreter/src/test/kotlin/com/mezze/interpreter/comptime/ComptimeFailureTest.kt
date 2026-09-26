package com.mezze.interpreter.comptime

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * What happens when a comptime block goes wrong.
 *
 * The distinction that matters, and it follows from Mezze's failure model
 * rather than from anything comptime invents: a `Result` is DATA. Returning
 * `Err { … }` embeds the error value and compiles fine. Only a raise — or an
 * explicit `compile_error` — stops the build.
 */
class ComptimeFailureTest {

    private val use =
        "use std::comptime::{ Comptime, ComptimeEval, compile_error }\nuse std::effects::noop::{ noop }"

    private fun failureMessage(src: String): String =
        try {
            MezzeTestHelper.run("$use\n${src.trimIndent()}")
            fail<Nothing>("expected the build to fail, but this compiled:\n$src")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Throwable) {
            e.message.orEmpty()
        }

    @Test
    @Timeout(120)
    fun `compile_error stops the build with its own message`() {
        val m = failureMessage("""(perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                compile_error { msg = "schema is stale" }
            })""")
        assertTrue(m.contains("schema is stale"), "message not surfaced, got:\n$m")
    }

    @Test
    @Timeout(120)
    fun `a crash inside the body stops the build`() {
        val m = failureMessage("""(perform ComptimeEval in Comptime.comptime { eval = {} -> do
                noop {}
                1 / 0
            })""")
        assertTrue(
            m.contains("comptime", ignoreCase = true) || m.contains("zero", ignoreCase = true),
            "expected a comptime/divide-by-zero failure, got:\n$m",
        )
    }

    /** The other half, and the one that is easy to get wrong: an `Err` is a
     *  value. It embeds, it does not fail. */
    @Test
    @Timeout(120)
    fun `returning Err is data, not a failure`() {
        val r = MezzeTestHelper.run(
            """
            $use
            # In a function, not the trailing bare expression: a variant
            # constructor there is typed only by context, and the top-level-
            # expression path this harness allows -- real mez rejects it with
            # V0710 -- never solves it.
            let outcome = {} -> Comptime.comptime { eval = {} -> do
                noop {}
                Err { error = "nope" }
            }
            match (perform ComptimeEval in outcome {}) is
                Ok { val } -> "ok"
                Err { error } -> error
            """.trimIndent()
        )
        assertEquals("nope", r)
    }

    @Test
    @Timeout(120)
    fun `compile_error outside comptime is an ordinary raise`() {
        val m = failureMessage("""compile_error { msg = "boom" }""")
        assertTrue(m.contains("boom"), "expected the message, got:\n$m")
    }
}
