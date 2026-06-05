package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * #195 PR-B — calling an ability method on a polymorphic value inside a HOF
 * callback should dispatch through the ability impl resolved at the outer
 * call site. With PR-A's seeded primitive impls in place, the impls exist;
 * the gap is in how the compiler threads the impl into the inner lambda.
 *
 * These tests fail today (pre-PR-B) — they're the regression net that
 * proves PR-B's fix actually works.
 */
class PolymorphicAbilityInHofTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `polymorphic helper dispatches encode_repr on Str via outer constraint`() {
        // The classic motivator: a helper with `'a with EncodeRepr 'a` calls
        // `value.encode_repr` inside its body, and gets invoked with 'a = Str.
        val result = eval("""
            use std::codec::json::{json_fmt}
            use std::codec::core::{EncodeRepr, default_config}

            let to_query_str : { val: 'a } with EncodeRepr 'a -> Str = { val } ->
              match val.encode_repr { fmt = json_fmt, config = default_config } is
                Ok { val = _ } -> "ok"
                Err { error = _ } -> "err"

            to_query_str { val = "hello" }
        """)
        assertEquals("ok", result)
    }

    @Test
    fun `polymorphic helper dispatches encode_repr inside a fold callback`() {
        // The actual #195 shape: the call site is nested inside a seq::fold lambda.
        val result = eval("""
            use std::codec::json::{json_fmt}
            use std::codec::core::{EncodeRepr, default_config}
            use std::collections::seq

            let count_encodable : { xs: [Str] } -> Int = { xs } ->
              seq::fold {
                xs = xs,
                init = 0,
                f = { acc, x } -> match x.encode_repr { fmt = json_fmt, config = default_config } is
                  Ok { val = _ } -> acc + 1
                  Err { error = _ } -> acc,
              }

            count_encodable { xs = ["a", "b", "c"] }
        """)
        assertEquals(3L, result)
    }
}
