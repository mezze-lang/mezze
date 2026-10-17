package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Compound fold bodies — `{acc, x} -> acc <OP> f(x)` — through the fused loop.
 *
 * The element transform `f(x)` is injected into the pipeline as a map stage.
 * Three things went wrong there and each is pinned below.
 *
 * 1. `BuiltinSumRangeLong` sums a range directly, so an element transform has
 *    nowhere to go; the fast path took the fold anyway and dropped it, making
 *    `fold(0, {acc, x} -> acc + x * 3)` return the plain sum.
 * 2. `Reduce` and `Scan` carry only the combining op, so they dropped it too.
 *    Reduce cannot take the transform as a map stage even in principle: it
 *    seeds the accumulator with the first element UNTRANSFORMED.
 * 3. A folder with captures never fused at all, so `acc + x * mul` fell back
 *    to the generic loop while the identical `acc + x * 3` fused — worth 19%
 *    of throughput on a request handler doing one such fold.
 */
class CompoundFolderFusionTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ── The element transform must not be dropped ──

    @Test
    fun `fold over a range with a literal rhs`() {
        val src = """
            use std::collections::iter

            let main = {} -> iter::fold {
              it = iter::range { start = 1, end = 5 }, init = 0,
              f = {acc, x} -> acc + x * 3
            }
            main {}
        """.trimIndent()
        assertEquals(30L, eval(src)) // (1+2+3+4) * 3
    }

    @Test
    fun `fold over a range with a non-zero init`() {
        val src = """
            use std::collections::iter

            let main = {} -> iter::fold {
              it = iter::range { start = 1, end = 5 }, init = 100,
              f = {acc, x} -> acc + x * 3
            }
            main {}
        """.trimIndent()
        assertEquals(130L, eval(src))
    }

    @Test
    fun `reduce with a compound body`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              let r = iter::reduce { it = iter::range { start = 1, end = 5 }, f = {acc, x} -> acc + x * x }
              match r is
                Some { val } -> val
                None -> -1
            main {}
        """.trimIndent()
        assertEquals(30L, eval(src)) // 1, 1+4, 5+9, 14+16
    }

    @Test
    fun `reduce seeds the accumulator with the first element untransformed`() {
        val src = """
            use std::collections::iter

            let main = {} -> do
              let r = iter::reduce { it = iter::range { start = 2, end = 4 }, f = {acc, x} -> acc + x * x }
              match r is
                Some { val } -> val
                None -> -1
            main {}
        """.trimIndent()
        // [2, 3] -> acc = 2, then 2 + 3*3 = 11. Injecting the transform as a
        // map stage ahead of the reduce would give 4 + 9 = 13.
        assertEquals(11L, eval(src))
    }

    @Test
    fun `scan with a compound body`() {
        val src = """
            use std::collections::seq

            let main = {} -> do
              let r = seq::scan { xs = [1, 2, 3], init = 0, f = {acc, x} -> acc + x * x }
              seq::fold { xs = r, init = 0, f = {acc, x} -> acc + x }
            main {}
        """.trimIndent()
        assertEquals(20L, eval(src)) // accumulators 1, 5, 14
    }

    // ── A captured right-hand side ──

    @Test
    fun `captured rhs over a range`() {
        val src = """
            use std::collections::iter

            let run: { mul: Int } -> Int = { mul } ->
              iter::fold { it = iter::range { start = 1, end = 5 }, init = 0, f = {acc, x} -> acc + x * mul }

            let main = {} -> run { mul = 3 }
            main {}
        """.trimIndent()
        assertEquals(30L, eval(src))
    }

    @Test
    fun `captured rhs over a seq`() {
        val src = """
            use std::collections::seq

            let run: { mul: Int } -> Int = { mul } ->
              seq::fold { xs = [1, 2, 3, 4], init = 0, f = {acc, x} -> acc + x * mul }

            let main = {} -> run { mul = 3 }
            main {}
        """.trimIndent()
        assertEquals(30L, eval(src))
    }

    @Test
    fun `captured rhs is read from the right frame slot`() {
        val src = """
            use std::collections::iter

            ## `mul` is the LAST of several locals, so it does not sit at frame
            ## slot 0. Lifting the capture to the wrong slot reads `decoy` (or
            ## the parameter) instead and the total comes out wrong.
            let run: { n: Int } -> Int = { n } -> do
              let decoy = 999
              let other = 7
              let mul = 3
              iter::fold { it = iter::range { start = 1, end = n }, init = 0, f = {acc, x} -> acc + x * mul }

            let main = {} -> run { n = 5 }
            main {}
        """.trimIndent()
        assertEquals(30L, eval(src))
    }

    @Test
    fun `captured rhs with a non-commutative element op`() {
        val src = """
            use std::collections::iter

            let run: { d: Int } -> Int = { d } ->
              iter::fold { it = iter::range { start = 10, end = 14 }, init = 0, f = {acc, x} -> acc + x / d }

            let main = {} -> run { d = 3 }
            main {}
        """.trimIndent()
        // 10/3 + 11/3 + 12/3 + 13/3 = 3 + 3 + 4 + 4
        assertEquals(14L, eval(src))
    }

    @Test
    fun `captured rhs through two lambda levels`() {
        val src = """
            use std::collections::iter

            let outer: { mul: Int } -> { n: Int } -> Int = { mul } -> { n } ->
              iter::fold { it = iter::range { start = 1, end = n }, init = 0, f = {acc, x} -> acc + x * mul }

            let main = {} -> do
              let f = outer { mul = 3 }
              f { n = 5 }
            main {}
        """.trimIndent()
        assertEquals(30L, eval(src))
    }

    @Test
    fun `captured rhs with a subtracting fold op`() {
        val src = """
            use std::collections::iter

            let run: { mul: Int } -> Int = { mul } ->
              iter::fold { it = iter::range { start = 1, end = 5 }, init = 100, f = {acc, x} -> acc - x * mul }

            let main = {} -> run { mul = 3 }
            main {}
        """.trimIndent()
        assertEquals(70L, eval(src))
    }

    @Test
    fun `compound body written element-first with a non-commutative fold op`() {
        val src = """
            use std::collections::iter

            let main = {} -> iter::fold {
              it = iter::range { start = 1, end = 5 }, init = 0,
              f = {acc, x} -> x * 3 - acc
            }
            main {}
        """.trimIndent()
        // acc = 0; 1*3-0=3; 2*3-3=3; 3*3-3=6; 4*3-6=6. Reading the operands in
        // the other order computes `acc - x*3` and gives -30.
        assertEquals(6L, eval(src))
    }

    // ── Captured shapes that must NOT fuse, and must still be right ──

    @Test
    fun `capture as the fold operand rather than the element rhs`() {
        val src = """
            use std::collections::iter

            let run: { bump: Int } -> Int = { bump } ->
              iter::fold { it = iter::range { start = 1, end = 5 }, init = 0, f = {acc, x} -> acc + bump }

            let main = {} -> run { bump = 3 }
            main {}
        """.trimIndent()
        assertEquals(12L, eval(src)) // four elements, +3 each, element ignored
    }

    @Test
    fun `capture applied to the accumulator`() {
        val src = """
            use std::collections::iter

            let run: { k: Int } -> Int = { k } ->
              iter::fold { it = iter::range { start = 1, end = 4 }, init = 1, f = {acc, x} -> acc * k + x }

            let main = {} -> run { k = 2 }
            main {}
        """.trimIndent()
        // acc: 1 -> 1*2+1=3 -> 3*2+2=8 -> 8*2+3=19
        assertEquals(19L, eval(src))
    }

    @Test
    fun `an explicit map stage ahead of a compound fold`() {
        val src = """
            use std::collections::iter

            let main = {} -> iter::fold {
              it = iter::map { it = iter::range { start = 1, end = 5 }, f = {x} -> x + 1 },
              init = 0,
              f = {acc, x} -> acc + x * 3
            }
            main {}
        """.trimIndent()
        // elements 2,3,4,5 -> (2+3+4+5) * 3. The pipeline carries one map stage,
        // so this must fall back rather than drop either transform.
        assertEquals(42L, eval(src))
    }
}
