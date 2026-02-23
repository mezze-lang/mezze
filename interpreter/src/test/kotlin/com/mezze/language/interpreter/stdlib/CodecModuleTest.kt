package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Paths

/**
 * Tests for std/codec module.
 * Tests codec error types, configurations, and helper functions.
 */
class CodecModuleTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setup() {
        TestUtils.setupProjectRoot()
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val typeCheckResult = typeChecker.typeCheck(program, projectRoot)
        val (result, _) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances, projectRoot)
        return result
    }

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
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
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
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
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
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
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
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
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
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
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
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
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
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
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
        assertTrue(result is MezzeString)
        assertEquals("Cannot encode nested record", (result as MezzeString).value)
    }

    @Test
    fun `test EncodeError path`() {
        val result = eval("""
            use std::codec::core::{EncodeError}
            use std::collections::array

            let error: EncodeError = {
                path = ["user", "name"],
                message = "Encoding failed"
            }
            array::length {xs = error.path}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(2L, (result as MezzeInt).value)
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
        assertTrue(result is MezzeString)
        assertEquals("Int", (result as MezzeString).value)
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
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    // === Helper Functions Tests ===

    @Test
    fun `test empty_path is empty array`() {
        val result = eval("""
            use std::codec::core::{empty_path}
            use std::collections::array

            array::is_empty {xs = empty_path}
        """)
        assertTrue(result is MezzeBool)
        assertTrue((result as MezzeBool).value)
    }

    @Test
    fun `test append_path adds field`() {
        val result = eval("""
            use std::codec::core::{empty_path, append_path}
            use std::collections::array

            let path = append_path {path = empty_path, field = "user"}
            array::length {xs = path}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(1L, (result as MezzeInt).value)
    }

    @Test
    fun `test append_path chains correctly`() {
        val result = eval("""
            use std::codec::core::{empty_path, append_path}
            use std::collections::array

            let path1 = append_path {path = empty_path, field = "user"}
            let path2 = append_path {path = path1, field = "address"}
            array::length {xs = path2}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(2L, (result as MezzeInt).value)
    }

    @Test
    fun `test append_index adds index as string`() {
        val result = eval("""
            use std::codec::core::{empty_path, append_index}
            use std::collections::array

            let path = append_index {path = empty_path, index = 0}
            array::length {xs = path}
        """)
        assertTrue(result is MezzeInt)
        assertEquals(1L, (result as MezzeInt).value)
    }

    @Test
    fun `test format_path with empty path`() {
        val result = eval("""
            use std::codec::core::{empty_path, format_path}

            format_path {path = empty_path}
        """)
        assertTrue(result is MezzeString)
        assertEquals("<root>", (result as MezzeString).value)
    }

    @Test
    fun `test format_path with single field`() {
        val result = eval("""
            use std::codec::core::{empty_path, append_path, format_path}

            let path = append_path {path = empty_path, field = "user"}
            format_path {path = path}
        """)
        assertTrue(result is MezzeString)
        assertEquals("user", (result as MezzeString).value)
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
        assertTrue(result is MezzeString)
        assertEquals("user.address.city", (result as MezzeString).value)
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
        assertTrue(result is MezzeString)
        assertEquals("Test error", (result as MezzeString).value)
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
        assertTrue(result is MezzeString)
        assertEquals("Int", (result as MezzeString).value)
    }

    // === Entry Type Tests ===

    @Test
    fun `test Entry type construction`() {
        val result = eval("""
            use std::codec::core::{Entry}

            let entry: Entry String Int = {key = "count", value = 42}
            entry.value
        """)
        assertTrue(result is MezzeInt)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `test Entry with complex types`() {
        val result = eval("""
            use std::codec::core::{Entry}

            let entry: Entry Int {name: String} = {key = 1, value = {name = "Alice"}}
            entry.value.name
        """)
        assertTrue(result is MezzeString)
        assertEquals("Alice", (result as MezzeString).value)
    }
}
