package com.mezze.interpreter.stdlib

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for ability constraints on functions.
 *
 * Ability constraints allow functions to require specific type class implementations.
 * For example, `with Encode Json 'a` requires the type parameter 'a to have an
 * Encode implementation for the Json format.
 *
 * Migrated from V1 to V2 with unboxed values.
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
            use std::codec::core::{EncodeRepr, CodecConfig, default_config}
            use std::codec::json::{Json, JsonFmt, json_fmt, to_str}

            let encode_to_json: { body: 'a } with EncodeRepr 'a -> Str = { body } -> do
                let result = body.encode_repr { fmt = json_fmt, config = default_config }
                match result is
                    Ok { val } -> to_str { json = val }
                    Err { error } -> "error"

            let main = {} -> do
                let user = { name = "Alice", age = 30 }
                encode_to_json { body = user }
        """)
        assertNull(error, "Expected type check to succeed but got: $error")
    }

    @Test
    fun `post with json body and ability constraint compiles`() {
        // Verifies an EncodeRepr-constrained helper can encode an arbitrary
        // value and ship it through the chainable HTTP request surface.
        val error = typeCheck("""
            use std::effects::http::{Http, HttpClient, Method, request}
            use std::codec::core::{EncodeRepr, default_config}
            use std::codec::json::{json_fmt, to_str}
            use std::collections::bytes

            type User = { name: Str, age: Int }

            let post_encoded: { url: Str, body: 'a } with EncodeRepr 'a -> { } <Http> = { url, body } -> do
                let encoded = body.encode_repr { fmt = json_fmt, config = default_config }
                match encoded is
                    Ok { val } -> do
                        let body_bytes = bytes::from_string { s = to_str { json = val } }
                        request { url = url }
                            .with_method { val = Post }
                            .with_body { val = body_bytes }
                            .send {}
                        {}
                    Err { error = _ } -> {}

            let test = {} -> do
                let user: User = { name = "Alice", age = 30 }
                perform HttpClient in
                    post_encoded { url = "https://example.com/users", body = user }
        """)
        assertNull(error, "Expected type check to succeed but got: $error")
    }

    @Test
    fun `put with json body and ability constraint compiles`() {
        val error = typeCheck("""
            use std::effects::http::{Http, HttpClient, Method, request}
            use std::codec::core::{EncodeRepr, default_config}
            use std::codec::json::{json_fmt, to_str}
            use std::collections::bytes

            type User = { name: Str, age: Int }

            let put_encoded: { url: Str, body: 'a } with EncodeRepr 'a -> { } <Http> = { url, body } -> do
                let encoded = body.encode_repr { fmt = json_fmt, config = default_config }
                match encoded is
                    Ok { val } -> do
                        let body_bytes = bytes::from_string { s = to_str { json = val } }
                        request { url = url }
                            .with_method { val = Put }
                            .with_body { val = body_bytes }
                            .send {}
                        {}
                    Err { error = _ } -> {}

            let test = {} -> do
                let user: User = { name = "Alice", age = 30 }
                perform HttpClient in
                    put_encoded { url = "https://example.com/users/1", body = user }
        """)
        assertNull(error, "Expected type check to succeed but got: $error")
    }

    @Test
    fun `patch with json body and ability constraint compiles`() {
        val error = typeCheck("""
            use std::effects::http::{Http, HttpClient, Method, request}
            use std::codec::core::{EncodeRepr, default_config}
            use std::codec::json::{json_fmt, to_str}
            use std::collections::bytes

            type UserPatch = { name: Option Str }

            let patch_encoded: { url: Str, body: 'a } with EncodeRepr 'a -> { } <Http> = { url, body } -> do
                let encoded = body.encode_repr { fmt = json_fmt, config = default_config }
                match encoded is
                    Ok { val } -> do
                        let body_bytes = bytes::from_string { s = to_str { json = val } }
                        request { url = url }
                            .with_method { val = Patch }
                            .with_body { val = body_bytes }
                            .send {}
                        {}
                    Err { error = _ } -> {}

            let test = {} -> do
                let patch: UserPatch = { name = Some { val = "Bob" } }
                perform HttpClient in
                    patch_encoded { url = "https://example.com/users/1", body = patch }
        """)
        assertNull(error, "Expected type check to succeed but got: $error")
    }
}
