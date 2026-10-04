package com.mezze.interpreter

import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Arithmetic that cannot produce an answer must fail as a MEZZE error.
 *
 * `/` and `%` by zero are the same situation, so a program must not be able
 * to tell them apart by how they fail. They used to differ: divide raised a
 * [com.mezze.interpreter.values.MezzeGuestException], modulo threw a bare JVM
 * `ArithmeticException`. Truffle only treats the former as a guest-language
 * exception, so the second one reached the user as a host/internal failure —
 * and, per MezzeGuestException's own doc, the debugger's exception-breakpoint
 * filter never fires for it.
 *
 * The division guard also lived in the inline node only. `a / b` reaches the
 * primitive by four routes (the inline DirectDivideNode, the scalar-ABI
 * NativeOp, the typed LLL handler, and the record-ABI NativeDispatch entry),
 * and only the first was guarded — so the SAME expression failed differently
 * depending on which route the compiler picked for it. The guard now sits on
 * the shared primitive, which every route goes through.
 */
class ArithmeticErrorTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun failureOf(source: String): Throwable =
        assertThrows(Throwable::class.java) { MezzeTestHelper.run(source.trimIndent()) }

    private fun assertGuestFailure(source: String, expected: String) {
        val t = failureOf(source)
        val message = t.message ?: ""
        assertTrue(
            message.contains(expected),
            "expected a `$expected` error, got ${t::class.java.name}: $message",
        )
        // The point of the test: Truffle must see this as a GUEST error.
        // A bare ArithmeticException is a host error and reaches the user
        // as an internal failure with a JVM stack trace.
        val polyglot = t as? PolyglotException
        if (polyglot != null) {
            assertTrue(
                polyglot.isGuestException,
                "a Mezze arithmetic error must be a guest exception, not a host one: $message",
            )
            assertTrue(
                !polyglot.isInternalError,
                "a Mezze arithmetic error must not surface as an internal error: $message",
            )
        }
    }

    @Test
    fun `integer divide by zero is a guest error`() {
        assertGuestFailure("10 / 0", "Division by zero")
    }

    @Test
    fun `integer modulo by zero is a guest error, like divide`() {
        assertGuestFailure("10 % 0", "Modulo by zero")
    }

    /**
     * Reached through a function rather than as a constant-folded literal, so
     * the operands are not known at compile time and the call cannot be
     * collapsed to the inline node at its use site.
     */
    @Test
    fun `divide by zero through a call is a guest error too`() {
        assertGuestFailure(
            """
            let div = { a: Int, b: Int } -> a / b
            div { a = 10, b = 0 }
            """,
            "Division by zero",
        )
    }

    @Test
    fun `modulo by zero through a call is a guest error too`() {
        assertGuestFailure(
            """
            let rem = { a: Int, b: Int } -> a % b
            rem { a = 10, b = 0 }
            """,
            "Modulo by zero",
        )
    }

    /** Fractions had the same defect, in two copies (FracNatives, FractionHelper). */
    @Test
    fun `dividing by a zero fraction is a guest error`() {
        assertGuestFailure(
            """
            let x = 3//4
            let zero = 0//1
            x / zero
            """,
            "Fraction division by zero",
        )
    }

    /**
     * S-9: CONSTRUCTING a fraction over zero — the same mistake as dividing by one,
     * and the last route still leaking a host exception.
     *
     * `frac::make { numerator = 1, denominator = 0 }` surfaced as
     * `java.lang.ArithmeticException: Division by zero in fraction` — a JVM class
     * name — while `1 / 0` two tests above says `Division by zero`. The guard sat a
     * few lines above `FracNatives.divide`, which had always raised correctly, so
     * the two routes to "a fraction over zero" failed in two different ways.
     *
     * `frac::make` stays `-> Frac` rather than becoming `-> Option Frac`: a zero
     * denominator IS division by zero, and this language raises for that (`1 / 0`
     * above) instead of returning an option. `frac::from_float` is an `Option`
     * because NaN is a value a caller can legitimately hold and test for — the
     * difference between the two is deliberate.
     */
    @Test
    fun `constructing a fraction over zero is a guest error`() {
        assertGuestFailure(
            """
            use std::frac
            frac::make { numerator = 1, denominator = 0 }
            """,
            "A fraction cannot have a zero denominator",
        )
    }
}
