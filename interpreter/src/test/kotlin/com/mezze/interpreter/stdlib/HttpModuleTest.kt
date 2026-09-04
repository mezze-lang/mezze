package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

/**
 * Tests the new-codec dispatch surface for records — Tier-3 `decode` /
 * `encode` with LHS-type-annotation driving synthesis. The Tier-4 sugar
 * (`Type.from_json{}` / `x.json{}`) targets nominal types; anonymous
 * records still round-trip through the Tier-3 primitive.
 *
 * See `docs/codec/redesign-2026-09.md` §"Tier 3 — Encode / Decode primitive".
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
            use std::codec::json::{Json, json_default}
            use std::codec::core::{Decode, DecodeError}

            let json_obj: Json = JObject { fields = [
                {key = "name", value = JString {val = "Alice"}},
                {key = "email", value = JString {val = "alice@example.com"}}
            ] }

            let user: Result {name: Str, email: Str} DecodeError = json_obj.decode { config = json_default }
            match user is
                Ok { val } -> val.name
                Err { error } -> "error"
        """)
        assertEquals("Alice", result)
    }

    @Test
    fun `test decode with bidirectional type inference`() {
        val result = eval("""
            use std::codec::json::{Json, json_default}
            use std::codec::core::{Decode, DecodeError}

            let json_obj: Json = JObject { fields = [
                {key = "name", value = JString {val = "Bob"}},
                {key = "age", value = JInt {val = 30}}
            ] }

            let user: Result {name: Str, age: Int} DecodeError = json_obj.decode { config = json_default }
            match user is
                Ok { val } -> val.name
                Err { error } -> "error"
        """)
        assertEquals("Bob", result)
    }

    @Test
    fun `test encode via method-call syntax`() {
        // #399 (Disabled originally): now exercises the new Tier-3 encode
        // surface. Record encoding synthesizes `Encode JsonC` from Tier-2
        // per-shape impls and produces a `Json` value (no `Result`, per
        // "Encoding is total" — see `docs/codec/redesign-2026-09.md` §Design
        // principles #2).
        val result = eval("""
            use std::codec::json::{Json, to_str, json_default}
            use std::codec::core::{Encode}

            let user = { name = "Dave", age = 25 }
            let encoded: Json = user.encode { config = json_default }
            to_str { json = encoded }
        """)
        val str = result as String
        assertTrue(str.contains("Dave"), "Expected JSON to contain 'Dave' but got: ${'$'}str")
    }

    @Test
    fun `test decode inside perform block with bidirectional inference`() {
        val result = eval("""
            use std::effects::log::{Console, println}
            use std::codec::json::{Json, json_default}
            use std::codec::core::{Decode, DecodeError}

            let json_obj: Json = JObject { fields = [
                {key = "name", value = JString {val = "Charlie"}}
            ] }

            perform Console in do
                println { msg = "Starting decode" }
                let user: Result {name: Str} DecodeError = json_obj.decode { config = json_default }
                match user is
                    Ok { val } -> val.name
                    Err { error } -> "error"
        """)
        assertEquals("Charlie", result)
    }
}
