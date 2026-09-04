package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * #195 PR-B — calling an ability method on a polymorphic value inside a HOF
 * callback must dispatch through the impl resolved at the outer call site.
 * Ported to the new codec surface (task #86): the polymorphic helper carries
 * `with Encode JsonC 'a` and the compiler auto-derives `Encode JsonC Str`
 * per-value-type at the caller.
 *
 * These tests exercise Tier-3 dispatch inside a HOF callback (`seq::fold`),
 * proving impl-threading survives lambda nesting.
 */
class PolymorphicAbilityInHofTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `polymorphic helper dispatches encode on Str via outer constraint`() {
        // A helper `'a with Encode JsonC 'a` calls `value.encode` inside its
        // body, and is invoked with `'a = Str`. Encoding is total (no Result)
        // per redesign principle #2.
        val result = eval("""
            use std::codec::json::{Json, JsonC, json_default, to_str}
            use std::codec::core::{Encode}

            let to_query_str : { val: 'a } with Encode JsonC 'a -> Str = { val } -> do
              let encoded: Json = val.encode { config = json_default }
              to_str { json = encoded }

            to_query_str { val = "hello" }
        """)
        assertEquals("\"hello\"", result)
    }

    @Test
    fun `polymorphic helper dispatches encode inside a fold callback`() {
        // The #195 shape: the call site is nested inside a seq::fold lambda.
        // Impl-threading must survive lambda nesting.
        val result = eval("""
            use std::codec::json::{Json, JsonC, json_default}
            use std::codec::core::{Encode}
            use std::collections::seq

            let count_encoded : { xs: [Str] } -> Int = { xs } ->
              seq::fold {
                xs = xs,
                init = 0,
                f = { acc, x } -> do
                  let _: Json = x.encode { config = json_default }
                  acc + 1,
              }

            count_encoded { xs = ["a", "b", "c"] }
        """)
        assertEquals(3L, result)
    }
}
