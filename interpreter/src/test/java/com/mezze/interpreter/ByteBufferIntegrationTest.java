package com.mezze.interpreter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ByteBuffer module operations.
 * Uses V2 unboxed values directly.
 */
class ByteBufferIntegrationTest {
    // ========================================
    // Construction Tests
    // ========================================

    @Test
    void bufferEmptyCreatesEmptyBuffer() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            buffer::is_empty { buf = buffer::empty {} }
            """);
        assertEquals(true, result);
    }

    @Test
    void bufferNewCreatesBufferWithCapacity() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            buffer::is_empty { buf = buffer::new { capacity = 1024 } }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Writing Tests
    // ========================================

    @Test
    void bufferWriteStringAddsContent() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
            buffer::size { buf = buf2 }
            """);
        assertEquals(5L, result);
    }

    @Test
    void bufferWriteByteAddsSingleByte() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_byte { buf = buf, b = 65 }
            buffer::size { buf = buf2 }
            """);
        assertEquals(1L, result);
    }

    @Test
    void bufferWriteAddsBytes() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let data = bytes::from_string { s = "Hello" }
            let buf2 = buffer::write { buf = buf, bytes = data }
            buffer::size { buf = buf2 }
            """);
        assertEquals(5L, result);
    }

    @Test
    void bufferChainedWritesAccumulate() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
            let buf3 = buffer::write_byte { buf = buf2, b = 32 }
            let buf4 = buffer::write_string { buf = buf3, s = "World" }
            buffer::size { buf = buf4 }
            """);
        assertEquals(11L, result); // "Hello" + space + "World"
    }

    // ========================================
    // Conversion Tests
    // ========================================

    @Test
    void bufferToBytesConvertsToImmutableBytes() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
            let data = buffer::to_bytes { buf = buf2 }
            bytes::size { b = data }
            """);
        assertEquals(5L, result);
    }

    @Test
    void bufferToBytesContentMatchesWrittenData() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hi" }
            let data = buffer::to_bytes { buf = buf2 }
            bytes::to_hex { b = data }
            """);
        assertEquals("4869", result); // "Hi" in hex
    }

    // ========================================
    // Query Tests
    // ========================================

    @Test
    void bufferSizeReturns0ForEmptyBuffer() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            buffer::size { buf = buffer::empty {} }
            """);
        assertEquals(0L, result);
    }

    @Test
    void bufferIsEmptyReturnsTrueForEmptyBuffer() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            buffer::is_empty { buf = buffer::empty {} }
            """);
        assertEquals(true, result);
    }

    @Test
    void bufferIsEmptyReturnsFalseForNonEmptyBuffer() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "X" }
            buffer::is_empty { buf = buf2 }
            """);
        assertEquals(false, result);
    }

    // ========================================
    // Clear Tests
    // ========================================

    @Test
    void bufferClearResetsBuffer() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello World" }
            let buf3 = buffer::clear { buf = buf2 }
            buffer::size { buf = buf3 }
            """);
        assertEquals(0L, result);
    }

    @Test
    void bufferClearMakesBufferEmpty() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
            let buf3 = buffer::clear { buf = buf2 }
            buffer::is_empty { buf = buf3 }
            """);
        assertEquals(true, result);
    }

    // ========================================
    // Buffer Reuse Tests
    // ========================================

    @Test
    void bufferCanBeWrittenToAfterClear() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "First" }
            let buf3 = buffer::clear { buf = buf2 }
            let buf4 = buffer::write_string { buf = buf3, s = "Second" }
            buffer::size { buf = buf4 }
            """);
        assertEquals(6L, result); // "Second" = 6 chars
    }

    @Test
    void bufferToBytesAllowsContinuedWriting() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Part1" }
            let data1 = buffer::to_bytes { buf = buf2 }
            let buf3 = buffer::write_string { buf = buf2, s = "Part2" }
            buffer::size { buf = buf3 }
            """);
        // Buffer has "Part1Part2" = 10 chars
        assertEquals(10L, result);
    }

    // ========================================
    // Large Buffer Tests
    // ========================================

    @Test
    void bufferHandlesLargeContent() {
        Object result = MezzeTestHelper.run("""
            use std::collections::buffer
            let buf = buffer::empty {}
            let s = "0123456789"
            let buf1 = buffer::write_string { buf = buf, s = s }
            let buf2 = buffer::write_string { buf = buf1, s = s }
            let buf3 = buffer::write_string { buf = buf2, s = s }
            let buf4 = buffer::write_string { buf = buf3, s = s }
            let buf5 = buffer::write_string { buf = buf4, s = s }
            buffer::size { buf = buf5 }
            """);
        assertEquals(50L, result); // 5 x 10 chars
    }
}
