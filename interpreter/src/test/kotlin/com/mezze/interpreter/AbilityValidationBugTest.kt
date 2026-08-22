package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Comprehensive tests for ability declaration and implementation validation.
 *
 * The typechecker must reject invalid code at the definition point —
 * NOT wait until a call site to fail.
 */
class AbilityValidationBugTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun shouldTypeError(source: String, description: String) {
        try {
            eval(source)
            fail("Expected type error for: $description — but it ran successfully")
        } catch (e: Exception) {
            val msg = e.message ?: ""
            assertTrue(
                msg.contains("type") || msg.contains("Type") || msg.contains("annotation") ||
                msg.contains("mismatch") || msg.contains("TypeCheck") || msg.contains("must be") ||
                msg.contains("must declare") || msg.contains("record syntax") ||
                e::class.simpleName?.contains("TypeCheck") == true,
                "Expected TYPE error but got runtime error for: $description\n${e::class.simpleName}: $msg"
            )
        }
    }

    // ========================================================================
    // GOOD: Valid ability declarations and implementations
    // ========================================================================

    @Test
    fun `valid ability with single param`() {
        val result = eval("""
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }
            impl Show for Int where {
              show = {it} -> "int"
            }
            42.show {}
        """.trimIndent())
        assertEquals("int", result)
    }

    @Test
    fun `valid ability with multiple params`() {
        // NB: the ability method must not be named `add` — that collides with the
        // built-in NumAdd operator on Int (#279), which would silently shadow it.
        val result = eval("""
            ability Combine for 'a where {
              combine: {it: 'a, r: 'a} -> 'a
            }
            impl Combine for Int where {
              combine = {it, r} -> it + r
            }
            3.combine {r = 4}
        """.trimIndent())
        assertEquals(7L, result)
    }

    @Test
    fun `valid ability with stdlib call in body`() {
        val result = eval("""
            use std::int
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }
            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }
            5.show {}
        """.trimIndent())
        assertEquals("5", result)
    }

    @Test
    fun `valid ability used inside perform block`() {
        val result = eval("""
            use std::effects::log::{Console, println}
            use std::int
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }
            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }
            perform Console in do
              let x = 5.show {}
              println {msg = x}
              x
        """.trimIndent())
        assertEquals("5", result)
    }

    @Test
    fun `valid polymorphic function with ability constraint`() {
        val result = eval("""
            ability Describe for 'a where {
              describe: {it: 'a} -> Str
            }
            impl Describe for Int where {
              describe = {it} -> "int"
            }
            let show = {x} -> x.describe {}
            show {x = 42}
        """.trimIndent())
        assertEquals("int", result)
    }

    // ========================================================================
    // BUG 3: Let-generalization — polymorphic functions must stay generic
    // ========================================================================

    @Test
    fun `polymorphic function called with two different types`() {
        val result = eval("""
            use std::int
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }
            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }
            impl Show for Str where {
              show = {it} -> it
            }
            let my_func = {x} -> x.show {}

            let r1 = my_func {x = 5}
            let r2 = my_func {x = "hello"}
            r1
        """.trimIndent())
        assertEquals("5", result)
    }

    @Test
    fun `polymorphic function second call site returns correct value`() {
        val result = eval("""
            use std::int
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }
            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }
            impl Show for Str where {
              show = {it} -> it
            }
            let my_func = {x} -> x.show {}

            let r1 = my_func {x = 5}
            let r2 = my_func {x = "hello"}
            r2
        """.trimIndent())
        assertEquals("hello", result)
    }

    @Test
    fun `polymorphic function called with Str first then Int`() {
        // Order shouldn't matter — both should work regardless of which is first
        val result = eval("""
            use std::int
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }
            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }
            impl Show for Str where {
              show = {it} -> it
            }
            let my_func = {x} -> x.show {}

            let r1 = my_func {x = "world"}
            let r2 = my_func {x = 42}
            r2
        """.trimIndent())
        assertEquals("42", result)
    }

    @Test
    fun `polymorphic function with multiple ability constraints`() {
        val result = eval("""
            ability Describe for 'a where {
              describe: {it: 'a} -> Str
            }
            impl Describe for Int where {
              describe = {it} -> "int"
            }
            impl Describe for Bool where {
              describe = {it} -> "bool"
            }
            let show_type = {x} -> x.describe {}

            let r1 = show_type {x = 42}
            let r2 = show_type {x = True}
            r2
        """.trimIndent())
        assertEquals("bool", result)
    }

    @Test
    fun `value binding with ability should NOT generalize`() {
        // let x = some_ability_call should stay monomorphic
        // This is the case the original restriction was designed for
        val result = eval("""
            ability Double for 'a where {
              double: {it: 'a} -> 'a
            }
            impl Double for Int where {
              double = {it} -> it + it
            }
            let x = 5.double {}
            x
        """.trimIndent())
        assertEquals(10L, result)
    }

    @Test
    fun `valid ability with return type matching receiver type`() {
        val result = eval("""
            ability Double for 'a where {
              double: {it: 'a} -> 'a
            }
            impl Double for Int where {
              double = {it} -> it + it
            }
            21.double {}
        """.trimIndent())
        assertEquals(42L, result)
    }

    // ========================================================================
    // BAD: Invalid ability declarations (should be rejected)
    // ========================================================================

    @Test
    fun `reject bare type in ability op declaration`() {
        shouldTypeError("""
            ability Show for 'a where {
              show: 'a -> Str
            }
            impl Show for Int where {
              show = {it} -> "int"
            }
            42.show {}
        """.trimIndent(), "bare type 'a -> Str instead of {it: 'a} -> Str")
    }

    // ========================================================================
    // BAD: Invalid ability implementations (should be rejected)
    // ========================================================================

    @Test
    fun `reject impl op as bare identifier`() {
        shouldTypeError("""
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }
            impl Show for Int where {
              show = it
            }
            42.show {}
        """.trimIndent(), "show = it (bare identifier, not a lambda)")
    }

    @Test
    fun `reject impl op as literal`() {
        shouldTypeError("""
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }
            impl Show for Int where {
              show = "always this"
            }
            42.show {}
        """.trimIndent(), "show = literal (not a lambda)")
    }

    @Test
    fun `reject impl op as function call`() {
        shouldTypeError("""
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }
            let helper = {x} -> "hi"
            impl Show for Int where {
              show = helper {x = 1}
            }
            42.show {}
        """.trimIndent(), "show = function call (not a lambda)")
    }

    @Test
    fun `reject impl op as record literal`() {
        shouldTypeError("""
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }
            impl Show for Int where {
              show = {name = "bad"}
            }
            42.show {}
        """.trimIndent(), "show = record literal (not a lambda)")
    }
}
