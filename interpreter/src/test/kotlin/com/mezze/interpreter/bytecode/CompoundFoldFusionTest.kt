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

    /**
     * A TWO-NODE compound body over a FILTERED chain. `acc + x * x` is the
     * shape FusedLongPipeline takes in its own slot rather than as an inline
     * tree, and it is the one the test above cannot reach: `acc + x * 2 - 1` is
     * three nodes, so it goes to the generic loop's tree path and never touches
     * the slot. The slot is where the stage ORDER lives.
     *
     * THE BUG THIS PINS. The element transform belongs to the SINK — in
     * `acc + x * x` the square is part of the fold, not a pipeline stage — so
     * the filter must see the RAW element. Riding in the map slot it ran FIRST,
     * because `filterFirst` is only set in the MapInline branch and no explicit
     * map stage was present to set it. The filter then tested `x * x > 5`:
     * x >= 3 rather than x >= 6, and the fold returned 280 where the same
     * pipeline with the square written as an explicit map returns 230.
     *
     * Hand-worked: keep x > 5 over 0..10 -> 6,7,8,9; squares 36+49+64+81 = 230.
     * The wrong order gives 3..9 squared = 280, so the two answers cannot be
     * confused with each other.
     */
    @Test
    fun `a two-node compound fold body runs after the filter, not before it`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 10 }
              .filter { p = { x } -> x > 5 }
              .fold { init = 0, f = { acc, x } -> acc + x * x }
            main {}
            """,
        )
        assertEquals(230L, out)
    }

    /**
     * THE SAME ORDER, SIZED PAST THE VECTOR PIPELINE. `FlpSimd` is a THIRD site
     * that decides stage order, and it only engages at `SIMD_THRESHOLD = VL * 4`
     * — 16 or 32 elements depending on the vector width. The test above runs
     * ten, so it cannot reach it, and a stage-order bug that lives only in the
     * vector loops is CORRECT small and wrong large. That exact split has
     * happened here before: `map(*3).filter(<n)` was right below ~16-32
     * elements and wrong above, because both vector pipelines took a
     * `filterFirst` flag and never read it.
     *
     * The oracle is computed HERE, in Kotlin, rather than by a second Mezze
     * pipeline — an equality between two Mezze routes passes if both are wrong
     * the same way.
     *
     * `filter(x > 100)` makes the two orders far apart rather than adjacent:
     * filtering the raw element keeps 101.., filtering the SQUARE keeps 11..,
     * since `x * x > 100` from x = 11.
     */
    @Test
    fun `the stage order holds past the SIMD threshold`() {
        val expected = (101..4095).sumOf { it.toLong() * it.toLong() }
        val wrongOrder = (11..4095).sumOf { it.toLong() * it.toLong() }
        assertTrue(expected != wrongOrder, "the two orders must be distinguishable")
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 4096 }
              .filter { p = { x } -> x > 100 }
              .fold { init = 0, f = { acc, x } -> acc + x * x }
            main {}
            """,
        )
        assertEquals(expected, out)
    }

    /**
     * The same pipeline with the square as an EXPLICIT map, which takes a
     * different route — an explicit map in the slot makes the compound folder
     * refuse, so this one does not fuse through the slot at all. It is here to
     * state the expected answer independently of the path that computes it: if
     * these two ever disagree, one of them is wrong regardless of which.
     */
    @Test
    fun `the explicit-map spelling of the same pipeline agrees`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 10 }
              .filter { p = { x } -> x > 5 }
              .map { f = { x } -> x * x }
              .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """,
        )
        assertEquals(230L, out)
    }

    // ──────── the transform when an explicit map holds the slot ────────

    /**
     * `map(f).fold(acc + x * k)` — a one-op element transform with an explicit
     * map already in the pipeline.
     *
     * THE WORST ROW MEASURED ON THIS BRANCH, and it looked like the best. The
     * transform used to `return null` on finding the slot taken, which did not
     * merely decline the fast path: the chain then hit the blanket refusal and
     * fell back to the REAL folder, unfused. `bench/cases/foldshape` at n=2e7
     * read 13 863 ps/elem with 80 young collections against 95 and 4 —
     * **145.9x**, and 151.1x again at 6e7, with both in-case controls at
     * exactly 1.0x. For comparison the SAME pipeline with a DEEPER body reads
     * 724: a one-op transform was 19x slower than a two-op one, a cliff
     * pointing the wrong way.
     *
     * It now appends as the LAST map stage, which is where a sink-side
     * transform belongs anyway. 0..4 mapped by `* 3` gives 0,3,6,9,12 and the
     * folder doubles each, so 2 * 30 = 60.
     */
    @Test
    fun `an element transform fuses even when an explicit map holds the slot`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x * 3 }
              .fold { init = 0, f = { acc, x } -> acc + x * 2 }
            main {}
            """,
        )
        assertEquals(60L, out)
        assertTrue("FusedLongRangePipeline+extraMaps" in emitted, "emitted: $emitted")
    }

    /**
     * THE ORDER IS PINNED BY THE ANSWERS. The map runs first and the transform
     * second, so `map(*3)` then `*2` is 6x and `map(*2)` then `*3` is also 6x —
     * which would hide a swap. These use ADD against MUL instead, where the two
     * orders disagree: `(x+1)*2` over 0..4 is 2+4+6+8+10 = 30, while `(x*2)+1`
     * is 1+3+5+7+9 = 25.
     */
    @Test
    fun `the map runs before the element transform, not after`() {
        assertEquals(30L, run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x + 1 }
              .fold { init = 0, f = { acc, x } -> acc + x * 2 }
            main {}
            """,
        ))
        assertEquals(25L, run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x * 2 }
              .fold { init = 0, f = { acc, x } -> acc + x + 1 }
            main {}
            """,
        ))
    }

    /** A SELF op as the transform, behind an explicit map: (x+1)^2 over 0..4 = 55. */
    @Test
    fun `a self op transform fuses behind an explicit map`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x + 1 }
              .fold { init = 0, f = { acc, x } -> acc + x * x }
            main {}
            """,
        )
        assertEquals(55L, out)
        assertTrue("FusedLongRangePipeline+extraMaps" in emitted, "emitted: $emitted")
    }

    /** TWO explicit maps and a transform: ((x+1)*2) then *3 over 0..4 = 90. */
    @Test
    fun `a transform appends after several maps`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x + 1 }
              .map { f = { x } -> x * 2 }
              .fold { init = 0, f = { acc, x } -> acc + x * 3 }
            main {}
            """,
        )
        assertEquals(90L, out)
        assertTrue("FusedLongRangePipeline+extraMaps" in emitted, "emitted: $emitted")
    }

    /**
     * A FILTER BEFORE the map. The transform still runs last, and the filter
     * still sees the RAW element: keep x > 2 of 0..4 -> 3,4; map +1 -> 4,5;
     * double -> 8,10 = 18. Filtering any later value would keep a different
     * set.
     */
    @Test
    fun `a filter before the map still sees the raw element`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .filter { p = { x } -> x > 2 }
              .map { f = { x } -> x + 1 }
              .fold { init = 0, f = { acc, x } -> acc + x * 2 }
            main {}
            """,
        )
        assertEquals(18L, out)
    }

    /**
     * A FILTER AFTER the map. The transform belongs at the sink, so it must run
     * after the filter — but the extras block runs BEFORE the second filter
     * test, so this cannot be expressed and must be REFUSED rather than
     * reordered. map +1 over 0..4 -> 1..5; keep > 2 -> 3,4,5; double -> 24.
     * Running the transform before the filter would keep `2x > 2`, i.e. from
     * x=1, and answer 28.
     */
    @Test
    fun `a filter after the map refuses rather than reordering`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x + 1 }
              .filter { p = { x } -> x > 2 }
              .fold { init = 0, f = { acc, x } -> acc + x * 2 }
            main {}
            """,
        )
        assertEquals(24L, out)
        assertTrue("FusedLongRangePipeline+extraMaps" !in emitted,
            "a filter after the map must not ride the extras; emitted: $emitted")
    }

    /**
     * SIZED PAST THE VECTOR THRESHOLD, because the extras block is applied by
     * `FlpSimd` lanewise as well and that is a separate implementation. The
     * oracle is computed in Kotlin.
     */
    @Test
    fun `an element transform behind a map holds past the SIMD threshold`() {
        val n = 4099
        val expected = (0 until n).sumOf { (it.toLong() + 1L) * 2L }
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = $n }
              .map { f = { x } -> x + 1 }
              .fold { init = 0, f = { acc, x } -> acc + x * 2 }
            main {}
            """,
        )
        assertEquals(expected, out)
        assertTrue("FusedLongRangePipeline+extraMaps" in emitted, "emitted: $emitted")
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
