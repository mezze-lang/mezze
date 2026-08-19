package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * #217 (latent #215): NonEmptyList / Bytes higher-order functions declared their
 * callbacks PURE (`f: { x } -> 'b`, no `<...>`), so — after #215 tightened
 * unification — an effectful callback was either silently accepted-and-laundered
 * (pre-#215) or wrongly rejected (post-#215). Both are wrong: like `seq::map`,
 * these HOFs should carry a polymorphic effect row on the callback AND the
 * return, so an effectful callback is accepted and its effect PROPAGATES to the
 * caller. Fixed by annotating the callbacks/returns with `<...>`.
 *
 * These tests run against the REAL stdlib (immune to the stale PrecompiledStdlib
 * singleton). The negative cases assert the effect surfaces: a caller declared
 * PURE that passes an effectful callback is rejected (the effect can't be hidden).
 * The positive cases confirm the effectful callback IS accepted when the caller
 * carries the effect, and that a pure callback still works (no regression). The
 * native-fusion runtime path itself is exercised by NonEmptyListModuleTest /
 * BytesModuleTest.
 */
class CollectionHofEffectPropagationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private fun assertEffectSurfaces(source: String, what: String) {
        val exception = assertThrows(PolyglotException::class.java, { eval(source) }, what)
        val msg = exception.message ?: ""
        assertTrue(msg.contains("TypeCheckError"), "expected a TypeCheckError for $what, got: $msg")
        assertTrue(msg.contains("Log"), "the $what rejection must mention the un-annotated effect Log; got: $msg")
    }

    // ── the effect must not be laundered: a pure caller is rejected ──────

    @Test fun `bytes map with an effectful callback cannot hide the effect from a pure caller`() {
        assertEffectSurfaces(
            """
            use std::effects::log
            use std::collections::bytes
            let leaky: { x: Int } -> Int <Log> = { x } -> do
              log::info { msg = "leak" }
              x
            let go: { b: Bytes } -> Bytes = { b } -> bytes::map { b = b, f = leaky }
            """,
            "bytes::map effect laundered into a pure caller",
        )
    }

    @Test fun `bytes fold with an effectful callback cannot hide the effect from a pure caller`() {
        assertEffectSurfaces(
            """
            use std::effects::log
            use std::collections::bytes
            let leaky: { acc: Int, byte: Int } -> Int <Log> = { acc, byte } -> do
              log::info { msg = "leak" }
              acc + byte
            let go: { b: Bytes } -> Int = { b } -> bytes::fold { b = b, init = 0, f = leaky }
            """,
            "bytes::fold effect laundered into a pure caller",
        )
    }

    // ── the effectful callback IS accepted when the caller carries it ────

    @Test fun `bytes map with an effectful callback is accepted when the caller carries the effect`() {
        // The effect flows through bytes::map's `<...>` row into go's declared
        // <Log> — so this type-checks (pre-#217 it laundered; post-tightening it
        // would wrongly reject without the `<...>` annotation). We assert only
        // that it does NOT raise a TypeCheckError; discharging Log to a value
        // needs a handler, which is orthogonal to the propagation being checked.
        assertDoesNotThrow {
            eval(
                """
                use std::effects::log
                use std::collections::bytes
                let leaky: { x: Int } -> Int <Log> = { x } -> do
                  log::info { msg = "b" }
                  x
                let go: { b: Bytes } -> Bytes <Log> = { b } -> bytes::map { b = b, f = leaky }
                42
                """
            )
        }
    }

    // ── a pure callback still works (no regression) ─────────────────────

    @Test fun `bytes map with a pure callback still works`() {
        // Doubling each byte via a pure callback must still type-check and run.
        val result = eval(
            """
            use std::collections::bytes
            let doubled = bytes::map { b = bytes::from_seq { arr = [1, 2] }, f = { x } -> x + x }
            bytes::size { b = doubled }
        """
        )
        assertEquals(2L, result)
    }
}
