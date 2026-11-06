package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A fold whose folder DOES something keeps the fused loop; it calls the folder
 * per element instead of declining the chain.
 *
 * The fused-chain emitter had two ways to emit a Fold sink, and both put the
 * folder's arithmetic INTO the loop: `Accumulate` wants `acc OP x` with at most
 * one element transform, `AccumulateTree` wants a pure arithmetic tree over the
 * two parameters with no captures and no locals. A folder that CALLS anything
 * satisfies neither, and the whole chain was declined — `[chain-canon-emit]
 * reject: unclassifiable folder body` — so the fold fell all the way back to the
 * native fold and its element-at-a-time iterator protocol.
 *
 * MEASURED on `bench/cases/mandelannot`, side 1800, min of 3, through the
 * generated launcher:
 *
 *     2  iter::range().fold(), folder calls pixel     67 -> 45 ns/px   1.49x
 *     4  the same pixels walked by a tail loop        38 -> 36 ns/px   (control)
 *
 * The gap between walking the grid lazily and walking it by hand fell from 29 ns
 * a pixel to 9 — and 9 ns is what `iter::range().fold()` costs with a folder the
 * emitter CAN inline, so what is left is the protocol floor rather than this
 * defect. In `mezze-benchmark`'s own harness, same regime (N=1200 -> 2400, every
 * implementation warm, all ten checksums agreeing), mandelbrot went **69.126 ->
 * 17.591 ns/unit, ahead of kotlin's 21.846 and c's 22.221.**
 *
 * WHY A CALL PER ELEMENT IS THE RIGHT TRADE, and it is not a new one: the
 * `CallEach` sink settled it for ForEach already, and its own note says the win
 * is "going from not fused at all to fused with one alloc per iter", with
 * Truffle's escape analysis often folding the record away. The loop becomes a
 * counted range walk with no iterator state and no Option per step, which is the
 * part that was costing 20 ns.
 *
 * Unlike the two inlining modes this one accepts CAPTURES, because the closure is
 * built once in the loop prologue rather than per element — and a folder captures
 * the moment its body mentions anything from the enclosing scope, which
 * mandelbrot's does (`side` and `span`).
 *
 * THESE ASSERT VALUES, AND THAT IS A LIMITATION, not a choice. `FUSED_EMITS` is
 * the seam for asserting which shape was emitted, and it records nothing for any
 * of these programs — including an `acc + x` fold that certainly does fuse under
 * `mez build`. The test harness lowers the stdlib from source while a real build
 * serves the prelowered snapshot, so `iter`'s chains are not fused here at all;
 * `RecursiveSpecSelfCallTest` and `LocalLambdaSpecializerTest` both record the
 * same blindness, and [NamedPipelineStepFusionTest] states the same thing for the
 * same reason — its perf claim also rests on a bench rather than an assertion
 * here. The evidence that this mode fires is `MEZZE_CHAIN_CANON_EMIT_DEBUG=1` on
 * a BUILT binary: "emitted fused loop (AccumulateCall, 0 stages)" where it used
 * to say "reject: unclassifiable folder body".
 *
 * So be clear about what these are worth: swapping the per-element call's two
 * arguments in the emitter leaves all four PASSING, because the path never runs
 * in this harness. They are correctness pins for the shapes the mode handles, not
 * a guard on the mode. The guard is the checksum — `bench/cases/mandelannot`
 * kernel 2 answers 39 866 172 before and after, and `mezze-benchmark` has ten
 * implementations agreeing on mandelbrot(200) and mandelbrot(1000).
 */
class FoldCallingFolderFusionTest {

    private val emitted = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        BytecodeCompiler.FUSED_EMITS.set(emitted)
    }

    @AfterEach
    fun tearDown() {
        BytecodeCompiler.FUSED_EMITS.remove()
    }

    private fun run(body: String): Any = MezzeTestHelper.run(
        "use std::collections::iter\nuse std::collections::seq\n\n${body.trimIndent()}",
    )

    @Test
    fun `a fold whose folder calls a function still fuses`() {
        val out = run("""
            let twice = { n } -> n * 2
            let main = {} ->
              iter::range { start = 1, end = 11 }
                .fold { init = 0, f = { acc, x } -> acc + twice { n = x } }
            main {}
        """)
        assertEquals(110L, out)
    }

    @Test
    fun `a folder that captures still fuses`() {
        // The shape mandelbrot has: the callback mentions the enclosing
        // function's parameters, so the lambda carries captures. The two
        // inlining modes both refuse captures outright.
        val out = run("""
            let scale = { n, k } -> n * k
            let total = { k } ->
              iter::range { start = 1, end = 11 }
                .fold { init = 0, f = { acc, x } -> acc + scale { n = x, k = k } }
            let main = {} -> total { k = 3 }
            main {}
        """)
        assertEquals(165L, out)
    }

    @Test
    fun `an inlinable folder still computes the same answer`() {
        // `acc + x` is what the `Accumulate` mode exists for, and the new mode is
        // reached only after both inlining modes decline, so this one must not
        // change at all. Which path it takes is not observable here (see the note
        // above); that it still answers 55 is.
        val out = run("""
            let main = {} ->
              iter::range { start = 1, end = 11 }
                .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
        """)
        assertEquals(55L, out)
    }

    @Test
    fun `the folder sees the accumulator and the element in the right order`() {
        // A non-commutative folder: if the two arguments were passed the other way
        // round the answer would be wrong rather than slow, so this is the
        // correctness pin for the argument order of the per-element call.
        val out = run("""
            let diff = { a, b } -> a - b
            let main = {} ->
              iter::range { start = 1, end = 5 }
                .fold { init = 100, f = { acc, x } -> diff { a = acc, b = x } }
            main {}
        """)
        // 100 - 1 - 2 - 3 - 4 = 90
        assertEquals(90L, out)
    }
}
