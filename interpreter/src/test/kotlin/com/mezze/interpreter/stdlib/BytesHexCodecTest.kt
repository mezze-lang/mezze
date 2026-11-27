package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * `bytes::to_hex` and `bytes::from_hex` after both stopped working one byte at
 * a time.
 *
 * WHY THEY CHANGED. `to_hex` called `String.format("%02x", …)` PER BYTE, which
 * parses the format string, allocates a `Formatter` and boxes the int every
 * time: 49 944 ps a byte over a 1 024-byte buffer on
 * `bench/cases/bytesurface`, against `to_base64` — the method directly beneath
 * it, a strictly more complex encoding — at 220. `from_hex` was the same shape,
 * allocating a two-character `substring` and running `Integer.parseInt` for
 * every byte of output.
 *
 * WHAT CAN GO WRONG with a nibble table, and so what is tested here:
 *
 *  - **The high and low nibble can be SWAPPED**, which is invisible for any
 *    byte whose nibbles are equal — `0x00`, `0xff`, `0x77` all encode the same
 *    either way. The cases below are deliberately asymmetric (`0x01`, `0xf0`,
 *    `0xa5`) so a swap shows.
 *  - **The zero PAD can be dropped**, which only shows for bytes below 0x10:
 *    `0x05` must be `"05"` and not `"5"`, or the whole string shifts and every
 *    later byte decodes wrong. A length check catches it where a spot check
 *    would not.
 *  - **Sign extension.** `data[i]` is a signed byte, so anything at or above
 *    0x80 is negative and needs the `& 0xFF`; without it the table index goes
 *    negative. Every case here includes a high byte.
 *  - **Case on input.** `parseInt(s, 16)` accepted both, so the table must too,
 *    or `from_hex` silently stops accepting uppercase hex.
 *
 * COVERAGE IS EVIDENCED BY A SENTINEL, and it corrected one of these tests.
 * Swapping the two nibbles in the encoder fails 2 of the 6 — but NOT the
 * round trip over every byte value, and the reason is worth keeping: swapping
 * nibbles is a BIJECTION on 0..255, so the sum over the whole range is
 * invariant under it. A property that ranges over every input can still be
 * blind to a permutation of those inputs; the asymmetric spot check above is
 * what actually catches it.
 *
 * ONE BEHAVIOUR NARROWED ON PURPOSE. `parseInt` accepts a SIGN, so `"-1"` used
 * to decode to `0xFF` and `"+f"` to `0x0F` — neither is hex. Both are `None`
 * now, which is what every other malformed input already gave. Pinned below so
 * the change is a decision rather than a surprise.
 */
class BytesHexCodecTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val prelude = listOf(
        "use std::collections::bytes",
        "use std::option",
        "use std::str",
    )

    private fun run(vararg parts: List<String>): Any =
        MezzeTestHelper.run(parts.flatMap { it }.joinToString("\n"))

    @Test
    fun `encoding is asymmetric per nibble, zero padded, and sign safe`() {
        // 0x01 0xf0 0xa5 0x05 0x80 0xff — every one distinguishes a swap, a
        // dropped pad, or a missing & 0xFF.
        assertEquals("01f0a50580ff", run(
            prelude,
            listOf("bytes::to_hex { b = bytes::from_seq { arr = [1, 240, 165, 5, 128, 255] } }"),
        ))
    }

    @Test
    fun `every byte value encodes to two characters`() {
        // 256 bytes in, 512 characters out. A dropped pad anywhere makes this
        // short, which no single spot check would reveal.
        assertEquals(512L, run(
            prelude,
            listOf(
                "let fill = { i, acc } -> if i < 0 then acc",
                "  else fill { i = i - 1, acc = seq::push { xs = acc, x = i } }",
                "use std::collections::seq",
                "let b = bytes::from_seq { arr = fill { i = 255, acc = [] } }",
                "str::size { s = bytes::to_hex { b = b } }",
            ),
        ))
    }

    @Test
    fun `a round trip over every byte value is the identity`() {
        // Encode 0..255, decode, and compare the sum of the bytes:
        // 255*256/2 = 32640. A swap, a pad or a sign error changes it.
        assertEquals(32640L, run(
            prelude,
            listOf(
                "use std::collections::seq",
                "let fill = { i, acc } -> if i < 0 then acc",
                "  else fill { i = i - 1, acc = seq::push { xs = acc, x = i } }",
                "let b = bytes::from_seq { arr = fill { i = 255, acc = [] } }",
                "let back = option::get_or { opt = bytes::from_hex { s = bytes::to_hex { b = b } },",
                "                            def = bytes::empty {} }",
                "bytes::fold { b = back, init = 0, f = { acc, byte } -> acc + byte }",
            ),
        ))
    }

    @Test
    fun `decoding accepts both cases, as parseInt did`() {
        assertEquals(1L, run(
            prelude,
            listOf(
                "let lower = option::get_or { opt = bytes::from_hex { s = \"deadbeef\" }, def = bytes::empty {} }",
                "let upper = option::get_or { opt = bytes::from_hex { s = \"DEADBEEF\" }, def = bytes::empty {} }",
                "let mixed = option::get_or { opt = bytes::from_hex { s = \"DeAdBeEf\" }, def = bytes::empty {} }",
                "let a = bytes::to_hex { b = lower }",
                "let c = bytes::to_hex { b = upper }",
                "let d = bytes::to_hex { b = mixed }",
                "if a == \"deadbeef\" && c == \"deadbeef\" && d == \"deadbeef\" then 1 else 0",
            ),
        ))
    }

    @Test
    fun `malformed input is None`() {
        assertEquals(1L, run(
            prelude,
            listOf(
                "let bad = { s } -> match bytes::from_hex { s = s } is",
                "  Some { ... } -> 0",
                "  None -> 1",
                "# odd length, a non-hex character, and the SIGNS parseInt used to accept",
                "let odd = bad { s = \"abc\" }",
                "let nonhex = bad { s = \"zz\" }",
                "let minus = bad { s = \"-1\" }",
                "let plus = bad { s = \"+f\" }",
                "let space = bad { s = \" 1\" }",
                "if odd == 1 && nonhex == 1 && minus == 1 && plus == 1 && space == 1 then 1 else 0",
            ),
        ))
    }

    @Test
    fun `the empty buffer round trips`() {
        assertEquals(1L, run(
            prelude,
            listOf(
                "let e = bytes::to_hex { b = bytes::empty {} }",
                "let back = option::get_or { opt = bytes::from_hex { s = \"\" }, def = bytes::from_seq { arr = [1] } }",
                "if e == \"\" && bytes::size { b = back } == 0 then 1 else 0",
            ),
        ))
    }
}
