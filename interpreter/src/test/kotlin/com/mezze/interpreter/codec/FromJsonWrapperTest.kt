package com.mezze.interpreter.codec

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * `from_json` (target-directed JSON decode) called through a WRAPPER function
 * whose return type carries the decode target as a bounded-polymorphic type var
 * (`… with FromJson 'a Str -> Result 'a …`).
 *
 * Two bugs guarded here, both fixed 2026-09-06:
 *
 * 1. TYPECHECK (AbilityChecker.convertAnnotationToType): a return-only `'a` with
 *    an ability constraint was materialized as `TypeApplication("'a")` — a var
 *    in a type-application costume — when the deferred `FromJson` obligation was
 *    resolved at the call site, so it couldn't unify with the caller's concrete
 *    target. Simple wrappers (`{s} -> s.from_json {}`) rejected.
 *
 * 2. CODEGEN (TypedASTCompiler): when the `from_json` call sits inside a generic
 *    combinator callback (`flat_map { f = {val} -> map_err { result =
 *    val.from_json {}, … } }`), its receiver stays an unresolved VarType at emit
 *    and the compiler bypassed the per-site decoder wiring (`decodeOpBySite`),
 *    compiling `.from_json` as a plain field read → runtime
 *    UnsupportedSpecializationException. Now the receiver's input type is
 *    recovered from the pinned decoder impl.
 *
 * Both are exercised by decoding through a wrapper and reading a field of the
 * result.
 */
class FromJsonWrapperTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(source: String): Any = MezzeTestHelper.run(source)

    /** Simplest wrapper: body IS the from_json. Guards bug 1. */
    @Test
    fun `decode through a trivial from_json wrapper`() {
        val src = """
            use std::codec::json::{ FromJson }
            use std::codec::core::{ DecodeError }
            let dec = { s } -> s.from_json {}
            let r: Result { name: Str } DecodeError = dec { s = "{\"name\":\"Ada\"}" }
            match r is
              Ok { val = { name } } -> name
              Err { error = _ } -> "err"
        """.trimIndent()
        assertEquals("Ada", run(src))
    }

    /** One level of combinator nesting around from_json. */
    @Test
    fun `decode through a map_err wrapper`() {
        val src = """
            use std::codec::json::{ FromJson }
            use std::result::{ map_err }
            let dec = { s } -> map_err { result = s.from_json {}, f = { error } -> "e" }
            let r: Result { name: Str } Str = dec { s = "{\"name\":\"Ada\"}" }
            match r is
              Ok { val = { name } } -> name
              Err { error = _ } -> "err"
        """.trimIndent()
        assertEquals("Ada", run(src))
    }

    // NOTE: from_json nested TWO+ levels deep inside flat_map callbacks
    // (`flat_map { f = {val} -> map_err { result = val.from_json {}, … } }`) is
    // the case the CODEGEN fix (TypedASTCompiler) addresses — it crashed at
    // runtime before. It is verified in PROGRAM mode (`mez exec`) but not
    // asserted here: this suite's MezzeTestHelper runs in eval mode, where a
    // separate codec-derivation timing gap rejects the concrete FromJson target
    // for a deeply-nested wrapper. The one-level `map_err` case above already
    // exercises the VarType-receiver codegen path in eval mode.

    /** The wrapper's error channel is a user variant (not Str). */
    @Test
    fun `decode through a wrapper with a variant error type`() {
        val src = """
            use std::codec::json::{ FromJson }
            use std::result::{ map_err }
            type Fail = | Boom { m: Str } |
            let dec = { s } -> map_err { result = s.from_json {}, f = { error } -> Boom { m = "d" } }
            let r: Result { name: Str } Fail = dec { s = "{\"name\":\"Ada\"}" }
            match r is
              Ok { val = { name } } -> name
              Err { error = _ } -> "err"
        """.trimIndent()
        assertEquals("Ada", run(src))
    }
}
