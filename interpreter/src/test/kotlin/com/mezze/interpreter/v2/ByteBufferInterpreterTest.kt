package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Interpreter tests for ByteBuffer module.
 * Tests runtime behavior of buffer operations.
 * Migrated from V1 to V2 with unboxed values.
 */
class ByteBufferInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================
    // Construction Tests
    // ========================================

    @Test
    fun `test buffer empty creates empty buffer`() {
        val source = """
            use std::collections::buffer
            buffer::is_empty { buf = buffer::empty {} }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test buffer new creates buffer with capacity`() {
        val source = """
            use std::collections::buffer
            buffer::is_empty { buf = buffer::new { capacity = 1024 } }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    // ========================================
    // Writing Tests
    // ========================================

    @Test
    fun `test buffer write_string adds content`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
            buffer::size { buf = buf2 }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test buffer write_byte adds single byte`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_byte { buf = buf, b = 65 }
            buffer::size { buf = buf2 }
        """.trimIndent()

        assertEquals(1L, eval(source))
    }

    @Test
    fun `test buffer write adds bytes`() {
        val source = """
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let data = bytes::from_string { s = "Hello" }
            let buf2 = buffer::write { buf = buf, bytes = data }
            buffer::size { buf = buf2 }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test buffer chained writes accumulate`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
            let buf3 = buffer::write_byte { buf = buf2, b = 32 }
            let buf4 = buffer::write_string { buf = buf3, s = "World" }
            buffer::size { buf = buf4 }
        """.trimIndent()

        assertEquals(11L, eval(source)) // "Hello" + space + "World"
    }

    // ========================================
    // Conversion Tests
    // ========================================

    @Test
    fun `test buffer to_bytes converts to immutable bytes`() {
        val source = """
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
            let data = buffer::to_bytes { buf = buf2 }
            bytes::length { b = data }
        """.trimIndent()

        assertEquals(5L, eval(source))
    }

    @Test
    fun `test buffer to_bytes content matches written data`() {
        val source = """
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hi" }
            let data = buffer::to_bytes { buf = buf2 }
            bytes::to_hex { b = data }
        """.trimIndent()

        assertEquals("4869", eval(source)) // "Hi" in hex
    }

    // ========================================
    // Query Tests
    // ========================================

    @Test
    fun `test buffer size returns 0 for empty buffer`() {
        val source = """
            use std::collections::buffer
            buffer::size { buf = buffer::empty {} }
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test buffer is_empty returns true for empty buffer`() {
        val source = """
            use std::collections::buffer
            buffer::is_empty { buf = buffer::empty {} }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    @Test
    fun `test buffer is_empty returns false for non-empty buffer`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "X" }
            buffer::is_empty { buf = buf2 }
        """.trimIndent()

        assertEquals(false, eval(source))
    }

    // ========================================
    // Clear Tests
    // ========================================

    @Test
    fun `test buffer clear resets buffer`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello World" }
            let buf3 = buffer::clear { buf = buf2 }
            buffer::size { buf = buf3 }
        """.trimIndent()

        assertEquals(0L, eval(source))
    }

    @Test
    fun `test buffer clear makes buffer empty`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
            let buf3 = buffer::clear { buf = buf2 }
            buffer::is_empty { buf = buf3 }
        """.trimIndent()

        assertEquals(true, eval(source))
    }

    // ========================================
    // Buffer Reuse Tests
    // ========================================

    @Test
    fun `test buffer can be written to after clear`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "First" }
            let buf3 = buffer::clear { buf = buf2 }
            let buf4 = buffer::write_string { buf = buf3, s = "Second" }
            buffer::size { buf = buf4 }
        """.trimIndent()

        assertEquals(6L, eval(source)) // "Second" = 6 chars
    }

    @Test
    fun `test buffer to_bytes allows continued writing`() {
        val source = """
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Part1" }
            let data1 = buffer::to_bytes { buf = buf2 }
            let buf3 = buffer::write_string { buf = buf2, s = "Part2" }
            buffer::size { buf = buf3 }
        """.trimIndent()

        // Buffer has "Part1Part2" = 10 chars
        assertEquals(10L, eval(source))
    }

    // ========================================
    // Large Buffer Tests
    // ========================================

    @Test
    fun `test buffer handles large content`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let s = "0123456789"
            let buf1 = buffer::write_string { buf = buf, s = s }
            let buf2 = buffer::write_string { buf = buf1, s = s }
            let buf3 = buffer::write_string { buf = buf2, s = s }
            let buf4 = buffer::write_string { buf = buf3, s = s }
            let buf5 = buffer::write_string { buf = buf4, s = s }
            buffer::size { buf = buf5 }
        """.trimIndent()

        assertEquals(50L, eval(source)) // 5 x 10 chars
    }
}
