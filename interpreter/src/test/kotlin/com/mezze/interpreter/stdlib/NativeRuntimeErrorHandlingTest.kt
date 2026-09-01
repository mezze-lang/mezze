package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * LOCK-IN regression tests for genuine-runtime native errors [S8.1c/#384].
 *
 * #382 relabeled the ~214 typechecker-unreachable value-shape guards as
 * NativeTypeMismatchException. #384 audited the ~33 remaining raw
 * `throw new RuntimeException(...)` sites that are GENUINE runtime
 * conditions and classified each as user-reachable / internal-invariant /
 * intentionally-fatal.
 *
 * Some user-reachable conditions whose stdlib wrapper returns a BARE value
 * (Atom validator, ...) cannot become a Mezze `Err`/`Option` without a
 * stdlib signature change. They were relabeled as `MezzeGuestException` — a
 * clean, guest-visible fatal that renders like div-by-zero, NOT a raw Java
 * stack trace / silent corruption.
 *
 * [S8.1-A/#388] NonEmptyList get/from_list/from_seq took the signature-change
 * route instead: they now return Option and the partial case is the
 * recoverable None value, not a fatal (see the migrated tests below).
 *
 * These tests pin that behaviour: each feeds a deliberately-invalid input
 * to such a native and asserts the program fails with a recognizable
 * guest-error message (surfaced as a PolyglotException, a RuntimeException).
 * If a native ever regresses to producing a garbage value instead of the
 * labeled guest error, the message assertion fails loudly.
 */
class NativeRuntimeErrorHandlingTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun expectGuestError(src: String): String {
        val ex = assertThrows(RuntimeException::class.java) {
            MezzeTestHelper.run(src)
        }
        // Walk the cause chain so the assertion is robust to Polyglot
        // wrapping — the guest message must appear somewhere.
        val messages = generateSequence<Throwable>(ex) { it.cause }
            .mapNotNull { it.message }
            .toList()
        return messages.joinToString(" | ")
    }

    // ==================== NonEmptyList: from_list on empty ====================
    //
    // [S8.1-A/#388] `from_list`/`from_seq`/`get` no longer guest-fatal on the
    // partial case — they return Option (None). These formerly asserted a
    // clean guest error; migrated to assert the recoverable None value.

    @Test
    fun `nel from_list on an empty list returns None, not a fatal`() {
        val code = """
            use std::collections::nonemptylist::{ from_list }
            use std::collections::list
            let empty = list::empty {}
            match from_list { xs = empty } is
              Some { val } -> False
              None -> True
        """.trimIndent()
        assertEquals(true, MezzeTestHelper.run(code))
    }

    // ==================== NonEmptyList: get out of bounds ====================

    @Test
    fun `nel get with an out-of-range index returns None, not a fatal`() {
        val code = """
            use std::collections::nonemptylist::{ singleton, get }
            let one = singleton { x = 42 }
            match get { xs = one, index = 999 } is
              Some { val } -> False
              None -> True
        """.trimIndent()
        assertEquals(true, MezzeTestHelper.run(code))
    }

    // ==================== Arithmetic: div / mod by zero ====================

    @Test
    fun `integer divide by zero fails with a clean Division by zero guest error`() {
        val code = """
            let z = 0
            10 / z
        """.trimIndent()
        val msg = expectGuestError(code)
        assertTrue(
            msg.contains("Division by zero", ignoreCase = true) || msg.contains("by zero", ignoreCase = true),
            "Expected a divide-by-zero guest error, got: $msg"
        )
    }

    @Test
    fun `integer modulo by zero fails with a clean guest error`() {
        val code = """
            let z = 0
            10 % z
        """.trimIndent()
        val msg = expectGuestError(code)
        assertTrue(
            msg.contains("zero", ignoreCase = true),
            "Expected a modulo-by-zero guest error, got: $msg"
        )
    }
}
