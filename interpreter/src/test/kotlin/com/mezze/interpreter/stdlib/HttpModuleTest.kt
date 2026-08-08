package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for HTTP module codec integration.
 * Migrated from V1 to V2 with unboxed values.
 */
class HttpModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `test decode with type annotation`() {
        val result = eval("""
            use std::codec::json
            use std::codec::json::{Json, JsonFmt, json_fmt}
            use std::codec::core::{DecodeRepr, DecodeError, default_config}

            let json_obj = JObject { fields = [
                {key = "name", value = JString {val = "Alice"}},
                {key = "email", value = JString {val = "alice@example.com"}}
            ] }

            let user: Result {name: Str, email: Str} DecodeError = json_obj.decode_repr { fmt = json_fmt, config = default_config }
            match user is
                Ok { val } -> val.name
                Err { error } -> "error"
        """)
        assertEquals("Alice", result)
    }

    @Test
    fun `test decode with bidirectional type inference`() {
        val result = eval("""
            use std::codec::json
            use std::codec::json::{Json, JsonFmt, json_fmt}
            use std::codec::core::{DecodeRepr, DecodeError, default_config}

            let json_obj = JObject { fields = [
                {key = "name", value = JString {val = "Bob"}},
                {key = "age", value = JInt {val = 30}}
            ] }

            let user: Result {name: Str, age: Int} DecodeError = json_obj.decode_repr { fmt = json_fmt, config = default_config }
            match user is
                Ok { val } -> val.name
                Err { error } -> "error"
        """)
        assertEquals("Bob", result)
    }

    @Test
    @Disabled("#399: fails with 'Non-exhaustive match: no variant tag matched' — surfaced once #397 unblocked the ParallelJoinPanicTest hang. Root cause is #398 (TypedVariantPattern.type never receives solver substitution → tag mismatch between codec-generated Ok/Err construction and pattern sites). Re-enable after codec redesign lands and #398 is fixed.")
    fun `test encode via method-call syntax`() {
        val result = eval("""
            use std::codec::json
            use std::codec::json::{Json, JsonFmt, json_fmt, to_str}
            use std::codec::core::{EncodeRepr, EncodeError, default_config}

            let user = { name = "Dave", age = 25 }
            let encoded: Result Json EncodeError = user.encode_repr { fmt = json_fmt, config = default_config }
            match encoded is
                Ok { val } -> to_str { json = val }
                Err { error } -> "error"
        """)
        // Should produce valid JSON string
        val str = result as String
        assertTrue(str.contains("Dave"), "Expected JSON to contain 'Dave' but got: $str")
    }

    @Test
    fun `test decode inside perform block with bidirectional inference`() {
        val result = eval("""
            use std::effects::log::{Console, println}
            use std::codec::json
            use std::codec::json::{Json, JsonFmt, json_fmt}
            use std::codec::core::{DecodeRepr, DecodeError, default_config}

            let json_obj = JObject { fields = [
                {key = "name", value = JString {val = "Charlie"}}
            ] }

            perform Console in do
                println { msg = "Starting decode" }
                let user: Result {name: Str} DecodeError = json_obj.decode_repr { fmt = json_fmt, config = default_config }
                match user is
                    Ok { val } -> val.name
                    Err { error } -> "error"
        """)
        assertEquals("Charlie", result)
    }
}
