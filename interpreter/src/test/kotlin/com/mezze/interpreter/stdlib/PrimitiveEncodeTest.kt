package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * #195 PR-A ported to the new codec surface — primitive types (Int, Str,
 * Bool, Float, ...) must dispatch through the auto-derived `impl Encode
 * JsonC for <primitive>` synthesized from Tier-2 `EncodeScalar`. Without
 * that, polymorphic helpers bound on `with Encode JsonC 'a` would fail
 * to resolve for a primitive `'a`.
 *
 * Each test exercises `(value).encode { config = json_default }` against a
 * concrete primitive, then serializes and inspects the wire form. Encoding
 * is total (no `Result`) per redesign principle #2.
 */
class PrimitiveEncodeTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `Int encode dispatches to synthesized impl`() {
        val result = eval("""
            use std::codec::json::{Json, json_default, to_str}
            use std::codec::core::{Encode}

            let encoded: Json = (42).encode { config = json_default }
            to_str { json = encoded }
        """)
        assertEquals("42", result)
    }

    @Test
    fun `Str encode dispatches to synthesized impl`() {
        val result = eval("""
            use std::codec::json::{Json, json_default, to_str}
            use std::codec::core::{Encode}

            let encoded: Json = "hello".encode { config = json_default }
            to_str { json = encoded }
        """)
        assertEquals("\"hello\"", result)
    }

    @Test
    fun `Bool encode dispatches to synthesized impl`() {
        val result = eval("""
            use std::codec::json::{Json, json_default, to_str}
            use std::codec::core::{Encode}

            let encoded: Json = True.encode { config = json_default }
            to_str { json = encoded }
        """)
        assertEquals("true", result)
    }

    @Test
    fun `Float encode dispatches to synthesized impl`() {
        val result = eval("""
            use std::codec::json::{Json, json_default, to_str}
            use std::codec::core::{Encode}

            let encoded: Json = (3.14).encode { config = json_default }
            to_str { json = encoded }
        """)
        assertEquals("3.14", result)
    }
}
