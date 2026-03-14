package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
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
            use std::codec::json::{Json}
            use std::codec::core::{Decode, DecodeError, default_config}

            let json_obj = JObject { fields = [
                {key = "name", value = JString {val = "Alice"}},
                {key = "email", value = JString {val = "alice@example.com"}}
            ] }

            let user: Result {name: Str, email: Str} DecodeError = Decode.decode {val = json_obj, config = default_config}
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
            use std::codec::json::{Json}
            use std::codec::core::{Decode, DecodeError, default_config}

            let json_obj = JObject { fields = [
                {key = "name", value = JString {val = "Bob"}},
                {key = "age", value = JInt {val = 30}}
            ] }

            let user = Decode.decode {val = json_obj, config = default_config}
            match user is
                Ok { val } -> val.name
                Err { error } -> "error"
        """)
        assertEquals("Bob", result)
    }

    @Test
    fun `test decode inside perform block with bidirectional inference`() {
        val result = eval("""
            use std::effects::log::{Console, println}
            use std::codec::json
            use std::codec::json::{Json}
            use std::codec::core::{Decode, DecodeError, default_config}

            let json_obj = JObject { fields = [
                {key = "name", value = JString {val = "Charlie"}}
            ] }

            perform Console in do
                println { msg = "Starting decode" }
                let user = Decode.decode {val = json_obj, config = default_config}
                match user is
                    Ok { val } -> val.name
                    Err { error } -> "error"
        """)
        assertEquals("Charlie", result)
    }
}
