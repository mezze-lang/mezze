package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `enumerate` fuses, and its `{i, val}` record is never built.
 *
 * THE GAP. `ChainCanonicalizer` had no `StageIR.Enumerate` and no mention of
 * enumerate at all, so a chain containing `.enumerate{}` could not be peeled:
 * the source classified as Opaque, nothing fused, and a RECORD was allocated
 * per element. Measured on bench/cases/fusegaps at n=2e7: 124247 ps/elem
 * against 93 for the shapes that fuse — 1412x, the worst cliff in the
 * inventory, and the records-are-syntax principle with a number attached.
 *
 * THE FIX. The stage carries nothing. The loop keeps a counter, the element
 * stays where it is, and a downstream `x.val` resolves to the element while
 * `x.i` resolves to the counter — so the record disappears at lowering instead
 * of being allocated and read back. Measured 124247 -> 556 ps/elem, 223x, with
 * the shapes that already fused unmoved (map+filter 89, map+map 639).
 *
 * WHERE IT DECLINES, and why that is safe rather than quiet: the record is live
 * from the enumerate until a MAP consumes it, and a filter or take passes it
 * through unchanged. If it is still live at a predicate or at the sink — whose
 * classifiers do not resolve its fields — the chain declines and the original,
 * record-building pipeline runs. A bare read of the parameter also declines,
 * because the record does not exist for a bare read to denote. Those cases are
 * correct and slow, which is the right way round; the last two tests pin their
 * VALUES so a future change cannot make them quietly wrong.
 *
 * WHY THESE VALUES. Resolving `.i` to the wrong local does not crash, it
 * returns a different number — so the `.i` cases use a source whose values
 * differ from their indices (`range{10,15}`), where reading the element in the
 * index's place gives 120 instead of 20.
 */
class EnumerateFusionTest {

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

    private fun run(body: String): Any =
        MezzeTestHelper.run("use std::collections::iter\n\n${body.trimIndent()}")

    @Test
    fun `a map reading val fuses and sees the element`() {
        // 0..4 -> *3 -> 0,3,6,9,12 -> 30.
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .enumerate {}
              .map { f = { x } -> x.val * 3 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """,
        )
        assertEquals(30L, out)
        assertTrue("FusedLoop" in emitted, "enumerate did not fuse; emitted: $emitted")
    }

    @Test
    fun `a map reading i sees the INDEX, not the element`() {
        // Values 10..14, indices 0..4. `x.i * 2` is 0,2,4,6,8 -> 20. Reading the
        // element there instead gives 20,22,24,26,28 -> 120.
        val out = run(
            """
            let main = {} -> iter::range { start = 10, end = 15 }
              .enumerate {}
              .map { f = { x } -> x.i * 2 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """,
        )
        assertEquals(20L, out)
        assertTrue("FusedLoop" in emitted, "emitted: $emitted")
    }

    @Test
    fun `the index counts elements that REACH the enumerate, not source positions`() {
        // A filter BEFORE the enumerate: survivors 0,2,4,6,8 get indices 0..4,
        // so `x.i * 10` sums to 100. Numbering source positions instead would
        // give 0,20,40,60,80 -> 200.
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 10 }
              .filter { p = { x } -> x % 2 == 0 }
              .enumerate {}
              .map { f = { x } -> x.i * 10 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """,
        )
        assertEquals(100L, out)
        assertTrue("FusedLoop" in emitted, "emitted: $emitted")
    }

    @Test
    fun `the index restarts per call, not across calls`() {
        // The counter is a loop local, so a second call must start from 0
        // again. Both calls sum 0+1+2+3+4 = 10, so 20 — a counter that leaked
        // between calls would give 10 + 35 = 45.
        val out = run(
            """
            let go = {} -> iter::range { start = 7, end = 12 }
              .enumerate {}
              .map { f = { x } -> x.i * 1 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            let main = {} -> go {} + go {}
            main {}
            """,
        )
        assertEquals(20L, out)
    }

    @Test
    fun `a predicate over the record declines, and keeps its answer`() {
        // A filter AFTER the enumerate reads the record, which no predicate
        // classifier resolves — so this declines and the real pipeline runs.
        // Indices 3,4 survive, values 3,4, mapped to 9 and 12 -> 21.
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .enumerate {}
              .filter { p = { x } -> x.i > 2 }
              .map { f = { x } -> x.val * 3 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """,
        )
        assertEquals(21L, out)
    }

    @Test
    fun `a record reaching the sink declines, and keeps its answer`() {
        // No map consumes the record, so it arrives at the sink. That declines
        // and the record-building pipeline runs — which is what `to_seq` of an
        // enumerate must do, since the records are the result.
        val out = run(
            """
            use std::collections::seq
            let main = {} -> seq::size {
              xs = iter::to_seq { it = iter::enumerate { it = iter::range { start = 0, end = 5 } } },
            }
            main {}
            """,
        )
        assertEquals(5L, out)
    }

    @Test
    fun `enumerate over an empty range folds to the initial value`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 0 }
              .enumerate {}
              .map { f = { x } -> x.i * 2 }
              .fold { init = 7, f = { acc, x } -> acc + x }
            main {}
            """,
        )
        assertEquals(7L, out)
    }
}
