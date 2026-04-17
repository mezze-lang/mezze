package com.mezze.interpreter.v2.stdlib

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

            let encode_to_json: { body: 'a } -> Str with EncodeRepr 'a = { body } -> do
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
    fun `post_body helper with ability constraint compiles`() {
        val error = typeCheck("""
            use std::effects::http::{Http, HttpClient, post_body}

            type User = { name: Str, age: Int }

            let test = {} -> do
                let user: User = { name = "Alice", age = 30 }
                perform HttpClient in do
                    post_body { url = "https://example.com/users", body = user }
        """)
        assertNull(error, "Expected type check to succeed but got: $error")
    }

    @Test
    fun `put_body helper with ability constraint compiles`() {
        val error = typeCheck("""
            use std::effects::http::{Http, HttpClient, put_body}

            type User = { name: Str, age: Int }

            let test = {} -> do
                let user: User = { name = "Alice", age = 30 }
                perform HttpClient in do
                    put_body { url = "https://example.com/users/1", body = user }
        """)
        assertNull(error, "Expected type check to succeed but got: $error")
    }

    @Test
    fun `patch_body helper with ability constraint compiles`() {
        val error = typeCheck("""
            use std::effects::http::{Http, HttpClient, patch_body}

            type UserPatch = { name: Option Str }

            let test = {} -> do
                let patch: UserPatch = { name = Some { val = "Bob" } }
                perform HttpClient in do
                    patch_body { url = "https://example.com/users/1", body = patch }
        """)
        assertNull(error, "Expected type check to succeed but got: $error")
    }
}
