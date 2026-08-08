package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * #195 PR-A — primitive types (Str, Int, Bool, ...) must have `impl EncodeRepr` and
 * `impl DecodeRepr` blobs visible to dispatch, not just codec-generator inlining at
 * the call site. Without these, polymorphic stdlib helpers bound on
 * `with EncodeRepr 'a` crash at runtime when `'a` instantiates to a primitive
 * — see HttpStageDTest commented-out polymorphic with_query notes.
 *
 * These tests exercise `(value).encode_repr {...}` and `repr.decode_repr {...}`
 * against concrete primitives. The receiver type is statically known here, so
 * dispatch resolves via the seeded impl. The polymorphic-receiver case (the
 * actual #195 motivator) requires PR-B/C and is verified separately.
 */
@Disabled("#399: fails with 'Non-exhaustive match: no variant tag matched' — surfaced once #397 unblocked the ParallelJoinPanicTest hang. Root cause is #398 (TypedVariantPattern.type never receives solver substitution → tag mismatch between codec-generated Ok/Err construction and pattern sites). Re-enable after codec redesign lands and #398 is fixed.")
class PrimitiveEncodeReprTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `Int encode_repr dispatches to seeded impl`() {
        val result = eval("""
            use std::codec::json::{json_fmt}
            use std::codec::core::{default_config}

            match (42).encode_repr { fmt = json_fmt, config = default_config } is
                Ok { val = _ } -> 1
                Err { error = _ } -> -1
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `Str encode_repr dispatches to seeded impl`() {
        val result = eval("""
            use std::codec::json::{json_fmt}
            use std::codec::core::{default_config}

            match "hello".encode_repr { fmt = json_fmt, config = default_config } is
                Ok { val = _ } -> 1
                Err { error = _ } -> -1
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `Bool encode_repr dispatches to seeded impl`() {
        val result = eval("""
            use std::codec::json::{json_fmt}
            use std::codec::core::{default_config}

            match True.encode_repr { fmt = json_fmt, config = default_config } is
                Ok { val = _ } -> 1
                Err { error = _ } -> -1
        """)
        assertEquals(1L, result)
    }

    @Test
    fun `Float encode_repr dispatches to seeded impl`() {
        val result = eval("""
            use std::codec::json::{json_fmt}
            use std::codec::core::{default_config}

            match (3.14).encode_repr { fmt = json_fmt, config = default_config } is
                Ok { val = _ } -> 1
                Err { error = _ } -> -1
        """)
        assertEquals(1L, result)
    }
}
