package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for std/collections/bytes module.
 */
class BytesModuleTest {

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
    fun `test bytes empty`() {
        val result = eval("""
            use std::collections::bytes
            bytes::is_empty { b = bytes::empty {} }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test bytes from_string`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            bytes::length { b = b }
        """)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `test bytes from_array`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_array { arr = [72, 105] }
            bytes::length { b = b }
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test bytes from_hex`() {
        val result = eval("""
            use std::collections::bytes
            match bytes::from_hex { s = "48656c6c6f" } is
                Some { val } -> bytes::length { b = val }
                None -> 0
        """)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `test bytes from_hex invalid`() {
        val result = eval("""
            use std::collections::bytes
            match bytes::from_hex { s = "ZZZZ" } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test bytes from_base64`() {
        val result = eval("""
            use std::collections::bytes
            match bytes::from_base64 { s = "SGVsbG8=" } is
                Some { val } -> bytes::length { b = val }
                None -> 0
        """)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `test bytes from_base64 invalid`() {
        val result = eval("""
            use std::collections::bytes
            match bytes::from_base64 { s = "!!invalid!!" } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(MezzeBool(true), result)
    }

    // ===== Basic Operations =====

    @Test
    fun `test bytes length`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            bytes::length { b = b }
        """)
        assertEquals(MezzeInt(11), result)
    }

    @Test
    fun `test bytes is_empty - true`() {
        val result = eval("""
            use std::collections::bytes
            bytes::is_empty { b = bytes::empty {} }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test bytes is_empty - false`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "x" }
            bytes::is_empty { b = b }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test bytes get`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            match bytes::get { b = b, i = 0 } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(MezzeInt(72), result) // 'H' = 72
    }

    @Test
    fun `test bytes get out of bounds`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hi" }
            match bytes::get { b = b, i = 10 } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(MezzeBool(true), result)
    }

    // ===== Slicing =====

    @Test
    fun `test bytes slice`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let s = bytes::slice { b = b, start = 0, end = 5 }
            bytes::length { b = s }
        """)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `test bytes take`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let t = bytes::take { b = b, n = 5 }
            bytes::length { b = t }
        """)
        assertEquals(MezzeInt(5), result)
    }

    @Test
    fun `test bytes drop`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let d = bytes::drop { b = b, n = 6 }
            bytes::length { b = d }
        """)
        assertEquals(MezzeInt(5), result)
    }

    // ===== Combining =====

    @Test
    fun `test bytes concat`() {
        val result = eval("""
            use std::collections::bytes
            let b1 = bytes::from_string { s = "Hello" }
            let b2 = bytes::from_string { s = "World" }
            let b3 = bytes::concat { b1 = b1, b2 = b2 }
            bytes::length { b = b3 }
        """)
        assertEquals(MezzeInt(10), result)
    }

    @Test
    fun `test bytes concat_all`() {
        val result = eval("""
            use std::collections::bytes
            let b1 = bytes::from_string { s = "A" }
            let b2 = bytes::from_string { s = "B" }
            let b3 = bytes::from_string { s = "C" }
            let bs = [b1, b2, b3]
            let combined = bytes::concat_all { bs = bs }
            bytes::length { b = combined }
        """)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `test bytes repeat`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hi" }
            let r = bytes::repeat { b = b, n = 3 }
            bytes::length { b = r }
        """)
        assertEquals(MezzeInt(6), result)
    }

    // ===== Searching =====

    @Test
    fun `test bytes index_of - found`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let pattern = bytes::from_string { s = "World" }
            match bytes::index_of { b = b, pattern = pattern } is
                Some { val } -> val
                None -> -1
        """)
        assertEquals(MezzeInt(6), result)
    }

    @Test
    fun `test bytes index_of - not found`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            let pattern = bytes::from_string { s = "xyz" }
            match bytes::index_of { b = b, pattern = pattern } is
                Some { val } -> False
                None -> True
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test bytes contains - true`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let pattern = bytes::from_string { s = "World" }
            bytes::contains { b = b, pattern = pattern }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test bytes contains - false`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            let pattern = bytes::from_string { s = "xyz" }
            bytes::contains { b = b, pattern = pattern }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test bytes starts_with - true`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let prefix = bytes::from_string { s = "Hello" }
            bytes::starts_with { b = b, prefix = prefix }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test bytes starts_with - false`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let prefix = bytes::from_string { s = "World" }
            bytes::starts_with { b = b, prefix = prefix }
        """)
        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test bytes ends_with - true`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let suffix = bytes::from_string { s = "World" }
            bytes::ends_with { b = b, suffix = suffix }
        """)
        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test bytes ends_with - false`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello World" }
            let suffix = bytes::from_string { s = "Hello" }
            bytes::ends_with { b = b, suffix = suffix }
        """)
        assertEquals(MezzeBool(false), result)
    }

    // ===== Transformations =====

    @Test
    fun `test bytes reverse`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_array { arr = [1, 2, 3] }
            let r = bytes::reverse { b = b }
            match bytes::get { b = r, i = 0 } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(MezzeInt(3), result)
    }

    @Test
    fun `test bytes map`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_array { arr = [1, 2, 3] }
            let m = bytes::map { b = b, f = {x} -> x + 1 }
            match bytes::get { b = m, i = 0 } is
                Some { val } -> val
                None -> 0
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test bytes fold`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_array { arr = [1, 2, 3] }
            bytes::fold { b = b, init = 0, f = {acc, byte} -> acc + byte }
        """)
        assertEquals(MezzeInt(6), result)
    }

    // ===== Conversions =====

    @Test
    fun `test bytes to_string`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            match bytes::to_string { b = b } is
                Ok { val } -> val
                Error { error } -> "failed"
        """)
        assertEquals(MezzeString("Hello"), result)
    }

    @Test
    fun `test bytes to_array`() {
        val result = eval("""
            use std::collections::bytes
            use std::collections::array
            let b = bytes::from_string { s = "Hi" }
            let arr = bytes::to_array { b = b }
            array::length { xs = arr }
        """)
        assertEquals(MezzeInt(2), result)
    }

    @Test
    fun `test bytes to_hex`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hi" }
            bytes::to_hex { b = b }
        """)
        assertEquals(MezzeString("4869"), result)
    }

    @Test
    fun `test bytes to_base64`() {
        val result = eval("""
            use std::collections::bytes
            let b = bytes::from_string { s = "Hello" }
            bytes::to_base64 { b = b }
        """)
        assertEquals(MezzeString("SGVsbG8="), result)
    }

    @Test
    fun `test bytes roundtrip hex`() {
        val result = eval("""
            use std::collections::bytes
            let b1 = bytes::from_string { s = "Test" }
            let hex = bytes::to_hex { b = b1 }
            match bytes::from_hex { s = hex } is
                Some { val } ->
                    match bytes::to_string { b = val } is
                        Ok { val } -> val
                        Error { error } -> "failed"
                None -> "failed"
        """)
        assertEquals(MezzeString("Test"), result)
    }

    @Test
    fun `test bytes roundtrip base64`() {
        val result = eval("""
            use std::collections::bytes
            let b1 = bytes::from_string { s = "Test" }
            let b64 = bytes::to_base64 { b = b1 }
            match bytes::from_base64 { s = b64 } is
                Some { val } ->
                    match bytes::to_string { b = val } is
                        Ok { val } -> val
                        Error { error } -> "failed"
                None -> "failed"
        """)
        assertEquals(MezzeString("Test"), result)
    }
}
