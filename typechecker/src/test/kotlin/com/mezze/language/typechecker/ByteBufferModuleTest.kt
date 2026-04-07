package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull
import kotlin.test.assertFails

/**
 * Tests for ByteBuffer module functions.
 * Verifies that importing and using buffer utility functions works correctly
 * and that type checking catches type errors.
 */
class ByteBufferModuleTest {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        TestUtils.setupProjectRoot()
    }

    private fun parseAndTypeCheck(source: String): com.mezze.language.ast.TypeCheckResult {
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    // ========================================
    // Construction Tests
    // ========================================

    @Test
    fun `test buffer empty function`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test buffer new function with capacity`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::new { capacity = 1024 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    // ========================================
    // Writing Tests
    // ========================================

    @Test
    fun `test buffer write_string function`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test buffer write_byte function`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_byte { buf = buf, b = 65 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test buffer write function with bytes`() {
        val source = """
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let data = bytes::from_string { s = "Hello" }
            let buf2 = buffer::write { buf = buf, bytes = data }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    // ========================================
    // Query Tests
    // ========================================

    @Test
    fun `test buffer size function returns Int`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let sz: Int = buffer::size { buf = buf }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test buffer is_empty function returns Bool`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let empty: Bool = buffer::is_empty { buf = buf }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    // ========================================
    // Conversion Tests
    // ========================================

    @Test
    fun `test buffer to_bytes function returns Bytes`() {
        val source = """
            use std::collections::buffer
            use std::collections::bytes
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
            let data = buffer::to_bytes { buf = buf2 }
            let len = bytes::length { b = data }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    // ========================================
    // Mutation Tests
    // ========================================

    @Test
    fun `test buffer clear function`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
            let buf3 = buffer::clear { buf = buf2 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    // ========================================
    // Chaining Tests
    // ========================================

    @Test
    fun `test buffer chained writes`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = "Hello" }
            let buf3 = buffer::write_byte { buf = buf2, b = 32 }
            let buf4 = buffer::write_string { buf = buf3, s = "World" }
            let result = buffer::to_bytes { buf = buf4 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    // ========================================
    // Type Error Tests
    // ========================================

    @Test
    fun `test buffer write_string rejects non-string`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_string { buf = buf, s = 123 }
        """.trimIndent()

        assertFails {
            parseAndTypeCheck(source)
        }
    }

    @Test
    fun `test buffer write_byte rejects non-int`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::empty {}
            let buf2 = buffer::write_byte { buf = buf, b = "not an int" }
        """.trimIndent()

        assertFails {
            parseAndTypeCheck(source)
        }
    }

    @Test
    fun `test buffer size rejects non-buffer`() {
        val source = """
            use std::collections::buffer
            let sz = buffer::size { buf = "not a buffer" }
        """.trimIndent()

        assertFails {
            parseAndTypeCheck(source)
        }
    }

    @Test
    fun `test buffer new rejects non-int capacity`() {
        val source = """
            use std::collections::buffer
            let buf = buffer::new { capacity = "big" }
        """.trimIndent()

        assertFails {
            parseAndTypeCheck(source)
        }
    }
}
