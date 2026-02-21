package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for std/collections/buffer module.
 */
class BufferModuleTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return result
    }

    // ===== Construction =====

    @Test
    fun `test buffer empty`() {
        val result = eval("""
            use std::collections::buffer
            let buf = buffer::empty {}
            buffer::is_empty { buf = buf }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test buffer new with capacity`() {
        val result = eval("""
            use std::collections::buffer
            let buf = buffer::new { capacity = 100 }
            buffer::is_empty { buf = buf }
        """)
        assertEquals(MezzeBool(true), result)
    }

    // ===== Writing =====

    @Test
    fun `test buffer write`() {
        val result = eval("""
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let b = bytes::from_string { s = "Hello" }
            let buf2 = buffer::write { buf = buf, bytes = b }
            buffer::size { buf = buf2 }
        """)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `test buffer write multiple`() {
        val result = eval("""
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let b1 = bytes::from_string { s = "Hello" }
            let b2 = bytes::from_string { s = "World" }
            let buf2 = buffer::write { buf = buf, bytes = b1 }
            let buf3 = buffer::write { buf = buf2, bytes = b2 }
            buffer::size { buf = buf3 }
        """)
        assertEquals(MezzeInt(10), result)
    }

    @Test
    fun `test buffer write_byte`() {
        val result = eval("""
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_byte { buf = buf, b = 65 }
            buffer::size { buf = buf2 }
        """)
        assertEquals(MezzeInt(1), result)
    }

    @Test
    fun `test buffer write_byte multiple`() {
        val result = eval("""
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_byte { buf = buf, b = 72 }
            let buf3 = buffer::write_byte { buf = buf2, b = 105 }
            buffer::size { buf = buf3 }
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test buffer write_string`() {
        val result = eval("""
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
            buffer::size { buf = buf2 }
        """)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `test buffer write_string multiple`() {
        val result = eval("""
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
            let buf3 = buffer::write_string { buf = buf2, s = " " }
            let buf4 = buffer::write_string { buf = buf3, s = "World" }
            buffer::size { buf = buf4 }
        """)
        assertEquals(MezzeInt(11), result)
    }

    // ===== Conversion =====

    @Test
    fun `test buffer to_bytes`() {
        val result = eval("""
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Test" }
            let b = buffer::to_bytes { buf = buf2 }
            bytes::length { b = b }
        """)
        assertEquals(MezzeInt(4), result)
    }

    @Test
    fun `test buffer to_bytes content`() {
        val result = eval("""
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hi" }
            let b = buffer::to_bytes { buf = buf2 }
            match bytes::to_string { b = b } is
                Ok { val } -> val
                Error { error } -> "failed"
        """)
        assertEquals(MezzeString("Hi"), result)
    }

    @Test
    fun `test buffer to_bytes does not consume buffer`() {
        val result = eval("""
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Test" }
            let b1 = buffer::to_bytes { buf = buf2 }
            let buf3 = buffer::write_string { buf = buf2, s = "More" }
            buffer::size { buf = buf3 }
        """)
        assertEquals(MezzeInt(8), result)
    }

    // ===== Query =====

    @Test
    fun `test buffer size empty`() {
        val result = eval("""
            use std::collections::buffer
            let buf = buffer::empty {}
            buffer::size { buf = buf }
        """)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `test buffer is_empty - true`() {
        val result = eval("""
            use std::collections::buffer
            let buf = buffer::empty {}
            buffer::is_empty { buf = buf }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test buffer is_empty - false`() {
        val result = eval("""
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_byte { buf = buf, b = 1 }
            buffer::is_empty { buf = buf2 }
        """)
        assertEquals(MezzeBool(false), result)
    }

    // ===== Mutation =====

    @Test
    fun `test buffer clear`() {
        val result = eval("""
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
            let buf3 = buffer::clear { buf = buf2 }
            buffer::size { buf = buf3 }
        """)
        assertEquals(MezzeInt(0), result)
    }

    @Test
    fun `test buffer clear makes empty`() {
        val result = eval("""
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
            let buf3 = buffer::clear { buf = buf2 }
            buffer::is_empty { buf = buf3 }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test buffer reuse after clear`() {
        val result = eval("""
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "First" }
            let buf3 = buffer::clear { buf = buf2 }
            let buf4 = buffer::write_string { buf = buf3, s = "Second" }
            let b = buffer::to_bytes { buf = buf4 }
            match bytes::to_string { b = b } is
                Ok { val } -> val
                Error { error } -> "failed"
        """)
        assertEquals(MezzeString("Second"), result)
    }

    // ===== Mixed Operations =====

    @Test
    fun `test buffer mixed write operations`() {
        val result = eval("""
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "A" }
            let buf3 = buffer::write_byte { buf = buf2, b = 66 }
            let b = bytes::from_string { s = "C" }
            let buf4 = buffer::write { buf = buf3, bytes = b }
            buffer::size { buf = buf4 }
        """)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `test buffer mixed write operations content`() {
        val result = eval("""
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "A" }
            let buf3 = buffer::write_byte { buf = buf2, b = 66 }
            let b = bytes::from_string { s = "C" }
            let buf4 = buffer::write { buf = buf3, bytes = b }
            let result = buffer::to_bytes { buf = buf4 }
            match bytes::to_string { b = result } is
                Ok { val } -> val
                Error { error } -> "failed"
        """)
        assertEquals(MezzeString("ABC"), result)
    }
}
