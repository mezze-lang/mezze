package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A fold body that is a pure expression fuses, however deep it is.
 *
 * THE GAP. `classifyFolderBody` matches `acc OP x` plus one level of element
 * transform (`acc OP x*k`) — which is two of the three nodes in
 * `acc + x * 2 - 1`. That body was refused, and a refused folder declines the
 * WHOLE chain, so the map before it stopped fusing too. Measured on
 * bench/cases/fusegaps at n=2e7: 14025 ps/elem against 93 for the shapes that
 * fuse, a 159x cliff on any fold that is not a bare `acc + x` — which is most
 * folds anyone writes.
 *
 * THE FIX. Such a body needs no shape at all: it is a pure arithmetic tree over
 * the folder's two parameters and integer literals, so the loop emits it as
 * written with the parameter reads rewritten to the accumulator and element
 * locals. Every operator is the same overflow-checked operation the unfused
 * folder would run, there are no calls, no allocation and no effects, so the
 * inline form computes exactly what calling the folder computes. It measured
 * 14025 -> 1448 ps/elem, 9.7x, and left the shapes that already fused untouched
 * (map+filter 91, map+map 641 before and after).
 *
 * WHY THE FUSION ASSERTIONS. The unfused path computes the SAME answer, just
 * slowly — so a value test alone passes with or without this and would not have
 * caught the cliff. `FUSED_EMITS` records which loop was emitted, which is the
 * property that actually changed; `assertTrue("FusedLoop" in emitted)` is what
 * fails when the folder is refused.
 *
 * WHY THESE VALUES. Emitting a tree inline is a semantic rewrite, and the way
 * it goes wrong is quiet: swap the accumulator and the element and `acc * 2 + x`
 * still returns a number, just the wrong one. So the cases below are
 * order-sensitive and non-commutative on purpose, each with its answer worked
 * out by hand.
 */
class CompoundFoldFusionTest {

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
    fun `a three-node fold body fuses and keeps its answer`() {
        // 0..4 mapped by *3 gives 0,3,6,9,12; folding `acc + x*2 - 1` adds
        // 2v - 1 per element, so 2*30 - 5 = 55.
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x * 3 }
              .fold { init = 0, f = { acc, x } -> acc + x * 2 - 1 }
            main {}
            """,
        )
        assertEquals(55L, out)
        assertTrue("FusedLoop" in emitted, "compound fold did not fuse; emitted: $emitted")
    }

    @Test
    fun `a deeper body with division fuses`() {
        // (x*3 + 1) / 2 over x = 0..4 is 0, 2, 3, 5, 6 under truncating integer
        // division, summing to 16. Division is in the grammar precisely because
        // it is NOT commutative and NOT associative, so a mis-emitted tree
        // shows up here rather than cancelling out.
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .fold { init = 0, f = { acc, x } -> acc + (x * 3 + 1) / 2 }
            main {}
            """,
        )
        assertEquals(16L, out)
        assertTrue("FusedLoop" in emitted, "deep fold did not fuse; emitted: $emitted")
    }

    @Test
    fun `the accumulator is not swapped with the element`() {
        // `acc * 2 + x` from init 1 over 0..4: 2, 5, 12, 27, 58. Reading the
        // element where the accumulator belongs gives 1, 3, 7, ... — a number
        // either way, which is why this case exists. The FolderShape doc records
        // the same inversion returning 13 where 19 was right.
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .fold { init = 1, f = { acc, x } -> acc * 2 + x }
            main {}
            """,
        )
        assertEquals(58L, out)
        assertTrue("FusedLoop" in emitted, "emitted: $emitted")
    }

    @Test
    fun `a bare non-commutative fold still fuses as before`() {
        // `acc - x` was already matched by the shape classifier; it must keep
        // both its answer and its fusion. ((((0-0)-1)-2)-3)-4 = -10.
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .fold { init = 0, f = { acc, x } -> acc - x }
            main {}
            """,
        )
        assertEquals(-10L, out)
        assertTrue("FusedLoop" in emitted || emitted.any { it.contains("FusedLongPipeline") },
            "simple fold stopped fusing; emitted: $emitted")
    }

    @Test
    fun `a modulo in the body keeps its answer, though it does not fuse yet`() {
        // acc + x % 3 over 0..5: 0,1,2,0,1,2 -> 6.
        //
        // MEASURED TO DECLINE: nothing is emitted for this chain, so `%` does
        // not reach the tree as an inline op here — on the evidence because the
        // operand needs a dictionary, which makes the folder CAPTURE, and the
        // tree path refuses captures (a capture addresses the lambda's own
        // capture array and needs lifting first). The one-level shape
        // classifier does support a captured operand, so `acc + x * k` is
        // unaffected; a DEEPER body with a capture declines and runs unfused.
        // Asserting the value is therefore the honest test, and the gap is
        // recorded in PERF-LOG rather than papered over.
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 6 }
              .fold { init = 0, f = { acc, x } -> acc + x % 3 }
            main {}
            """,
        )
        assertEquals(6L, out)
    }

    @Test
    fun `a compound fold over a filtered chain keeps both stages`() {
        // The point of fixing the folder: a refused folder declined the whole
        // chain, stages included. keep x < 4 -> 0,1,2,3, then acc + x*2 - 1
        // adds 2x - 1 four times: 2*6 - 4 = 8.
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 10 }
              .filter { p = { x } -> x < 4 }
              .fold { init = 0, f = { acc, x } -> acc + x * 2 - 1 }
            main {}
            """,
        )
        assertEquals(8L, out)
        assertTrue("FusedLoop" in emitted, "emitted: $emitted")
    }

    @Test
    fun `an impure fold body is declined, not mis-emitted`() {
        // A body that calls something is outside the grammar: it must fall back
        // to the real folder rather than be emitted inline. The answer is the
        // only thing that matters here — `int::min` of the accumulator and the
        // element, folded over 0..4 from init 2, stays 0 once it reaches 0.
        val out = run(
            """
            use std::int
            let main = {} -> iter::range { start = 0, end = 5 }
              .fold { init = 2, f = { acc, x } -> int::min { l = acc, r = x } }
            main {}
            """,
        )
        assertEquals(0L, out)
    }
}
