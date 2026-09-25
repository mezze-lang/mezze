package com.mezze.interpreter.comptime

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * `Embeddable` is the marker ability gating what `comptime` may return: a value
 * whose meaning is fully determined by data it carries, so it survives the
 * compiler process ending and is rebuilt in a fresh one.
 *
 * These tests drive it through an ordinary `with Embeddable 'a` constraint —
 * `comptime` itself lands in a later task — so what is under test is the
 * derivation rule in `canDeriveAbilityStructurally`, not the comptime plumbing.
 */
class EmbeddableAbilityTest {

    /** A function whose argument must be Embeddable. Stands in for `comptime`. */
    private val harness = """
        use std::abilities::embed::{Embeddable}

        let keep: { val: 'a } with Embeddable 'a -> 'a = { val } -> val
    """.trimIndent()

    private fun accepts(body: String): Boolean =
        try {
            MezzeTestHelper.run("$harness\n${body.trimIndent()}")
            true
        } catch (e: Throwable) {
            val m = e.message.orEmpty()
            // Only an Embeddable rejection counts as "not embeddable"; anything
            // else is a broken test, so surface it rather than reading it as a
            // negative result.
            if (!m.contains("Embeddable")) throw AssertionError("unrelated failure: $m", e)
            false
        }

    // ── data all the way down: accepted ───────────────────────────────────

    @Test
    @Timeout(120)
    fun `primitives are embeddable`() {
        assertTrue(accepts("""let main = {} -> keep { val = 1 }"""), "Int")
        assertTrue(accepts("""let main = {} -> keep { val = 1.5 }"""), "Float")
        assertTrue(accepts("""let main = {} -> keep { val = "s" }"""), "Str")
        assertTrue(accepts("""let main = {} -> keep { val = True }"""), "Bool")
        assertTrue(accepts("""let main = {} -> keep { val = {} }"""), "Unit")
        // Mezze has no char literal — a Char comes from `char::from_int`, which
        // hands back `Option Char`. So this covers Char and Option together.
        assertTrue(accepts("""
            use std::char
            let main = {} -> keep { val = char::from_int { code = 65 } }
        """), "Option Char")
    }

    @Test
    @Timeout(120)
    fun `records and variants are embeddable when their components are`() {
        assertTrue(accepts("""let main = {} -> keep { val = { x = 1, y = "s" } }"""), "record")
        assertTrue(
            accepts("""let main = {} -> keep { val = { a = { b = { c = 1 } } } }"""),
            "nested record",
        )
        assertTrue(accepts("""
            type Colour = | Red, Green, Blue |
            let main = {} -> keep { val = Red }
        """), "variant, no payload")
        assertTrue(accepts("""
            type Wrap = | Boxed { v: Int }, Empty |
            let main = {} -> keep { val = Boxed { v = 1 } }
        """), "variant with payload")
    }

    @Test
    @Timeout(120)
    fun `immutable containers are embeddable when their elements are`() {
        assertTrue(accepts("""
            use std::collections::seq
            let main = {} -> keep { val = [1, 2, 3] }
        """), "seq")
        assertTrue(accepts("""
            use std::collections::seq
            let main = {} -> keep { val = [{ x = 1 }, { x = 2 }] }
        """), "seq of records")
        assertTrue(accepts("""
            use std::collections::seq
            let main = {} -> keep { val = [[1], [2]] }
        """), "nested seq")
    }

    // ── not data: rejected ────────────────────────────────────────────────

    @Test
    @Timeout(120)
    fun `a function is not embeddable`() {
        assertTrue(!accepts("""let main = {} -> keep { val = { x } -> x + 1 }"""))
    }

    @Test
    @Timeout(120)
    fun `native handles are not embeddable`() {
        assertTrue(!accepts("""
            use std::effects::async::{ Async, LoomAsync }
            let main = {} -> perform LoomAsync in
              keep { val = Async.spawn { f = {} -> 1 } }
        """), "Promise")
    }

    /** The recursive half: a container or record is only as embeddable as what
     *  it holds. This is what stops a bad value reaching the reifier. */
    @Test
    @Timeout(120)
    fun `rejection is recursive through containers and records`() {
        assertTrue(!accepts("""
            use std::collections::seq
            let main = {} -> keep { val = [{ x } -> x] }
        """), "seq of functions")
        assertTrue(!accepts("""
            let main = {} -> keep { val = { f = { x } -> x } }
        """), "record holding a function")
    }

    // ── newtypes opt in, exactly as they do for Eq ────────────────────────

    @Test
    @Timeout(120)
    fun `a newtype is not embeddable until its author opts in`() {
        assertTrue(!accepts("""
            newtype Meters = Int
            let main = {} -> keep { val = Meters.(5) }
        """), "no impl yet")

        assertTrue(accepts("""
            use std::abilities::embed::{Embeddable}
            newtype Meters = Int
            impl Embeddable for Meters where { }
            let main = {} -> keep { val = Meters.(5) }
        """), "opted in")
    }
}
