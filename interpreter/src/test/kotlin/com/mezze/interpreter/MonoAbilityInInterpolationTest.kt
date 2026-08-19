package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * HOLE E (#225) regression: an ability method called on a polymorphic receiver
 * INSIDE a string interpolation INSIDE a HOF callback, where the enclosing
 * generic is monomorphized at a concrete element type.
 *
 * `Monomorphizer.rewriteCalls` had no `TypedStringLit` case, so it never recursed
 * into interpolation subexprs. The `x.tag {}` call inside `"$(acc)$(x.tag {})"`
 * was therefore never rewritten to a direct op-hash call — its receiver stayed a
 * VarType, forcing dict-passing, and a hash collision with the dict-passing
 * lambda collapsed the two into one FunctionIR with mismatched capture arity →
 * ArrayIndexOutOfBoundsException at runtime. The fix recurses into interpolation
 * exprs (which also dissolves the hash collision).
 */
class MonoAbilityInInterpolationTest {

    private val prelude = """
        use std::collections::seq
        ability Tag for 'self where {
          tag: { it: 'self } -> Str
        }
        impl Tag for Int where { tag = { it } -> "int" }
        impl Tag for Str where { tag = { it } -> "str" }
    """.trimIndent()

    @Test fun `ability method in an interpolation in a HOF callback under mono`() {
        // The exact #225 program. Was AIOOBE; must produce "intintint".
        val src = "$prelude\n" + """
            let describe_all: { xs: ['a] } with Tag 'a -> Str = { xs } ->
              seq::fold { xs = xs, init = "", f = { acc, x } -> "${'$'}(acc)${'$'}(x.tag {})" }
            describe_all { xs = [1, 2, 3] }
        """.trimIndent()
        assertEquals("intintint", MezzeTestHelper.run(src))
    }

    @Test fun `dispatches to the correct impl for the Str element type`() {
        val src = "$prelude\n" + """
            let describe_all: { xs: ['a] } with Tag 'a -> Str = { xs } ->
              seq::fold { xs = xs, init = "", f = { acc, x } -> "${'$'}(acc)${'$'}(x.tag {})" }
            describe_all { xs = ["a", "b"] }
        """.trimIndent()
        assertEquals("strstr", MezzeTestHelper.run(src))
    }

    @Test fun `bare ability method in a HOF callback still folds`() {
        // Control: no interpolation — this path always worked; guard against
        // over-fixing. `fold` returns the last tag string.
        val src = "$prelude\n" + """
            let last_tag: { xs: ['a] } with Tag 'a -> Str = { xs } ->
              seq::fold { xs = xs, init = "", f = { acc, x } -> x.tag {} }
            last_tag { xs = [1, 2, 3] }
        """.trimIndent()
        assertEquals("int", MezzeTestHelper.run(src))
    }

    @Test fun `ability method in an interpolation in the outer body under mono`() {
        // Control: mono fires, interpolation, but NOT inside a HOF callback.
        val src = "$prelude\n" + """
            let describe_one: { x: 'a } with Tag 'a -> Str = { x } -> "tag=${'$'}(x.tag {})"
            describe_one { x = 7 }
        """.trimIndent()
        assertEquals("tag=int", MezzeTestHelper.run(src))
    }
}
