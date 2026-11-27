package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A `ByteBuffer` chains.
 *
 * It is a BUILDER — every write returns the buffer — and it was the one
 * collection type in the stdlib with no inherent impl at all, so none of that
 * was reachable with a dot and building had to be written inside out:
 *
 *     write_byte { buf = write_byte { buf = write_str { buf = b, s = "hi" }, b = 10 }, b = 13 }
 *
 * `size` and `is_empty` are deliberately NOT in the inherent impl: they come
 * from `Sized`, and declaring them inherently is REFUSED outright — "the
 * inherent method takes precedence, so this impl's 'size' would never run" —
 * which is a good error and the reason this impl is five methods and not seven.
 */
class ByteBufferChainTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    /** Three writes in a row, which is the shape the impl exists for. */
    @Test
    fun `writes chain and keep their order`() {
        assertEquals("hi\r\n", run(
            """
            use std::collections::buffer
            use std::collections::bytes
            match buffer::empty {}
              .write_str { s = "hi" }
              .write_byte { b = 13 }
              .write_byte { b = 10 }
              .to_bytes {}
              .to_str {} is
              Ok { val } -> val
              Err { ... } -> "decode failed"
            """
        ).toString())
    }

    /** The order must be the written order, not reversed: "ab" not "ba". */
    @Test
    fun `two string writes are not reversed`() {
        assertEquals("ab", run(
            """
            use std::collections::buffer
            use std::collections::bytes
            match buffer::empty {}.write_str { s = "a" }.write_str { s = "b" }.to_bytes {}.to_str {} is
              Ok { val } -> val
              Err { ... } -> "decode failed"
            """
        ).toString())
    }

    /** `size` arrives through `Sized`, not the inherent impl. */
    @Test
    fun `size comes through the Sized ability`() {
        assertEquals(3L, run(
            """
            use std::collections::buffer
            use std::abilities::sized::{ Sized }
            buffer::empty {}.write_str { s = "abc" }.size {}
            """
        ))
    }

    /** `clear` returns the buffer too, so it chains like the writes. */
    @Test
    fun `clear chains and empties`() {
        assertEquals("z", run(
            """
            use std::collections::buffer
            use std::collections::bytes
            match buffer::empty {}
              .write_str { s = "discarded" }
              .clear {}
              .write_str { s = "z" }
              .to_bytes {}
              .to_str {} is
              Ok { val } -> val
              Err { ... } -> "decode failed"
            """
        ).toString())
    }

    /** Writing Bytes, not just Str. */
    @Test
    fun `write takes bytes`() {
        assertEquals(3L, run(
            """
            use std::collections::buffer
            use std::collections::bytes
            use std::abilities::sized::{ Sized }
            buffer::empty {}.write { bytes = bytes::from_seq { arr = [1, 2, 3] } }.size {}
            """
        ))
    }
}
