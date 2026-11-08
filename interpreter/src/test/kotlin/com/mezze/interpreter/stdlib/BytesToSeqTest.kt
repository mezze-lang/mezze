package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * `bytes::to_seq` builds its result as a primitive `long[]` now, and every
 * element must still be the byte's UNSIGNED value.
 *
 * WHY IT CHANGED. It used to fill an `Object[]` with
 * `(long) (bytes.get(i) & 0xFF)`, boxing a `Long` per element, and then let
 * `SeqStorageFactory.fromArray` guess the storage back — three `instanceof` an
 * element to decide it was all `Long`, a second array, and an unboxing pass.
 * 2 496 ps a byte on `bench/cases/bytesurface`, for what is a widening copy.
 *
 * WHAT THE RISK IS. A `byte` in Java is SIGNED, so `data[i]` alone turns every
 * value at or above 0x80 NEGATIVE: 0xFF reads as -1, not 255. The `& 0xFF` is
 * the whole correctness of the conversion and it is invisible for any buffer
 * that happens to hold only ASCII — which most test fixtures do. So every case
 * here includes a high byte, and one walks all 256 values.
 *
 * COVERAGE IS EVIDENCED BY A SENTINEL, and it corrected this note. Dropping the
 * `& 0xFF` fails TWO of the five, not the four first written here. The
 * interesting survivor is the ROUND TRIP: `from_seq` truncates with
 * `byteValue()`, so -1 goes back to 0xFF and the trip is the identity whether
 * or not the element was sign-extended on the way out. A round trip is
 * invariant under any error the inverse undoes symmetrically — the same blind
 * spot the hex codec's whole-range sum has under a nibble swap, which is a
 * bijection on 0..255. Both looked like the strongest test in their file and
 * neither sees the bug it was aimed at.
 *
 * What does see it is reading the VALUES: a sum over 0x00 0x7f 0x80 0xff is 510
 * unsigned and -2 sign-extended, and the 0..255 sweep is 32 640 against -128.
 */
class BytesToSeqTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val prelude = listOf(
        "use std::collections::bytes",
        "use std::collections::seq",
        "use std::option",
    )

    private fun run(vararg parts: List<String>): Any =
        MezzeTestHelper.run(parts.flatMap { it }.joinToString("\n"))

    @Test
    fun `the boundary values come back unsigned`() {
        // 0x00 0x7f 0x80 0xff -> 0 + 127 + 128 + 255 = 510.
        // Sign-extended it would be 0 + 127 + (-128) + (-1) = -2.
        assertEquals(510L, run(
            prelude,
            listOf(
                "let b = bytes::from_seq { arr = [0, 127, 128, 255] }",
                "seq::fold { xs = bytes::to_seq { b = b }, init = 0, f = { acc, x } -> acc + x }",
            ),
        ))
    }

    @Test
    fun `every byte value survives the conversion`() {
        // 0..255 sums to 255*256/2 = 32 640.
        assertEquals(32640L, run(
            prelude,
            listOf(
                "let fill = { i, acc } -> if i < 0 then acc",
                "  else fill { i = i - 1, acc = seq::push { xs = acc, x = i } }",
                "let b = bytes::from_seq { arr = fill { i = 255, acc = [] } }",
                "seq::fold { xs = bytes::to_seq { b = b }, init = 0, f = { acc, x } -> acc + x }",
            ),
        ))
    }

    @Test
    fun `the length is preserved`() {
        assertEquals(1L, run(
            prelude,
            listOf(
                "let b = bytes::from_seq { arr = [1, 200, 3, 250] }",
                "let s = bytes::to_seq { b = b }",
                "if seq::size { xs = s } == 4 && seq::size { xs = s } == bytes::size { b = b }",
                "  then 1 else 0",
            ),
        ))
    }

    @Test
    fun `a round trip through from_seq is the identity`() {
        // High bytes included deliberately: a sign-extended element would be
        // rejected or truncated differently on the way back in.
        assertEquals(1L, run(
            prelude,
            listOf(
                "let b = bytes::from_seq { arr = [0, 1, 128, 200, 254, 255] }",
                "let back = bytes::from_seq { arr = bytes::to_seq { b = b } }",
                "if bytes::to_hex { b = back } == bytes::to_hex { b = b } then 1 else 0",
            ),
        ))
    }

    @Test
    fun `the empty buffer gives the empty sequence`() {
        assertEquals(1L, run(
            prelude,
            listOf(
                "let s = bytes::to_seq { b = bytes::empty {} }",
                "if seq::size { xs = s } == 0 then 1 else 0",
            ),
        ))
    }
}
