package com.mezze.interpreter.regression

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * An ability op called through the SCALAR ABI gets its arguments in the right
 * order.
 *
 * Ability-impl ops now take the scalar calling convention (W-P1, ~2.1x on a hot
 * lambda). Scalar means the call site drops the argument RECORD and passes flat
 * positional values, ordered by the callee's own param list. So the one way the
 * change could corrupt a program is an ordering disagreement between the names
 * registered for the op's hash and the op's compiled `paramNames` — and the
 * symptom would not be a crash, it would be a WRONG ANSWER.
 *
 * Every case here is therefore order-sensitive: swap any two arguments and the
 * expected value changes. Commutative operations would pass either way and are
 * deliberately avoided.
 */
class AbilityOpScalarArgOrderTest {

    @Test
    fun `two params written in reverse order at the call site`() {
        // Subtraction, so a transposition gives -1 instead of 1.
        val r = MezzeTestHelper.run(
            """
            type Cfg = { tag: Int }
            ability Diff for 'c where { diff: { it: 'c, a: Int, b: Int } -> Int }
            impl Diff for Cfg where { diff = { it, a, b } -> do
              let d = a - b
              let scaled = d * 1
              scaled + it.tag }

            let main = {} -> do
              let c: Cfg = { tag = 0 }
              c.diff { b = 2, a = 3 }
            main {}
            """.trimIndent(),
        )
        assertEquals(1L, r, "a=3, b=2 → 1; a transposition would give -1")
    }

    @Test
    fun `three params in scrambled order, with an order-sensitive body`() {
        // Positional weights: any permutation gives a different number.
        val r = MezzeTestHelper.run(
            """
            type Cfg = { tag: Int }
            ability Mix for 'c where { mix: { it: 'c, a: Int, b: Int, d: Int } -> Int }
            impl Mix for Cfg where { mix = { it, a, b, d } -> do
              let w = (a * 100) + (b * 10) + d
              let adjusted = w + it.tag
              adjusted }

            let main = {} -> do
              let c: Cfg = { tag = 0 }
              c.mix { d = 3, a = 1, b = 2 }
            main {}
            """.trimIndent(),
        )
        assertEquals(123L, r, "a=1, b=2, d=3 → 123; any permutation differs")
    }

    @Test
    fun `the receiver is not confused with a parameter named like it`() {
        // `it` is the receiver's slot. A param whose VALUE could be mistaken for
        // the receiver would show up as reading the wrong tag.
        val r = MezzeTestHelper.run(
            """
            type Cfg = { tag: Int }
            ability Pick for 'c where { pick: { it: 'c, other: Cfg } -> Int }
            impl Pick for Cfg where { pick = { it, other } -> do
              let mine = it.tag * 10
              let theirs = other.tag
              mine + theirs }

            let main = {} -> do
              let a: Cfg = { tag = 1 }
              let b: Cfg = { tag = 2 }
              a.pick { other = b }
            main {}
            """.trimIndent(),
        )
        assertEquals(12L, r, "receiver's tag is 1 (tens), the argument's is 2 (units)")
    }

    @Test
    fun `strings, where a transposition cannot arithmetically cancel`() {
        val r = MezzeTestHelper.run(
            """
            use std::str
            type Cfg = { sep: Str }
            ability Join3 for 'c where { join3: { it: 'c, x: Str, y: Str, z: Str } -> Str }
            impl Join3 for Cfg where { join3 = { it, x, y, z } -> do
              let head = "$(x)$(it.sep)$(y)"
              let full = "$(head)$(it.sep)$(z)"
              full }

            let main = {} -> do
              let c: Cfg = { sep = "-" }
              c.join3 { z = "c", x = "a", y = "b" }
            main {}
            """.trimIndent(),
        )
        assertEquals("a-b-c", r)
    }

    @Test
    fun `an op reached through a lambda that captures the receiver`() {
        // The exact shape W-P1 is about: the receiver is a capture, not a
        // constant, and the call is per element.
        val r = MezzeTestHelper.run(
            """
            use std::collections::seq
            type Cfg = { tag: Int }
            ability Enc for 'c where { enc: { it: 'c, a: Int, b: Int } -> Int }
            impl Enc for Cfg where { enc = { it, a, b } -> do
              let w = (a * 10) + b
              let adjusted = w + it.tag
              adjusted }

            let run_all: { xs: [Int], c: Cfg } -> Int = { xs, c } ->
              seq::fold {
                xs = seq::map { xs = xs, f = { x } -> c.enc { b = 1, a = x } },
                init = 0,
                f = { acc, x } -> acc + x,
              }

            let main = {} -> run_all { xs = [1, 2, 3], c = { tag = 0 } }
            main {}
            """.trimIndent(),
        )
        // enc(a=1,b=1)=11, enc(a=2,b=1)=21, enc(a=3,b=1)=31 → 63
        assertEquals(63L, r, "a and b transposed would give 1+2+3 weighted the other way")
    }

    @Test
    fun `a generic op dispatched through a dictionary still gets its order right`() {
        // A constrained caller passes the impl as a dict param. The op's
        // registered names include the synthetic dict params, and they must sit
        // AFTER the regular ones — the same layout compileFunctionBody builds.
        val r = MezzeTestHelper.run(
            """
            ability Weigh for 'c where { weigh: { it: 'c, a: Int, b: Int } -> Int }
            type Tens = { unused: Int }
            impl Weigh for Tens where { weigh = { it, a, b } -> do
              let w = (a * 10) + b
              w + it.unused }

            let apply_to: { v: 'c, a: Int, b: Int } with Weigh 'c -> Int = { v, a, b } ->
              v.weigh { a = a, b = b }

            let main = {} -> apply_to { v = { unused = 0 }, b = 4, a = 7 }
            main {}
            """.trimIndent(),
        )
        assertEquals(74L, r, "a=7, b=4 → 74; transposed would be 47")
    }

    @Test
    fun `two impls of one ability keep their own param orders`() {
        // Each impl is its own hash with its own paramNames. Registering them
        // under one key, or reusing one impl's order for the other, shows up here.
        val r = MezzeTestHelper.run(
            """
            ability Pair for 'c where { pair: { it: 'c, a: Int, b: Int } -> Int }
            type Big = { n: Int }
            type Small = { m: Int }
            impl Pair for Big where { pair = { it, a, b } -> do
              let w = (a * 1000) + b
              w + it.n }
            impl Pair for Small where { pair = { it, a, b } -> do
              let w = (b * 1000) + a
              w + it.m }

            let main = {} -> do
              let big: Big = { n = 0 }
              let small: Small = { m = 0 }
              let l = big.pair { b = 2, a = 1 }
              let r2 = small.pair { b = 2, a = 1 }
              (l * 100000) + r2
            main {}
            """.trimIndent(),
        )
        // Big weighs `a` heavily: a=1,b=2 → 1002.
        // Small weighs `b` heavily: b=2,a=1 → 2001.
        // Packed so one impl borrowing the other's order cannot coincide.
        assertEquals(1002L * 100000L + 2001L, r)
    }
}
