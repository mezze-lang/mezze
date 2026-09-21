package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The hint on a string-interpolation type error must not send the reader
 * somewhere worse than the error it is explaining.
 *
 * It used to offer `.to_str {}` first. `to_str` is an inherent impl on the
 * SCALARS only, so following it lands on a second error for a record — and on a
 * USER VARIANT it crashes the compiler with
 * `[no-unresolved-dispatch-var]` (pre-existing; measured to reproduce with this
 * session's method-routing change reverted, so it is not that change).
 * `to_dbg` comes from Inspect, derived for every non-newtype, so it is available
 * on whatever was interpolated. The hint leads with it, and so does the
 * one-click fix's offer order.
 */
class InterpolationHintTest {

    @BeforeEach fun setUp() { ModuleResolver.clearCache(); TypeChecker.clearCache() }

    private fun hintsFor(src: String): List<String> {
        val r = TypeChecker().checkParsedModule(
            MezzeParser().parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), null, true,
        )
        val failure = r as? TypeChecker.TypeCheckResult.Failure
            ?: fail("expected the interpolation to be rejected, got: $r")
        return failure.errors.flatMap { it.hints }
    }

    @Test
    fun `the interpolation hint leads with to_dbg`() {
        val hints = hintsFor(
            """
            let n: Int = 5
            let s: Str = "v = $(n)"
            """,
        )
        val hint = hints.find { it.contains("to_dbg") }
            ?: fail("expected a hint naming to_dbg, got: $hints")
        assertTrue(
            hint.indexOf("to_dbg") < hint.indexOf("to_str"),
            "to_dbg must come first — it is the one available on every type; got: $hint",
        )
    }

    @Test
    fun `the hint says to_str is the scalar case`() {
        val hints = hintsFor(
            """
            type C = | Red, Green |
            let c: C = Red
            let s: Str = "v = $(c)"
            """,
        )
        assertTrue(
            hints.any { it.contains("to_dbg") && it.contains("scalar") },
            "on a user variant the hint must not present to_str as equally good; got: $hints",
        )
    }
}
