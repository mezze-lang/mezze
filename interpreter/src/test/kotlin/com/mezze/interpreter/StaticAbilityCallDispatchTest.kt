package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * HOLE F (#226): the documented STATIC ability-call form `Ability.method { it = v, ... }`
 * (e.g. `Eq.eq { it = 42, r = 45 }`, `Default.default {}`) must dispatch to the impl for
 * the receiver's type.
 *
 * It was mis-dispatched: `buildMethodCall` injected the ability NAME (a nullary variant)
 * as the receiver, colliding with the args' real `it`. For built-ins the receiver TYPE
 * also stayed a VarType, routing to the wrong dispatch. Result: `Eq.eq { it = 42, r = 42 }`
 * returned `false` (silent wrong), and user abilities crashed ("Cannot add MezzeVariant").
 * Fix (TypedAstBuilder.buildMethodCall): when the args already carry the receiver field,
 * use that value as the receiver (both the field-access whose TYPE drives built-in
 * dispatch, and the wrapped arg record) instead of the ability-name variant.
 */
class StaticAbilityCallDispatchTest {

    // ── built-in abilities ───────────────────────────────────────────────

    @Test fun `Eq eq static call on equal Ints is true`() {
        // The silent-wrong case: returned false before the fix.
        assertEquals(true, MezzeTestHelper.run("use std::abilities::compare::{ Eq }\nEq.eq { it = 42, r = 42 }"))
    }

    @Test fun `Eq eq static call on unequal Ints is false`() {
        assertEquals(false, MezzeTestHelper.run("use std::abilities::compare::{ Eq }\nEq.eq { it = 42, r = 45 }"))
    }

    @Test fun `Eq eq static call dispatches on Str`() {
        assertEquals(true, MezzeTestHelper.run("use std::abilities::compare::{ Eq }\nEq.eq { it = \"hi\", r = \"hi\" }"))
        assertEquals(false, MezzeTestHelper.run("use std::abilities::compare::{ Eq }\nEq.eq { it = \"hi\", r = \"lo\" }"))
    }

    @Test fun `Ord lt and gt static calls dispatch correctly`() {
        assertEquals(true, MezzeTestHelper.run("use std::abilities::compare::{ Ord }\nOrd.lt { it = 1, r = 2 }"))
        assertEquals(false, MezzeTestHelper.run("use std::abilities::compare::{ Ord }\nOrd.lt { it = 2, r = 1 }"))
        assertEquals(true, MezzeTestHelper.run("use std::abilities::compare::{ Ord }\nOrd.gt { it = 5, r = 2 }"))
    }

    // ── user-defined abilities ───────────────────────────────────────────

    private val dbl = """
        ability Dbl for 'self where {
          twice: { it: 'self } -> 'self
        }
        impl Dbl for Int where { twice = { it } -> it + it }
    """.trimIndent()

    @Test fun `user ability static call dispatches and reads the argument`() {
        // Was a crash ("Cannot add MezzeVariant and MezzeVariant") — the body's `it`
        // bound to the Dbl variant instead of 5.
        assertEquals(10L, MezzeTestHelper.run("$dbl\nDbl.twice { it = 5 }"))
    }

    @Test fun `user ability static call with extra args`() {
        val src = """
            ability Combine for 'self where {
              combine: { it: 'self, other: 'self } -> 'self
            }
            impl Combine for Int where { combine = { it, other } -> it + other }
            Combine.combine { it = 3, other = 4 }
        """.trimIndent()
        assertEquals(7L, MezzeTestHelper.run(src))
    }

    // ── method-style and operator controls (must remain correct) ─────────

    @Test fun `method-style call still works`() {
        assertEquals(true, MezzeTestHelper.run("use std::abilities::compare::{ Eq }\n(42).eq { r = 42 }"))
        assertEquals(10L, MezzeTestHelper.run("$dbl\n(5).twice {}"))
    }

    @Test fun `operator desugaring still works`() {
        assertEquals(true, MezzeTestHelper.run("42 == 42"))
        assertEquals(true, MezzeTestHelper.run("1 < 2"))
    }
}
