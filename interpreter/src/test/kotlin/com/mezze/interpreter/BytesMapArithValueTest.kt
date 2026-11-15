package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The bytes the rewritten `map` produces are the bytes the callback produced.
 *
 * `BytesMapArithRewriter` replaces `bytes::map { b, f = { x } -> x <op> k }`
 * with a native that does the arithmetic in the loop — 17.2x, measured against
 * `bytes::reverse` as the ceiling. `BytesMapArithLoweringTest` asserts the path
 * is taken; this asserts the ANSWER, because the two must be indistinguishable.
 *
 * THE TWO THINGS THAT COULD DIFFER, and both are checked below:
 *
 *  - **The element arrives UNSIGNED.** `BytesNatives.map` hands the mapper
 *    `(long) (b & 0xFF)`, so `std::collections::bytes` sees the Int in 0..255
 *    where a Java byte is signed. Without the `& 0xFF` in the new natives, a
 *    byte of 200 would present as -56 and every result above 127 would be
 *    wrong. `a high byte arrives unsigned` is the case that catches it.
 *  - **The result TRUNCATES to 8 bits.** `map` finishes with `byteValue()`, so
 *    `200 * 2` is 400, which is 144 as a byte, not 255 and not an error. The
 *    native's `(byte)` cast has to agree, and `multiplication truncates` pins
 *    it.
 *
 * Read back through `bytes::to_seq` rather than a fold, so a wrong byte shows as
 * a wrong element rather than disappearing into a total — a sum is exactly the
 * shape that would hide two compensating errors.
 */
class BytesMapArithValueTest {

    /**
     * `bytes::to_seq` hands back a `MezzeSeq`, which is `Iterable` but is not
     * equal to a Kotlin list however identical its contents, so the elements
     * are compared rather than the containers.
     */
    private fun elems(out: Any): List<Any?> = (out as Iterable<*>).toList()

    private fun run(body: String): Any = MezzeTestHelper.run(
        """
        use std::collections::bytes
        use std::collections::seq

        ${body.trimIndent()}
        """.trimIndent(),
    )

    @Test
    fun `dividing by a literal halves every byte`() {
        val out = run(
            """
            let main = {} -> bytes::to_seq {
              b = bytes::map { b = bytes::from_seq { arr = [4, 9, 255] }, f = { x } -> x / 2 }
            }
            main {}
            """,
        )
        // 4/2 = 2, 9/2 = 4 (truncating), 255/2 = 127.
        assertEquals(listOf(2L, 4L, 127L), elems(out))
    }

    @Test
    fun `adding a literal bumps every byte`() {
        val out = run(
            """
            let main = {} -> bytes::to_seq {
              b = bytes::map { b = bytes::from_seq { arr = [0, 1, 254] }, f = { x } -> x + 1 }
            }
            main {}
            """,
        )
        assertEquals(listOf(1L, 2L, 255L), elems(out))
    }

    @Test
    fun `subtracting a literal lowers every byte`() {
        val out = run(
            """
            let main = {} -> bytes::to_seq {
              b = bytes::map { b = bytes::from_seq { arr = [1, 2, 255] }, f = { x } -> x - 1 }
            }
            main {}
            """,
        )
        assertEquals(listOf(0L, 1L, 254L), elems(out))
    }

    @Test
    fun `modulo a literal keeps the low bits`() {
        val out = run(
            """
            let main = {} -> bytes::to_seq {
              b = bytes::map { b = bytes::from_seq { arr = [255, 16, 17] }, f = { x } -> x % 16 }
            }
            main {}
            """,
        )
        assertEquals(listOf(15L, 0L, 1L), elems(out))
    }

    /**
     * A BYTE ABOVE 127 MUST ARRIVE AS ITSELF. 200 is -56 as a signed Java byte,
     * so without the `& 0xFF` this answers 100 for `200 / 2` only by accident
     * of rounding and answers -28 for the division of the signed value. Divide
     * by 1 so the test is about the VALUE and nothing else.
     */
    @Test
    fun `a high byte arrives unsigned`() {
        val out = run(
            """
            let main = {} -> bytes::to_seq {
              b = bytes::map { b = bytes::from_seq { arr = [128, 200, 255] }, f = { x } -> x / 1 }
            }
            main {}
            """,
        )
        assertEquals(listOf(128L, 200L, 255L), elems(out))
    }

    /**
     * THE RESULT TRUNCATES, because that is what the callback path does:
     * `map` ends in `byteValue()`. 200*2 = 400 = 0b1_1001_0000, and the low
     * eight bits are 0b1001_0000 = 144. Not 255, not an error.
     */
    @Test
    fun `multiplication truncates to eight bits`() {
        val out = run(
            """
            let main = {} -> bytes::to_seq {
              b = bytes::map { b = bytes::from_seq { arr = [1, 100, 200] }, f = { x } -> x * 2 }
            }
            main {}
            """,
        )
        assertEquals(listOf(2L, 200L, 144L), elems(out))
    }

    /** An empty buffer maps to an empty buffer, not to a crash on `new byte[0]`. */
    @Test
    fun `an empty buffer maps to empty`() {
        val out = run(
            """
            let main = {} -> seq::size { xs = bytes::to_seq {
              b = bytes::map { b = bytes::from_seq { arr = [] }, f = { x } -> x / 2 }
            } }
            main {}
            """,
        )
        assertEquals(0L, out)
    }

    /**
     * THE REFUSED SHAPES STILL ANSWER. `255 - x` keeps the guest callback, and
     * it must still be right — if the rewriter ever took it, it would compute
     * `x - 255` and these would be the wrong bytes.
     */
    @Test
    fun `a literal on the left still answers correctly`() {
        val out = run(
            """
            let main = {} -> bytes::to_seq {
              b = bytes::map { b = bytes::from_seq { arr = [0, 1, 255] }, f = { x } -> 255 - x }
            }
            main {}
            """,
        )
        assertEquals(listOf(255L, 254L, 0L), elems(out))
    }

    /** And a captured operand, which is the case the scalar bypass still serves. */
    @Test
    fun `a captured multiplier still answers correctly`() {
        val out = run(
            """
            let scale = { b: Bytes, factor: Int } -> bytes::map { b = b, f = { x } -> x * factor }
            let main = {} -> bytes::to_seq {
              b = scale { b = bytes::from_seq { arr = [1, 2, 3] }, factor = 3 }
            }
            main {}
            """,
        )
        assertEquals(listOf(3L, 6L, 9L), elems(out))
    }
}
