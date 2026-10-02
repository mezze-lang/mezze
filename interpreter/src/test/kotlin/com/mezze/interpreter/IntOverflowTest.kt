package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `Int` arithmetic that leaves 64-bit range FAILS, and fails like `/ 0` does.
 *
 * S-6. `Int` is a 64-bit signed long, so a result outside that range has no
 * representable `Int`. The previous answer widened to a Double, which made a
 * function declared `-> Int` return one — unsound rather than merely imprecise. The
 * Double then flowed through comparisons unnoticed and blew up only where something
 * cast it back, so the reported failure was
 * `ClassCastException: Double cannot be cast to Long` raised by the VALUE RENDERER,
 * nowhere near the arithmetic that caused it.
 *
 * Found by `mez sample` on a two-line function, because `Int`'s generator has the
 * boundaries in its special table at 10% each.
 *
 * ## What the tests here are actually for
 *
 * Not "overflow throws" alone — a wrapping implementation would also pass a test
 * that loose. Each case pins one of the three things that were wrong:
 *
 *  - the result is not a widened Double (the unsoundness),
 *  - the error is a GUEST error naming the operation, not a host exception leaking a
 *    JVM type name at the user,
 *  - every ROUTE agrees. `a + b` reaches the primitive by several paths — the inline
 *    node, the scalar-ABI native op, the typed handler, the record-ABI entry — and
 *    before this they disagreed: the inline node widened while
 *    `PrimitiveNatives.intAdd` silently wrapped. So what `+` meant depended on a
 *    compilation decision the author cannot see. That is the invariant
 *    `PrimitiveNatives.intDivide` states in its own comment, which division honoured
 *    and overflow did not.
 */
class IntOverflowTest {

    private val max = "9223372036854775807"
    private val min = "0 - 9223372036854775807 - 1"

    private fun failure(source: String): String =
        runCatching { MezzeTestHelper.run(source) }.exceptionOrNull()?.message
            ?: "<returned a value instead of failing>"

    private fun assertOverflows(label: String, source: String, operation: String) {
        val message = failure(source)
        assertTrue(
            "overflowed Int" in message && operation in message,
            "$label: expected a `$operation overflowed Int` guest error, got: $message",
        )
        // The specific regression: a widened Double meeting a `(Long)` cast later.
        assertTrue(
            "ClassCastException" !in message,
            "$label: the Double-widening path is back — S-6 regression: $message",
        )
    }

    @Test
    fun `addition past the maximum overflows`() =
        assertOverflows("max + 1", "$max + 1", "Addition")

    @Test
    fun `multiplication past the maximum overflows`() =
        assertOverflows("max * 2", "$max * 2", "Multiplication")

    @Test
    fun `subtraction past the minimum overflows`() =
        assertOverflows("min - 1", "($min) - 1", "Subtraction")

    /** The one unary case: the minimum has no positive counterpart. */
    @Test
    fun `negating the minimum overflows`() =
        assertOverflows("negate min", "0 - ($min)", "Subtraction")

    /**
     * Through a FUNCTION, so the operands are runtime values rather than literals a
     * constant-folder might reach first. This is the shape `mez sample` produced.
     */
    @Test
    fun `overflow through a function call overflows too`() {
        assertOverflows(
            "a function's own arithmetic",
            """
            let total: { quantity: Int, unit_price: Int } -> Int = { quantity, unit_price } ->
              quantity * unit_price

            total { quantity = $max, unit_price = 3 }
            """.trimIndent(),
            "Multiplication",
        )
    }

    /**
     * The message names the OPERANDS. An overflow you cannot see the inputs of says
     * almost nothing about which computation went wrong, which was the other half of
     * what made the old failure useless.
     */
    @Test
    fun `the error names the operands`() {
        val message = failure("$max + 1")
        assertTrue(max in message, "the left operand is not in the message: $message")
    }

    /**
     * In-range arithmetic is untouched and EXACT. Asserting values, not just
     * "returned": a wrapping or widening implementation would also return something.
     */
    @Test
    fun `arithmetic inside the range is exact`() {
        assertEquals(12L, MezzeTestHelper.run("3 * 4"))
        assertEquals(7L, MezzeTestHelper.run("3 + 4"))
        assertEquals(-1L, MezzeTestHelper.run("3 - 4"))
        // Right up to the boundary, which must NOT trip the guard.
        assertEquals(9223372036854775807L, MezzeTestHelper.run("$max + 0"))
        assertEquals(9223372036854775806L, MezzeTestHelper.run("$max - 1"))
    }

    /**
     * Division by zero still fails its own way. Included because the two are the same
     * KIND of situation — an operation with no answer in the type — and the point of
     * this change is that they now fail alike; if one regressed to a host exception
     * the other probably would too.
     */
    @Test
    fun `division by zero still fails as a guest error`() {
        val message = failure("1 / 0")
        assertTrue("by zero" in message, "expected a divide-by-zero guest error: $message")
        assertTrue("ClassCastException" !in message, "host failure leaked: $message")
    }
}
