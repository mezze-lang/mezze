package com.mezze.interpreter.v2.codec

import org.junit.jupiter.api.Disabled
import com.mezze.interpreter.v2.MezzeTestHelper
import com.mezze.interpreter.v2.values.MezzeVariant
import com.mezze.interpreter.v2.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Integration tests for JSON streaming codec (Tier 2).
 *
 * These tests verify the streaming encode/decode functionality that writes
 * directly to ByteBuffer without building intermediate Json representation.
 *
 * Streaming codec is designed for large data (>100MB) where memory efficiency
 * is critical.
 *
 * All tests are initially @Disabled until the streaming implementation is complete.
 */
class JsonStreamCodecTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================================================
    // StreamEncodeFormat - Primitive Types
    // ========================================================================

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream encode int writes JSON integer to buffer`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            # Create a ByteBuffer and encode an integer
            let buffer = bytes::buffer_create {capacity = 64}
            let path = list::empty {}
            let result = stream::stream_int {value = 42, buffer = buffer, path = path}

            match result is
                Ok {val} -> bytes::buffer_to_str {buffer = val}
                Err {error} -> "error"
        """.trimIndent()

        val result = eval(source)
        assertEquals("42", result)
    }

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream encode float writes JSON float to buffer`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let buffer = bytes::buffer_create {capacity = 64}
            let path = list::empty {}
            let result = stream::stream_float {value = 3.14, buffer = buffer, path = path}

            match result is
                Ok {val} -> bytes::buffer_to_str {buffer = val}
                Err {error} -> "error"
        """.trimIndent()

        val result = eval(source)
        assertEquals("3.14", result)
    }

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream encode string writes JSON string with quotes to buffer`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let buffer = bytes::buffer_create {capacity = 64}
            let path = list::empty {}
            let result = stream::stream_string {value = "hello", buffer = buffer, path = path}

            match result is
                Ok {val} -> bytes::buffer_to_str {buffer = val}
                Err {error} -> "error"
        """.trimIndent()

        val result = eval(source)
        assertEquals("\"hello\"", result)
    }

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream encode bool true writes JSON true to buffer`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let buffer = bytes::buffer_create {capacity = 64}
            let path = list::empty {}
            let result = stream::stream_bool {value = True, buffer = buffer, path = path}

            match result is
                Ok {val} -> bytes::buffer_to_str {buffer = val}
                Err {error} -> "error"
        """.trimIndent()

        val result = eval(source)
        assertEquals("true", result)
    }

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream encode bool false writes JSON false to buffer`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let buffer = bytes::buffer_create {capacity = 64}
            let path = list::empty {}
            let result = stream::stream_bool {value = False, buffer = buffer, path = path}

            match result is
                Ok {val} -> bytes::buffer_to_str {buffer = val}
                Err {error} -> "error"
        """.trimIndent()

        val result = eval(source)
        assertEquals("false", result)
    }

    // ========================================================================
    // StreamEncodeFormat - Record Types
    // ========================================================================

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream encode empty record writes empty JSON object`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let buffer = bytes::buffer_create {capacity = 64}
            let path = list::empty {}

            # Start record
            let b1 = match stream::stream_record_start {buffer = buffer, path = path} is
                Ok {val} -> val
                Err {error} -> buffer

            # End record (no fields)
            let b2 = match stream::stream_record_end {buffer = b1, path = path} is
                Ok {val} -> val
                Err {error} -> b1

            bytes::buffer_to_str {buffer = b2}
        """.trimIndent()

        val result = eval(source)
        assertEquals("{}", result)
    }

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream encode record with fields writes JSON object`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let path = list::empty {}

            # Encode field values first
            let name_buf = bytes::buffer_create {capacity = 32}
            let name_val = match stream::stream_string {value = "Alice", buffer = name_buf, path = path} is
                Ok {val} -> val
                Err {error} -> name_buf

            let age_buf = bytes::buffer_create {capacity = 32}
            let age_val = match stream::stream_int {value = 30, buffer = age_buf, path = path} is
                Ok {val} -> val
                Err {error} -> age_buf

            # Build the record
            let buffer = bytes::buffer_create {capacity = 128}

            let b1 = match stream::stream_record_start {buffer = buffer, path = path} is
                Ok {val} -> val
                Err {error} -> buffer

            let b2 = match stream::stream_record_field {key = "name", value = name_val, is_first = True, buffer = b1, path = path} is
                Ok {val} -> val
                Err {error} -> b1

            let b3 = match stream::stream_record_field {key = "age", value = age_val, is_first = False, buffer = b2, path = path} is
                Ok {val} -> val
                Err {error} -> b2

            let b4 = match stream::stream_record_end {buffer = b3, path = path} is
                Ok {val} -> val
                Err {error} -> b3

            bytes::buffer_to_str {buffer = b4}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is String)
        val jsonStr = result as String
        assertTrue(jsonStr.contains("\"name\""))
        assertTrue(jsonStr.contains("\"Alice\""))
        assertTrue(jsonStr.contains("\"age\""))
        assertTrue(jsonStr.contains("30"))
    }

    // ========================================================================
    // StreamEncodeFormat - List Types
    // ========================================================================

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream encode empty list writes empty JSON array`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let buffer = bytes::buffer_create {capacity = 64}
            let path = list::empty {}

            let b1 = match stream::stream_list_start {buffer = buffer, path = path} is
                Ok {val} -> val
                Err {error} -> buffer

            let b2 = match stream::stream_list_end {buffer = b1, path = path} is
                Ok {val} -> val
                Err {error} -> b1

            bytes::buffer_to_str {buffer = b2}
        """.trimIndent()

        val result = eval(source)
        assertEquals("[]", result)
    }

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream encode list with elements writes JSON array`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let path = list::empty {}

            # Encode each element
            let e1_buf = bytes::buffer_create {capacity = 16}
            let e1 = match stream::stream_int {value = 1, buffer = e1_buf, path = path} is
                Ok {val} -> val
                Err {error} -> e1_buf

            let e2_buf = bytes::buffer_create {capacity = 16}
            let e2 = match stream::stream_int {value = 2, buffer = e2_buf, path = path} is
                Ok {val} -> val
                Err {error} -> e2_buf

            let e3_buf = bytes::buffer_create {capacity = 16}
            let e3 = match stream::stream_int {value = 3, buffer = e3_buf, path = path} is
                Ok {val} -> val
                Err {error} -> e3_buf

            # Build the list
            let buffer = bytes::buffer_create {capacity = 64}

            let b1 = match stream::stream_list_start {buffer = buffer, path = path} is
                Ok {val} -> val
                Err {error} -> buffer

            let b2 = match stream::stream_list_element {value = e1, is_first = True, buffer = b1, path = path} is
                Ok {val} -> val
                Err {error} -> b1

            let b3 = match stream::stream_list_element {value = e2, is_first = False, buffer = b2, path = path} is
                Ok {val} -> val
                Err {error} -> b2

            let b4 = match stream::stream_list_element {value = e3, is_first = False, buffer = b3, path = path} is
                Ok {val} -> val
                Err {error} -> b3

            let b5 = match stream::stream_list_end {buffer = b4, path = path} is
                Ok {val} -> val
                Err {error} -> b4

            bytes::buffer_to_str {buffer = b5}
        """.trimIndent()

        val result = eval(source)
        assertEquals("[1,2,3]", result)
    }

    // ========================================================================
    // StreamEncodeFormat - Option Types
    // ========================================================================

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream encode none writes JSON null`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let buffer = bytes::buffer_create {capacity = 64}
            let path = list::empty {}
            let result = stream::stream_none {buffer = buffer, path = path}

            match result is
                Ok {val} -> bytes::buffer_to_str {buffer = val}
                Err {error} -> "error"
        """.trimIndent()

        val result = eval(source)
        assertEquals("null", result)
    }

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream encode some writes wrapped value`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let path = list::empty {}

            # Encode the inner value
            let inner_buf = bytes::buffer_create {capacity = 32}
            let inner = match stream::stream_int {value = 42, buffer = inner_buf, path = path} is
                Ok {val} -> val
                Err {error} -> inner_buf

            # Wrap in Some
            let buffer = bytes::buffer_create {capacity = 64}
            let result = stream::stream_some {value = inner, buffer = buffer, path = path}

            match result is
                Ok {val} -> bytes::buffer_to_str {buffer = val}
                Err {error} -> "error"
        """.trimIndent()

        val result = eval(source)
        assertEquals("42", result)
    }

    // ========================================================================
    // StreamDecodeFormat - Primitive Types
    // ========================================================================

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream decode int reads JSON integer`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let data = bytes::from_string {s = "42"}
            let path = list::empty {}
            let result = stream::stream_decode_int {bytes = data, offset = 0, path = path}

            match result is
                Ok {val} -> val.value
                Err {error} -> 0 - 1
        """.trimIndent()

        val result = eval(source)
        assertEquals(42L, result)
    }

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream decode float reads JSON float`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let data = bytes::from_string {s = "3.14"}
            let path = list::empty {}
            let result = stream::stream_decode_float {bytes = data, offset = 0, path = path}

            match result is
                Ok {val} -> val.value
                Err {error} -> 0.0
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is Double)
        assertEquals(3.14, result as Double, 0.001)
    }

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream decode string reads JSON string`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let data = bytes::from_string {s = "\"hello\""}
            let path = list::empty {}
            let result = stream::stream_decode_string {bytes = data, offset = 0, path = path}

            match result is
                Ok {val} -> val.value
                Err {error} -> "error"
        """.trimIndent()

        val result = eval(source)
        assertEquals("hello", result)
    }

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream decode bool true reads JSON true`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let data = bytes::from_string {s = "true"}
            let path = list::empty {}
            let result = stream::stream_decode_bool {bytes = data, offset = 0, path = path}

            match result is
                Ok {val} -> val.value
                Err {error} -> False
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is Boolean)
        assertTrue(result as Boolean)
    }

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream decode bool false reads JSON false`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let data = bytes::from_string {s = "false"}
            let path = list::empty {}
            let result = stream::stream_decode_bool {bytes = data, offset = 0, path = path}

            match result is
                Ok {val} -> val.value
                Err {error} -> True
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is Boolean)
        assertFalse(result as Boolean)
    }

    // ========================================================================
    // StreamDecodeFormat - Offset Tracking
    // ========================================================================

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream decode returns correct offset after reading`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            # JSON: "42,100" - read first int, check offset points after it
            let data = bytes::from_string {s = "42,100"}
            let path = list::empty {}
            let result = stream::stream_decode_int {bytes = data, offset = 0, path = path}

            match result is
                Ok {val} -> val.offset
                Err {error} -> 0 - 1
        """.trimIndent()

        val result = eval(source)
        assertEquals(2L, result) // offset should be 2 (after "42")
    }

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream decode can read multiple values with offset tracking`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            # JSON array without brackets: "1,2,3"
            let data = bytes::from_string {s = "[1,2,3]"}
            let path = list::empty {}

            # Start array
            let start_result = stream::stream_decode_list_start {bytes = data, offset = 0, path = path}
            let offset1 = match start_result is
                Ok {val} -> val.offset
                Err {error} -> 0 - 1

            # Read first element
            let elem1_result = stream::stream_decode_int {bytes = data, offset = offset1, path = path}
            let val1 = match elem1_result is
                Ok {val} -> val.value
                Err {error} -> 0 - 1

            val1
        """.trimIndent()

        val result = eval(source)
        assertEquals(1L, result)
    }

    // ========================================================================
    // StreamDecodeFormat - Error Handling
    // ========================================================================

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream decode invalid JSON returns error`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let data = bytes::from_string {s = "not a number"}
            let path = list::empty {}
            let result = stream::stream_decode_int {bytes = data, offset = 0, path = path}

            match result is
                Ok {val} -> "ok"
                Err {error} -> "error"
        """.trimIndent()

        val result = eval(source)
        assertEquals("error", result)
    }

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream decode type mismatch returns error with path`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            # Try to decode a string as an int
            let data = bytes::from_string {s = "\"hello\""}
            let path = list::push {x = "field1", xs = list::empty {}}
            let result = stream::stream_decode_int {bytes = data, offset = 0, path = path}

            match result is
                Ok {val} -> "ok"
                Err {error} -> error.expected
        """.trimIndent()

        val result = eval(source)
        assertEquals("Int", result)
    }

    // ========================================================================
    // Integration Tests - Full Roundtrip
    // ========================================================================

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream encode then decode int roundtrip`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let path = list::empty {}

            # Encode
            let buffer = bytes::buffer_create {capacity = 64}
            let encoded = match stream::stream_int {value = 42, buffer = buffer, path = path} is
                Ok {val} -> val
                Err {error} -> buffer

            # Convert buffer to bytes for decoding
            let data = bytes::buffer_to_bytes {buffer = encoded}

            # Decode
            let decoded = stream::stream_decode_int {bytes = data, offset = 0, path = path}

            match decoded is
                Ok {val} -> val.value
                Err {error} -> 0 - 1
        """.trimIndent()

        val result = eval(source)
        assertEquals(42L, result)
    }

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream encode then decode string roundtrip`() {
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let path = list::empty {}

            # Encode
            let buffer = bytes::buffer_create {capacity = 64}
            let encoded = match stream::stream_string {value = "hello world", buffer = buffer, path = path} is
                Ok {val} -> val
                Err {error} -> buffer

            # Convert buffer to bytes for decoding
            let data = bytes::buffer_to_bytes {buffer = encoded}

            # Decode
            let decoded = stream::stream_decode_string {bytes = data, offset = 0, path = path}

            match decoded is
                Ok {val} -> val.value
                Err {error} -> "error"
        """.trimIndent()

        val result = eval(source)
        assertEquals("hello world", result)
    }

    // ========================================================================
    // Performance Characteristics (Conceptual Tests)
    // ========================================================================

    @Test
    @Disabled("Streaming codec not yet implemented")
    fun `stream encode does not build intermediate Json representation`() {
        // This test verifies the streaming codec writes directly to buffer
        // without allocating a Json variant tree.
        // The actual memory behavior is hard to test, but we can verify
        // the API works correctly for the streaming use case.
        val source = """
            use std::codec::core
            use std::codec::json::stream
            use std::collections::list
            use std::collections::bytes

            let path = list::empty {}
            let buffer = bytes::buffer_create {capacity = 1024}

            # Build a nested structure directly to buffer
            let b1 = match stream::stream_record_start {buffer = buffer, path = path} is
                Ok {val} -> val
                Err {error} -> buffer

            # Add many fields - in real usage this would be a loop
            let field_buf = bytes::buffer_create {capacity = 32}
            let field_val = match stream::stream_int {value = 1, buffer = field_buf, path = path} is
                Ok {val} -> val
                Err {error} -> field_buf

            let b2 = match stream::stream_record_field {key = "x", value = field_val, is_first = True, buffer = b1, path = path} is
                Ok {val} -> val
                Err {error} -> b1

            let b3 = match stream::stream_record_end {buffer = b2, path = path} is
                Ok {val} -> val
                Err {error} -> b2

            # Verify we got valid JSON output
            let output = bytes::buffer_to_str {buffer = b3}
            output
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is String)
        val json = result as String
        assertTrue(json.contains("{"))
        assertTrue(json.contains("}"))
        assertTrue(json.contains("\"x\""))
    }
}
