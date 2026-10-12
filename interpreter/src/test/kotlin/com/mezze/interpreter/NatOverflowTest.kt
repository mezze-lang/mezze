package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `Nat` arithmetic that leaves range FAILS, the way `Int`'s does (S-6).
 *
 * `Nat` is an erased newtype over `Int`, so it is a 64-bit signed long at runtime.
 * `Int`'s primitives were guarded long ago; the `Nat` twins were not, so
 * `max_nat + 1n` WRAPPED to a negative long and the program carried on holding a
 * "natural number" below zero — `Nat.(-3591848752957120200)`. That breaks the one
 * promise the type makes. `/ 0n` and `% 0n` leaked a host `ArithmeticException`
 * instead of the guest division-by-zero error `Int` gives.
 *
 * Found by `mez sample` on `{ n: Nat } -> n * 2n`: Nat's generator draws large
 * values on purpose.
 */
class NatOverflowTest {

    private val max = "9223372036854775807n"

    private fun failure(source: String): String =
        runCatching { MezzeTestHelper.run(source) }.exceptionOrNull()?.message
            ?: "<returned a value instead of failing>"

    private fun assertFails(label: String, source: String, vararg expected: String) {
        val message = failure(source)
        assertTrue(
            expected.all { it in message },
            "$label: expected a guest error containing ${expected.toList()}, got: $message",
        )
        assertTrue(
            "ArithmeticException" !in message,
            "$label: a host exception leaked instead of the guest error: $message",
        )
    }

    @Test
    fun `addition past the maximum overflows`() =
        assertFails("max + 1", "$max + 1n", "Addition", "overflowed Nat")

    @Test
    fun `multiplication past the maximum overflows`() =
        assertFails("max * 2", "$max * 2n", "Multiplication", "overflowed Nat")

    @Test
    fun `pow past the maximum overflows`() =
        assertFails("2^63", "use std::math::core\nstd::math::core::pow_nat { base = 2n, exp = 63n }", "overflowed Nat")

    /**
     * The largest power that FITS must not fail. `pow` squared the base once more
     * after the last exponent bit, so 2^62 overflowed computing an unused 2^64.
     */
    @Test
    fun `the largest fitting power succeeds`() =
        org.junit.jupiter.api.Assertions.assertEquals(
            4611686018427387904L,
            MezzeTestHelper.run("use std::math::core\nuse std::nat\nstd::nat::to_int { n = std::math::core::pow_nat { base = 2n, exp = 62n } }"),
        )

    /** Through a function, so the operands are runtime values — the shape sample found. */
    @Test
    fun `overflow through a function`() =
        assertFails(
            "double",
            "let double: { n: Nat } -> Nat = { n } -> n * 2n\ndouble { n = $max }",
            "Multiplication", "overflowed Nat",
        )

    @Test
    fun `division by zero is the guest error`() =
        assertFails("1 / 0", "let z: { n: Nat } -> Nat = { n } -> 1n / n\nz { n = 0n }", "Division")

    @Test
    fun `modulo by zero is the guest error`() =
        assertFails("1 % 0", "let z: { n: Nat } -> Nat = { n } -> 1n % n\nz { n = 0n }", "Modulo")
}
