package com.mezze.interpreter.stdlib

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for ability constraints on functions in the new codec surface.
 *
 * Ability constraints let a function require Tier-3 `Encode`/`Decode` for
 * the type parameter it operates on. Constraint shape: `with Encode JsonC 'a`
 * — parameter `'a` must have an `Encode` impl for the concrete config type.
 *
 * See `docs/codec/redesign-2026-09.md` §"Polymorphic constraint patterns".
 * Auto-derivation kicks in per (config, 'a) at the caller site.
 */
class AbilityConstraintTest {
    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun typeCheck(source: String): String? {
        return try {
            val ast = parser.parseParsedModule(source.trimIndent())
            typeChecker.typeCheck(ast, java.nio.file.Path.of("."))
            null // Success - no error
        } catch (e: Exception) {
            e.message
        }
    }

    @Test
    fun `function with Encode ability constraint compiles`() {
        val error = typeCheck("""
            use std::codec::core::{Encode}
            use std::codec::json::{Json, JsonC, json_default, to_str}

            let encode_to_json: { body: 'a } with Encode JsonC 'a -> Str = { body } -> do
                let result: Json = body.encode { config = json_default }
                to_str { json = result }

            let main = {} -> do
                let user = { name = "Alice", age = 30 }
                encode_to_json { body = user }
        """)
        assertNull(error, "Expected type check to succeed but got: ${'$'}error")
    }

    @Test
    fun `post with json body and ability constraint compiles`() {
        // Verifies an Encode-constrained helper can encode an arbitrary value
        // and ship it through the chainable HTTP request surface.
        val error = typeCheck("""
            use std::effects::http::{HttpClient, PooledHttpClient, Method, request}
            use std::codec::core::{Encode}
            use std::codec::json::{Json, JsonC, json_default, to_str}
            use std::collections::bytes

            type User = { name: Str, age: Int }

            let post_encoded: { url: Str, body: 'a } with Encode JsonC 'a -> { } <HttpClient> = { url, body } -> do
                let encoded: Json = body.encode { config = json_default }
                let body_bytes = bytes::from_str { s = to_str { json = encoded } }
                request { url = url }
                    .with_method { val = Post }
                    .with_body { val = body_bytes }
                    .send {}
                {}

            let test = {} -> do
                let user: User = { name = "Alice", age = 30 }
                perform PooledHttpClient in
                    post_encoded { url = "https://example.com/users", body = user }
        """)
        assertNull(error, "Expected type check to succeed but got: ${'$'}error")
    }

    @Test
    fun `put with json body and ability constraint compiles`() {
        val error = typeCheck("""
            use std::effects::http::{HttpClient, PooledHttpClient, Method, request}
            use std::codec::core::{Encode}
            use std::codec::json::{Json, JsonC, json_default, to_str}
            use std::collections::bytes

            type User = { name: Str, age: Int }

            let put_encoded: { url: Str, body: 'a } with Encode JsonC 'a -> { } <HttpClient> = { url, body } -> do
                let encoded: Json = body.encode { config = json_default }
                let body_bytes = bytes::from_str { s = to_str { json = encoded } }
                request { url = url }
                    .with_method { val = Put }
                    .with_body { val = body_bytes }
                    .send {}
                {}

            let test = {} -> do
                let user: User = { name = "Alice", age = 30 }
                perform PooledHttpClient in
                    put_encoded { url = "https://example.com/users/1", body = user }
        """)
        assertNull(error, "Expected type check to succeed but got: ${'$'}error")
    }

    @Test
    fun `patch with json body and ability constraint compiles`() {
        val error = typeCheck("""
            use std::effects::http::{HttpClient, PooledHttpClient, Method, request}
            use std::codec::core::{Encode}
            use std::codec::json::{Json, JsonC, json_default, to_str}
            use std::collections::bytes

            type UserPatch = { name: Option Str }

            let patch_encoded: { url: Str, body: 'a } with Encode JsonC 'a -> { } <HttpClient> = { url, body } -> do
                let encoded: Json = body.encode { config = json_default }
                let body_bytes = bytes::from_str { s = to_str { json = encoded } }
                request { url = url }
                    .with_method { val = Patch }
                    .with_body { val = body_bytes }
                    .send {}
                {}

            let test = {} -> do
                let patch: UserPatch = { name = Some { val = "Bob" } }
                perform PooledHttpClient in
                    patch_encoded { url = "https://example.com/users/1", body = patch }
        """)
        assertNull(error, "Expected type check to succeed but got: ${'$'}error")
    }
}
