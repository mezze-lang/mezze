package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * NOTHING BEYOND THE POSITION IS OBSERVABLE — the invariant that lets `clear`
 * be O(1).
 *
 * `bufferClear` used to call `Arrays.fill(buf.getData(), (byte) 0)` over the
 * WHOLE backing array before resetting the position. Measured on
 * `bench/cases/buffersurface` with a 2048-byte capacity that cost 237 643 ps,
 * against ~12 000 for a `write_byte` and 6 900 for a `size` — twenty times a
 * write, and scaling with the CAPACITY rather than with anything written.
 *
 * The fill was unobservable through the nine operations
 * `std::collections::buffer` exposes, which is why it could go: `size` and
 * `is_empty` read the position, `to_bytes` copies only the prefix below it, and
 * the writes append at it. These tests pin that, so the invariant is asserted
 * rather than assumed — none of them FAILS without the fix, because the fix
 * changes no reachable behaviour. They fail if someone later makes a reader
 * reach past the position, which is the mistake the fill was hiding.
 *
 * The cases deliberately write a LONG value first and a SHORTER one after the
 * clear, so any surviving tail would show up as extra bytes. With the fill in
 * place those bytes read as zeros; without it they are stale; and under the
 * invariant neither is reachable.
 */
class BufferClearInvariantTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private val prelude = """
        use std::collections::buffer
        use std::collections::bytes
        use std::collections::seq
    """

    /** After a clear the size is zero, whatever had been written. */
    @Test
    fun `clear resets the size`() {
        val out = eval(
            """
            $prelude
            let main = {} -> do
              let b = buffer::write_str { buf = buffer::new { capacity = 256 }, s = "hello world" }
              let c = buffer::clear { buf = b }
              buffer::size { buf = c }
            main {}
            """,
        )
        assertEquals(0L, out)
    }

    @Test
    fun `clear makes the buffer empty`() {
        val out = eval(
            """
            $prelude
            let main = {} -> do
              let b = buffer::write_str { buf = buffer::new { capacity = 256 }, s = "hello world" }
              buffer::is_empty { buf = buffer::clear { buf = b } }
            main {}
            """,
        )
        assertEquals(true, out)
    }

    /** And `to_bytes` of a cleared buffer is EMPTY, not a run of zeros. */
    @Test
    fun `to_bytes after clear is empty`() {
        val out = eval(
            """
            $prelude
            let main = {} -> do
              let b = buffer::write_str { buf = buffer::new { capacity = 256 }, s = "hello world" }
              bytes::size { b = buffer::to_bytes { buf = buffer::clear { buf = b } } }
            main {}
            """,
        )
        assertEquals(0L, out)
    }

    /**
     * THE CASE THAT MATTERS. A long value, a clear, then a SHORTER one — and
     * `to_bytes` must hand back exactly the short one. Anything surviving from
     * the first write would appear as a longer result, whether it survived as
     * zeros or as stale bytes.
     */
    @Test
    fun `writing after clear yields only the new bytes`() {
        val out = eval(
            """
            $prelude
            let main = {} -> do
              let big = buffer::write_str { buf = buffer::new { capacity = 256 },
                                            s = "a much longer original value" }
              let reused = buffer::write_str { buf = buffer::clear { buf = big }, s = "hi" }
              bytes::size { b = buffer::to_bytes { buf = reused } }
            main {}
            """,
        )
        assertEquals(2L, out)
    }

    /** The same, reading the bytes themselves rather than only the length. */
    @Test
    fun `the bytes after clear are exactly what was rewritten`() {
        val out = eval(
            """
            $prelude
            let main = {} -> do
              let big = buffer::write_str { buf = buffer::new { capacity = 256 },
                                            s = "a much longer original value" }
              let reused = buffer::write_byte { buf = buffer::clear { buf = big }, b = 65 }
              bytes::to_seq { b = buffer::to_bytes { buf = reused } }
            main {}
            """,
        )
        assertEquals(listOf(65L), (out as Iterable<*>).toList())
    }

    /**
     * Repeated clear-and-reuse, which is how a builder is actually used in a
     * loop and the shape the O(capacity) fill punished most.
     */
    @Test
    fun `clearing and reusing repeatedly stays correct`() {
        val out = eval(
            """
            $prelude
            let fill = { i, buf, acc } -> if i <= 0 then acc
              else do
                let one = buffer::write_byte { buf = buffer::clear { buf = buf }, b = i % 256 }
                fill { i = i - 1, buf = buf, acc = acc + buffer::size { buf = one } }
            let main = {} -> do
              let b = buffer::new { capacity = 512 }
              fill { i = 50, buf = b, acc = 0 }
            main {}
            """,
        )
        // Every iteration clears and writes exactly one byte, so every size is 1.
        assertEquals(50L, out)
    }

    /** A clear on an already-empty buffer is still empty, not an error. */
    @Test
    fun `clearing an empty buffer is a no-op`() {
        val out = eval(
            """
            $prelude
            let main = {} -> buffer::size { buf = buffer::clear { buf = buffer::empty {} } }
            main {}
            """,
        )
        assertEquals(0L, out)
    }
}
