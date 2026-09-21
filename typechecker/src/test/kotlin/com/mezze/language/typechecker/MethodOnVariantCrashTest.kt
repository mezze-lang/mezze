package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * A method no impl provides for the receiver's type is a DIAGNOSTIC, never a
 * compiler crash.
 *
 * `(Red).to_str {}` on a user variant used to throw
 * `[no-unresolved-dispatch-var] Inherent dispatch of 'to_str' … has an
 * UNRESOLVED receiver var`. The receiver is concrete and no `to_str` candidate
 * targets it, so the answer was known; the ladder's last rung dispatched over
 * ALL candidates anyway and emitted a node whose receiver never resolved. A
 * RECORD receiver already got the clean error from the deferred path — this is
 * the same receiver situation reaching a different rung.
 */
class MethodOnVariantCrashTest {

    @BeforeEach fun setUp() { ModuleResolver.clearCache(); TypeChecker.clearCache() }

    private fun outcome(src: String): String =
        try {
            when (
                val r = TypeChecker().checkParsedModule(
                    MezzeParser().parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), null, true,
                )
            ) {
                is TypeChecker.TypeCheckResult.Failure -> "REJECTED: " + r.errors.joinToString { it.message }
                else -> "OK"
            }
        } catch (e: TypeCheckError) {
            "REJECTED: ${e.message}"
        } catch (e: Throwable) {
            "THREW ${e.javaClass.simpleName}: ${e.message}"
        }

    /**
     * OPEN. The crash is real and PRE-EXISTING — it reproduces byte-identically
     * with this session's method-routing change temporarily reverted.
     *
     * TWO FIXES MEASURED AND REVERTED, so do not re-try either:
     *
     *  - "receiver IS concrete and no candidate targets it → defer": inert. The
     *    crash does not come through that rung, so `candidateNames` is empty for
     *    `(Red)` — `getReceiverConcreteType` does not give a usable type here.
     *  - "receiver type not usable and >1 candidate → defer instead of
     *    dispatching over all of them": does NOT fix this AND breaks
     *    `InherentMethodDispatchRegressionTest.multi-candidate get_or chained on
     *    inherent Result result with match-bound receiver`, which relies on the
     *    all-candidates dispatch being pinned later by unification.
     *
     * So the emitting rung is somewhere else in the ladder and wants locating
     * before anything is changed. The route users took into it is closed (the
     * interpolation hint no longer offers `.to_str {}` first).
     */
    @org.junit.jupiter.api.Disabled("OPEN: pre-existing crash — see KDoc for the two fixes already ruled out")
    @Test
    fun `to_str on a user variant is rejected, not a crash`() {
        val r = outcome(
            """
            type C = | Red, Green |
            let s: Str = (Red).to_str {}
            """,
        )
        assertTrue(r.startsWith("REJECTED"), "expected a diagnostic, got: $r")
        assertTrue(r.contains("to_str"), "the error must name the method; got: $r")
    }

    @Test
    fun `to_str on a record is rejected, as it already was`() {
        val r = outcome("let s: Str = ({ a = 1 }).to_str {}")
        assertTrue(r.startsWith("REJECTED"), "expected a diagnostic, got: $r")
    }

    @Test
    fun `to_dbg on the same variant still works`() {
        assertTrue(
            outcome(
                """
                type C = | Red, Green |
                let s: Str = (Red).to_dbg {}
                """,
            ) == "OK",
            "Inspect is derived for a non-newtype, so to_dbg must type-check",
        )
    }
}
