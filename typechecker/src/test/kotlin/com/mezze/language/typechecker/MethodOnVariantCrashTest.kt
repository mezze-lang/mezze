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
     * FIXED, and the fix was an ORDERING, not a new diagnostic.
     *
     * The T0206 this asserts was already being produced, by
     * `undispatchableInherentMethodErrors`, from the SAME receiver read the
     * invariant uses — so the two always agreed about which dispatches are
     * unresolved. But `checkNoUnresolvedDispatchVar` ran FIRST and threw, and
     * the diagnostic sitting directly below it never got to speak. The
     * invariant exists to catch a SILENT wrong answer (the "defaults to Bytes"
     * mis-dispatch class); a program being rejected with a precise error is not
     * silent, so it now skips whatever the error pass reports.
     *
     * TWO EARLIER ATTEMPTS, both measured and reverted — they aimed at the
     * method-resolution ladder, which was never the problem:
     *
     *  - "receiver IS concrete and no candidate targets it → defer": inert.
     *  - "receiver type not usable and >1 candidate → defer": did not fix it AND
     *    broke `InherentMethodDispatchRegressionTest.multi-candidate get_or
     *    chained on inherent Result result with match-bound receiver`.
     *
     * A probe over every return in `tryGenerateMethodCall` settled it: the
     * DEFERRED rung was already firing. The constraint was right all along.
     */
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
