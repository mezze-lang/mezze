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
 * The user-reachable conditions whose stdlib wrapper returns a BARE value
 * (NonEmptyList get/from_list, Atom validator, ...) cannot become a Mezze
 * `Err` without a stdlib signature change (reported as a follow-up). They
 * were instead relabeled as `MezzeGuestException` — a clean, guest-visible
 * fatal that renders like div-by-zero, NOT a raw Java stack trace / silent
 * corruption.
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

    @Test
    fun `nel from_list on an empty list fails with a clean guest error, not silent corruption`() {
        val code = """
            use std::collections::nonemptylist::{ from_list }
            use std::collections::list
            let empty = list::empty {}
            from_list { xs = empty }
        """.trimIndent()
        val msg = expectGuestError(code)
        assertTrue(
            msg.contains("NonEmptyList", ignoreCase = true) && msg.contains("empty", ignoreCase = true),
            "Expected a NonEmptyList empty-list guest error, got: $msg"
        )
    }

    // ==================== NonEmptyList: get out of bounds ====================

    @Test
    fun `nel get with an out-of-range index fails with a clean guest error`() {
        val code = """
            use std::collections::nonemptylist::{ singleton, get }
            let one = singleton { x = 42 }
            get { xs = one, index = 999 }
        """.trimIndent()
        val msg = expectGuestError(code)
        assertTrue(
            msg.contains("out of bounds", ignoreCase = true),
            "Expected a NonEmptyList index-out-of-bounds guest error, got: $msg"
        )
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
