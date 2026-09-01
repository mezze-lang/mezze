package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * #392: a method called on a receiver bound by a MATCH-ARM PATTERN must both
 * type-check AND dispatch at runtime, exactly like a receiver bound by `let` or
 * a param. Before the fix, the match-arm binding carried the raw unsolved
 * pattern var into method resolution (the do-block `let` path ran a local solve
 * first, so let-bound receivers worked but match-bound ones fell through to a
 * structural field access and failed to type-check at all).
 */
class MatchBoundReceiverMethodDispatchTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun `inherent method dispatches on a match-arm-bound receiver`() {
        val result = eval("""
            type Box 'a = | Box { val: 'a } |
            impl Box 'a where { get = { it } -> match it is Box { val = v } -> v }

            let via_match = { ob: Option (Box Int) } -> match ob is
              Some { val = inner } -> inner.get {}
              None -> 0

            let b = Box { val = 42 }
            via_match { ob = Some { val = b } }
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `inherent method dispatches on a single-ctor match-arm-bound receiver`() {
        val result = eval("""
            type Box 'a = | Box { val: 'a } |
            type Wrap 'a = | Wrap { inner: 'a } |
            impl Box 'a where { get = { it } -> match it is Box { val = v } -> v }

            let c1 = { w: Wrap (Box Int) } -> match w is
              Wrap { inner = b } -> b.get {}

            c1 { w = Wrap { inner = Box { val = 42 } } }
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `ability method dispatches on a match-arm-bound receiver`() {
        val result = eval("""
            ability Greet for 'a where { greet: { it: 'a } -> Str }
            type Dog = | Dog { name: Str } |
            impl Greet for Dog where { greet = { it } -> match it is Dog { name = n } -> n }

            let via_match = { ob: Option Dog } -> match ob is
              Some { val = d } -> d.greet {}
              None -> "none"

            via_match { ob = Some { val = Dog { name = "rex" } } }
        """)
        assertEquals("rex", result)
    }
}
