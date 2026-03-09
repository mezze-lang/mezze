package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/codec module.
 * Tests codec error types, configurations, and helper functions.
 */
class CodecModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === FieldTransform Tests ===

    @Test
    fun `test Identity field transform`() {
        val result = eval("""
            use std::codec::core::{FieldTransform}

            let t: FieldTransform = Identity
            match t is
                Identity -> True
                _ -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test SnakeCase field transform`() {
        val result = eval("""
            use std::codec::core::{FieldTransform}

            let t: FieldTransform = SnakeCase
            match t is
                SnakeCase -> True
                _ -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test CamelCase field transform`() {
        val result = eval("""
            use std::codec::core::{FieldTransform}

            let t: FieldTransform = CamelCase
            match t is
                CamelCase -> True
                _ -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test KebabCase field transform`() {
        val result = eval("""
            use std::codec::core::{FieldTransform}

            let t: FieldTransform = KebabCase
            match t is
                KebabCase -> True
                _ -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test ScreamingSnakeCase field transform`() {
        val result = eval("""
            use std::codec::core::{FieldTransform}

            let t: FieldTransform = ScreamingSnakeCase
            match t is
                ScreamingSnakeCase -> True
                _ -> False
        """)
        assertEquals(true, result)
    }

    // === CodecConfig Tests ===

    @Test
    fun `test default_config has Identity transform`() {
        val result = eval("""
            use std::codec::core::{default_config}

            match default_config.field_transform is
                Identity -> True
                _ -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test CodecConfig construction`() {
        val result = eval("""
            use std::codec::core::{CodecConfig, FieldTransform}

            let t: FieldTransform = SnakeCase
            let config: CodecConfig = {field_transform = t}
            match config.field_transform is
                SnakeCase -> True
                _ -> False
        """)
        assertEquals(true, result)
    }

    // === EncodeError Tests ===

    @Test
    fun `test EncodeError construction`() {
        val result = eval("""
            use std::codec::core::{EncodeError}

            let error: EncodeError = {
                path = ["user", "address"],
                message = "Cannot encode nested record"
            }
            error.message
        """)
        assertEquals("Cannot encode nested record", result)
    }

    @Test
    fun `test EncodeError path`() {
        val result = eval("""
            use std::codec::core::{EncodeError}
            use std::collections::seq

            let error: EncodeError = {
                path = ["user", "name"],
                message = "Encoding failed"
            }
            seq::length {xs = error.path}
        """)
        assertEquals(2L, result)
    }

    // === DecodeError Tests ===

    @Test
    fun `test DecodeError construction with found`() {
        val result = eval("""
            use std::codec::core::{DecodeError}

            let error: DecodeError = {
                path = ["data"],
                message = "Type mismatch",
                expected = "Int",
                found = Some {val = "String"}
            }
            error.expected
        """)
        assertEquals("Int", result)
    }

    @Test
    fun `test DecodeError construction without found`() {
        val result = eval("""
            use std::codec::core::{DecodeError}

            let error: DecodeError = {
                path = [],
                message = "Missing field",
                expected = "name",
                found = None
            }
            match error.found is
                Some {val} -> False
                None -> True
        """)
        assertEquals(true, result)
    }

    // === Helper Functions Tests ===

    @Test
    fun `test empty_path is empty array`() {
        val result = eval("""
            use std::codec::core::{empty_path}
            use std::collections::seq

            seq::is_empty {xs = empty_path}
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test append_path adds field`() {
        val result = eval("""
            use std::codec::core::{empty_path, append_path}
            use std::collections::seq

            let path = append_path {path = empty_path, field = "user"}
            seq::length {xs = path}
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test append_path chains correctly`() {
        val result = eval("""
            use std::codec::core::{empty_path, append_path}
            use std::collections::seq

            let path1 = append_path {path = empty_path, field = "user"}
            let path2 = append_path {path = path1, field = "address"}
            seq::length {xs = path2}
        """)
        assertEquals(2L, result)
    }

    @Test
    fun `test append_index adds index as string`() {
        val result = eval("""
            use std::codec::core::{empty_path, append_index}
            use std::collections::seq

            let path = append_index {path = empty_path, index = 0}
            seq::length {xs = path}
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `test format_path with empty path`() {
        val result = eval("""
            use std::codec::core::{empty_path, format_path}

            format_path {path = empty_path}
        """)
        assertEquals("<root>", result)
    }

    @Test
    fun `test format_path with single field`() {
        val result = eval("""
            use std::codec::core::{empty_path, append_path, format_path}

            let path = append_path {path = empty_path, field = "user"}
            format_path {path = path}
        """)
        assertEquals("user", result)
    }

    @Test
    fun `test format_path with multiple fields`() {
        val result = eval("""
            use std::codec::core::{empty_path, append_path, format_path}

            let path1 = append_path {path = empty_path, field = "user"}
            let path2 = append_path {path = path1, field = "address"}
            let path3 = append_path {path = path2, field = "city"}
            format_path {path = path3}
        """)
        assertEquals("user.address.city", result)
    }

    // === Error Constructor Tests ===

    @Test
    fun `test encode_error creates Err variant`() {
        val result = eval("""
            use std::codec::core::{encode_error, empty_path}

            let err = encode_error {path = empty_path, message = "Test error"}
            match err is
                Err {error} -> error.message
                _ -> "not an error"
        """)
        assertEquals("Test error", result)
    }

    @Test
    fun `test decode_error creates Err variant`() {
        val result = eval("""
            use std::codec::core::{decode_error, empty_path}

            let err = decode_error {
                path = empty_path,
                message = "Type mismatch",
                expected = "Int",
                found = Some {val = "String"}
            }
            match err is
                Err {error} -> error.expected
                _ -> "not an error"
        """)
        assertEquals("Int", result)
    }

    // === Entry Type Tests ===

    @Test
    fun `test Entry type construction`() {
        val result = eval("""
            use std::codec::core::{Entry}

            let entry: Entry String Int = {key = "count", value = 42}
            entry.value
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test Entry with complex types`() {
        val result = eval("""
            use std::codec::core::{Entry}

            let entry: Entry Int {name: String} = {key = 1, value = {name = "Alice"}}
            entry.value.name
        """)
        assertEquals("Alice", result)
    }
}
