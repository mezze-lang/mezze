package com.mezze.language.interpreter.stdlib

import com.mezze.language.interpreter.MezzeString
import com.mezze.language.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HttpModuleTest {

    @Test
    fun `test simple decode record with type annotation`() {
        // This test verifies that Decode.decode works OUTSIDE a perform block
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
    fun `test decode inside perform block`() {
        // This test verifies that Decode.decode works INSIDE a perform block
        // The type annotation on the let binding guides ability resolution
        val result = MezzeTestHelper.run("""
            use std::effects::log::{Log, Console, println}
            use std::codec::json
            use std::codec::json::{Json}
            use std::codec::core::{Decode, DecodeError, default_config}

            let json_obj = JObject { fields = [
                {key = "name", value = JString {val = "Bob"}}
            ] }

            perform Console in do
                println { msg = "Starting decode" }
                let user: Result {name: String} DecodeError = Decode.decode {val = json_obj, config = default_config}
                match user is
                    Ok { val } -> val.name
                    Err { error } -> "error"
        """)
        assertEquals("Bob", (result as MezzeString).value)
    }
}
