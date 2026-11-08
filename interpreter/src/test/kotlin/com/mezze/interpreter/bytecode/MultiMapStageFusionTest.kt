package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * More than ONE `map` stage in a fused pipeline.
 *
 * THE GAP. `FusedLongPipeline`'s spec carried a single map slot and
 * `tryBuildFusedLongPipeline` refused the whole fast path on meeting a second
 * map — `if (mapOp >= 0) return null` — so the pipeline fell to the generic
 * bytecode loop. `bench/cases/iterstage` is a pure stage-count ladder (no
 * filter, so every kernel folds exactly n elements, and each stage is an add);
 * at n=2e7 and again at 6e7 it reads, against the generic loop:
 *
 *     two stages    564 -> 425 ps/elem  (1.33x) · 532 -> 405  (1.31x)
 *     three stages  643 -> 423          (1.52x) · 620 -> 411  (1.51x)
 *     four stages   747 -> 751          (0.99x) · 718 -> 727  (0.99x)
 *
 * with all four controls flat to within 4% and identical checksums. Four stages
 * is unchanged because it is over the cap and keeps the loop it had.
 *
 * WHAT THE REWRITE IS. Maps two and later ride an `extraOps` int — 5 bits a
 * stage, op+1 in four and a self flag in the fifth — with their literals in a
 * parallel `long[]`. The OPS have to be in the int: the first attempt put them
 * in the array and measured SLOWER than the loop it replaced (two stages
 * 536 -> 891, four 790 -> 2424), because a `@ConstantOperand` array is a
 * constant REFERENCE and not constant CONTENTS, so `flpOp`'s switch stayed a
 * runtime switch and every element paid a dispatch per stage.
 *
 * WHY THESE CASES. Composing stages is a semantic rewrite and the way it fails
 * is quiet — `(x * 3) + 1` and `(x + 1) * 3` are both numbers. So every case
 * below is order-sensitive, with its answer worked out by hand, and asserts
 * which LOOP ran: a value test alone passes on the fallback, which computes the
 * same answer slowly and would prove nothing about the new path.
 */
class MultiMapStageFusionTest {

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

    private fun assertFusedWithExtras() =
        assertTrue("FusedLongRangePipeline+extraMaps" in emitted,
            "the extra map stages did not fuse; emitted: $emitted")

    /** 0..4 by `* 3` then `+ 1`: 1,4,7,10,13 = 35. Reversed it would be 45. */
    @Test
    fun `two maps compose in order, multiply then add`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x * 3 }
              .map { f = { x } -> x + 1 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """,
        )
        assertEquals(35L, out)
        assertFusedWithExtras()
    }

    /** The same two stages the other way round: (x+1)*3 over 0..4 = 45. */
    @Test
    fun `two maps compose in order, add then multiply`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x + 1 }
              .map { f = { x } -> x * 3 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """,
        )
        assertEquals(45L, out)
        assertFusedWithExtras()
    }

    /** Three stages, the most the cap allows: ((x+1)*2)-3 over 0..4 = 15. */
    @Test
    fun `three maps fuse and keep their order`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x + 1 }
              .map { f = { x } -> x * 2 }
              .map { f = { x } -> x - 3 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """,
        )
        assertEquals(15L, out)
        assertFusedWithExtras()
    }

    /**
     * A SELF op as the second stage. `x * x` takes no operand at all, so it
     * rides the self flag rather than a literal — and a literal of 0 is what
     * sits in its array slot, which would compute `x * 0` if the flag were
     * dropped. (x+1)² over 0..4 = 1+4+9+16+25 = 55.
     */
    @Test
    fun `a self op as an extra stage squares the element, it does not zero it`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x + 1 }
              .map { f = { x } -> x * x }
              .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """,
        )
        assertEquals(55L, out)
        assertFusedWithExtras()
    }

    /**
     * A filter BEFORE both maps. The extras all run together immediately after
     * the primary slot, so the filter has to be on one side of the whole group;
     * here it is `filterFirst` and sees the RAW element. Keep x > 2 over 0..4
     * -> 3,4; then (x*3)+1 -> 10,13 = 23. Filtering the transformed value would
     * keep everything from x = 1 and give a different number.
     */
    @Test
    fun `a filter before two maps sees the raw element`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .filter { p = { x } -> x > 2 }
              .map { f = { x } -> x * 3 }
              .map { f = { x } -> x + 1 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """,
        )
        assertEquals(23L, out)
        assertFusedWithExtras()
    }

    /**
     * A filter BETWEEN two maps cannot be expressed by one extras block, so it
     * must be REFUSED rather than reordered. (x*3) over 0..4 is 0,3,6,9,12;
     * keeping > 5 leaves 6,9,12; then +1 gives 7,10,13 = 30. Running the filter
     * before both maps would keep x > 5 of 0..4, which is nothing, and answer 0;
     * running it after both would keep (x*3)+1 > 5, i.e. 7,10,13 plus 4, = 34.
     * All three answers differ.
     */
    @Test
    fun `a filter between two maps is refused, not reordered`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x * 3 }
              .filter { p = { x } -> x > 5 }
              .map { f = { x } -> x + 1 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """,
        )
        assertEquals(30L, out)
        assertTrue("FusedLongRangePipeline+extraMaps" !in emitted,
            "a filter between maps must not ride the extras block; emitted: $emitted")
    }

    /**
     * Over the cap. Four map stages exceed `FLP_MAX_EXTRA_MAPS`, which is set
     * where the measurement stopped paying, so the pipeline keeps the generic
     * loop — and must still be RIGHT. (((x+1)+2)+3)+4 = x+10 over 0..4 = 60.
     */
    @Test
    fun `four maps exceed the cap and stay correct on the fallback`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x + 1 }
              .map { f = { x } -> x + 2 }
              .map { f = { x } -> x + 3 }
              .map { f = { x } -> x + 4 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """,
        )
        assertEquals(60L, out)
        assertTrue("FusedLongRangePipeline+extraMaps" !in emitted,
            "over the cap must not fuse the extras; emitted: $emitted")
    }

    /**
     * An extra stage whose operand is a RUNTIME value, not a literal. A dynamic
     * operand needs a bytecode child and the emitter's child list is fixed, so
     * it is refused — and the answer must survive the refusal. k is 2, so
     * (x*3)+2 over 0..4 is 2,5,8,11,14 = 40.
     */
    @Test
    fun `an extra stage with a runtime operand is refused and stays correct`() {
        val out = run(
            """
            let main = { k } -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x * 3 }
              .map { f = { x } -> x + k }
              .fold { init = 0, f = { acc, x } -> acc + x }
            main { k = 2 }
            """,
        )
        assertEquals(40L, out)
    }

    /**
     * The single-map case must be untouched: it keeps the config slot and its
     * bytecode child, which is what lets it vectorise. `bench/cases/iterstage`
     * puts the one-stage control at 0.96x and 0.99x across the change, and
     * turning SIMD off moves that row from 75 to 292 ps — so this path carries
     * a 3.9x that the extras block does not get, and must not be disturbed.
     */
    @Test
    fun `one map still takes the plain pipeline, with no extras`() {
        val out = run(
            """
            let main = {} -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x * 3 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            main {}
            """,
        )
        assertEquals(30L, out)
        assertTrue("FusedLongRangePipeline" in emitted, "emitted: $emitted")
        assertTrue("FusedLongRangePipeline+extraMaps" !in emitted,
            "a single map must not allocate an extras block; emitted: $emitted")
    }
}
