package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFails

/**
 * Tests for bytes module functions.
 * Verifies that importing and using bytes utility functions works correctly
 * and that type checking catches type errors.
 */
class BytesModuleTest {

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
        val program = parser.parse(source)
        return typeChecker.typeCheck(program)
    }

    // ========================================
    // Construction Tests
    // ========================================

    @Test
    fun `test bytes empty function`() {
        val source = """
            use std::collections::bytes
            let b = bytes::empty {}
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes from_string function`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes from_seq function`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_seq { arr = [72, 101, 108, 108, 111] }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes from_hex function returns Option`() {
        val source = """
            use std::collections::bytes
            let maybeBytes = bytes::from_hex { s = "48656c6c6f" }
            match maybeBytes is
                Some { val } -> val
                None -> bytes::empty {}
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes from_base64 function returns Option`() {
        val source = """
            use std::collections::bytes
            let maybeBytes = bytes::from_base64 { s = "SGVsbG8=" }
            match maybeBytes is
                Some { val } -> val
                None -> bytes::empty {}
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    // ========================================
    // Access Tests
    // ========================================

    @Test
    fun `test bytes length function returns Int`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            let len: Int = bytes::length { b = b }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes is_empty function returns Bool`() {
        val source = """
            use std::collections::bytes
            let b = bytes::empty {}
            let empty: Bool = bytes::is_empty { b = b }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes get function returns Option Int`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            let maybeByte = bytes::get { b = b, i = 0 }
            match maybeByte is
                Some { val } -> val
                None -> 0
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    // ========================================
    // Slicing Tests
    // ========================================

    @Test
    fun `test bytes slice function`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let sliced = bytes::slice { b = b, start = 0, end = 5 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes take function`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            let taken = bytes::take { b = b, n = 3 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes drop function`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            let dropped = bytes::drop { b = b, n = 2 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    // ========================================
    // Combination Tests
    // ========================================

    @Test
    fun `test bytes concat function`() {
        val source = """
            use std::collections::bytes
            let b1 = bytes::from_string { s = "Hello" }
            let b2 = bytes::from_string { s = " World" }
            let combined = bytes::concat { b1 = b1, b2 = b2 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes repeat function`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_string { s = "ab" }
            let repeated = bytes::repeat { b = b, n = 3 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    // ========================================
    // Search Tests
    // ========================================

    @Test
    fun `test bytes contains function returns Bool`() {
        val source = """
            use std::collections::bytes
            let haystack = bytes::from_string { s = "Hello World" }
            let needle = bytes::from_string { s = "World" }
            let found: Bool = bytes::contains { b = haystack, pattern = needle }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes starts_with function returns Bool`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let prefix = bytes::from_string { s = "Hello" }
            let result: Bool = bytes::starts_with { b = b, prefix = prefix }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes ends_with function returns Bool`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let suffix = bytes::from_string { s = "World" }
            let result: Bool = bytes::ends_with { b = b, suffix = suffix }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes index_of function returns Option Int`() {
        val source = """
            use std::collections::bytes
            let haystack = bytes::from_string { s = "Hello World" }
            let needle = bytes::from_string { s = "World" }
            let maybeIndex = bytes::index_of { b = haystack, pattern = needle }
            match maybeIndex is
                Some { val } -> val
                None -> -1
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    // ========================================
    // Transformation Tests
    // ========================================

    @Test
    fun `test bytes reverse function`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_seq { arr = [1, 2, 3] }
            let reversed = bytes::reverse { b = b }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes map function`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_seq { arr = [1, 2, 3] }
            let doubled = bytes::map { b = b, f = { x } -> x * 2 }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes fold function`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_seq { arr = [1, 2, 3, 4] }
            let sum: Int = bytes::fold { b = b, init = 0, f = { acc, byte } -> acc + byte }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    // ========================================
    // Conversion Tests
    // ========================================

    @Test
    fun `test bytes to_hex function returns String`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_string { s = "Hi" }
            let hex: Str = bytes::to_hex { b = b }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes to_base64 function returns String`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            let encoded: Str = bytes::to_base64 { b = b }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes to_str function returns Result`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            let result = bytes::to_str { b = b }
            match result is
                Ok { val } -> val
                Err { error } -> error
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes to_seq function returns seq of Int`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_seq { arr = [65, 66, 67] }
            let arr: [Int] = bytes::to_seq { b = b }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    // ========================================
    // Type Error Tests
    // ========================================

    @Test
    fun `test bytes from_string rejects non-string argument`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_string { s = 123 }
        """.trimIndent()

        assertFails {
            parseAndTypeCheck(source)
        }
    }

    @Test
    fun `test bytes length rejects non-bytes argument`() {
        val source = """
            use std::collections::bytes
            let len = bytes::length { b = "not bytes" }
        """.trimIndent()

        assertFails {
            parseAndTypeCheck(source)
        }
    }

    @Test
    fun `test bytes get rejects non-int index`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            let byte = bytes::get { b = b, i = "zero" }
        """.trimIndent()

        assertFails {
            parseAndTypeCheck(source)
        }
    }

    @Test
    fun `test bytes concat rejects non-bytes arguments`() {
        val source = """
            use std::collections::bytes
            let b1 = bytes::from_string { s = "Hello" }
            let combined = bytes::concat { b1 = b1, b2 = "not bytes" }
        """.trimIndent()

        assertFails {
            parseAndTypeCheck(source)
        }
    }

    @Test
    fun `test bytes map rejects wrong function signature`() {
        val source = """
            use std::collections::bytes
            let b = bytes::from_seq { arr = [1, 2, 3] }
            # Function returns String instead of Int
            let mapped = bytes::map { b = b, f = { x } -> "wrong" }
        """.trimIndent()

        assertFails {
            parseAndTypeCheck(source)
        }
    }

    // ========================================
    // Composition Tests
    // ========================================

    @Test
    fun `test bytes roundtrip hex encoding`() {
        val source = """
            use std::collections::bytes
            let original = bytes::from_string { s = "Test" }
            let hex = bytes::to_hex { b = original }
            let decoded = bytes::from_hex { s = hex }
            match decoded is
                Some { val } -> bytes::length { b = val }
                None -> 0
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }

    @Test
    fun `test bytes chained operations`() {
        val source = """
            use std::collections::bytes
            let b1 = bytes::from_string { s = "Hello" }
            let b2 = bytes::from_string { s = " World" }
            let combined = bytes::concat { b1 = b1, b2 = b2 }
            let taken = bytes::take { b = combined, n = 5 }
            let hex = bytes::to_hex { b = taken }
        """.trimIndent()

        val typed = parseAndTypeCheck(source)
        assertNotNull(typed)
    }
}
