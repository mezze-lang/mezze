package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.MezzeString
import com.mezze.language.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HttpModuleTest {

    @Test
    fun `test decode with type annotation`() {
        // This test verifies that Decode.decode works with explicit type annotation
        val result = MezzeTestHelper.run("""
            use std::codec::json
            use std::codec::json::{Json}
            use std::codec::core::{Decode, DecodeError, default_config}

            let json_obj = JObject { fields = [
                {key = "name", value = JString {val = "Alice"}},
                {key = "email", value = JString {val = "alice@example.com"}}
            ] }

            let user: Result {name: String, email: String} DecodeError = Decode.decode {val = json_obj, config = default_config}
            match user is
                Ok { val } -> val.name
                Err { error } -> "error"
        """)
        assertEquals("Alice", (result as MezzeString).value)
    }

    @Test
    fun `test decode with bidirectional type inference`() {
        // This test verifies that Decode.decode works WITHOUT type annotation
        // The type is inferred from field access in the match body (val.name)
        val result = MezzeTestHelper.run("""
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
        assertEquals("Bob", (result as MezzeString).value)
    }

    @Test
    fun `test decode inside perform block with bidirectional inference`() {
        // This test verifies that Decode.decode works INSIDE a perform block
        // WITHOUT type annotation - type inferred from field access
        val result = MezzeTestHelper.run("""
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
        assertEquals("Charlie", (result as MezzeString).value)
    }
}
