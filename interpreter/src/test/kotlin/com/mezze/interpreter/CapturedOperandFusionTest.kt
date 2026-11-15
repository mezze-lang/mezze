package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A CAPTURE INSIDE A COMPOUND OPERAND, in a fused chain.
 *
 * `BytecodeCompiler.isParamFree` decided whether a stage's right-hand side can
 * be emitted inline in the fused loop. It asked the wrong question — whether
 * the tree mentions the CALLBACK'S OWN PARAMETER — and answered yes for every
 * other `ReadLocalIR` and for `ReadCaptureIR`. Those are FRAME-RELATIVE: the
 * node comes from the callback's body, where index 0 is the callback's first
 * capture, while the fused loop runs in the ENCLOSING frame, where index 0 is
 * whatever that function put there.
 *
 * So `{val} -> val > half + 1` fused with the capture read left exactly as
 * written, and the loop compared the element against the enclosing frame's slot
 * 0. For a one-argument native HOF callback that slot holds the ARGUMENT
 * RECORD, which surfaced two ways:
 *
 *  - LOUDLY, as `Cannot add MezzeRecord and Long`, when the capture had
 *    arithmetic around it; and
 *  - SILENTLY, as the wrong answer, when it did not — `set::filter` with such a
 *    predicate returned an EMPTY set where half the elements should have
 *    survived, and `mapsurface` row 26 reported a checksum of 0 rather than
 *    failing.
 *
 * A bare capture was never affected: `liftCaptureRhs` rewrites it to the
 * enclosing slot it was captured from. The hole was a capture one level DOWN,
 * which that lift did not walk into. It walks now, through arithmetic, so these
 * shapes keep fusing instead of merely getting the right answer slowly.
 *
 * WHAT THESE TESTS ASSERT is the VALUE, never the plan. A fusion bug that
 * produces a correct answer by declining to fuse is not a failure; one that
 * produces 0 is. Every case below is a shape whose answer is wrong — not
 * missing — without the fix, which is why they are values and not timings.
 *
 * VERIFIED AGAINST THE ORIGINAL CODE: restoring both halves — the lift walking
 * only a bare capture AND `isParamFree` accepting frame-relative reads — fails
 * seven of these ten. The three that stay green are the ones that reach the
 * right answer by another route, and they are here to keep it that way: the
 * BARE capture (the lift always handled it), the LITERAL composite (frame
 * independent), and the FOLD (`classifyBinaryFolder` lifts its own element rhs
 * and declines when it cannot, so that path was never wrong). Either half of
 * the fix alone makes the other seven correct — the lift by expressing the
 * capture, `isParamFree` by declining the fusion — so the lift is what keeps
 * these shapes FAST and `isParamFree` is what makes a shape the lift cannot
 * express decline instead of answering wrongly.
 */
class CapturedOperandFusionTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    /** 1..200 in a set, and a seq of the same, for the stage under test. */
    private val prelude = """
        use std::collections::set
        use std::collections::seq
        use std::collections::iter

        let build = { i, s } -> if i <= 0 then s
          else build { i = i - 1, s = set::insert { s = s, x = i } }
    """

    // ── FILTER predicates: a capture inside the comparison's rhs ──

    @Test
    fun `filter against a capture plus a literal`() {
        val src = """
            $prelude
            let main = {} -> do
              let s = build { i = 200, s = set::empty {} }
              let half = 100
              set::size { s = set::filter { s = s, p = { val } -> val > half + 1 } }
            main {}
        """
        assertEquals(99L, eval(src)) // 102..200
    }

    @Test
    fun `filter against a capture scaled and divided`() {
        val src = """
            $prelude
            let main = {} -> do
              let s = build { i = 200, s = set::empty {} }
              let half = 100
              set::size { s = set::filter { s = s, p = { val } -> val > half * 2 / 2 } }
            main {}
        """
        assertEquals(100L, eval(src)) // 101..200
    }

    /** The capture on the LEFT of its own arithmetic, so the walk must not
     *  assume which side it sits on. */
    @Test
    fun `filter against a literal times a capture`() {
        val src = """
            $prelude
            let main = {} -> do
              let s = build { i = 200, s = set::empty {} }
              let half = 100
              set::size { s = set::filter { s = s, p = { val } -> val > 2 * half } }
            main {}
        """
        assertEquals(0L, eval(src)) // nothing in 1..200 exceeds 200
    }

    /** Two different captures in one operand. */
    @Test
    fun `filter against two captures`() {
        val src = """
            $prelude
            let main = {} -> do
              let s = build { i = 200, s = set::empty {} }
              let lo = 60
              let hi = 40
              set::size { s = set::filter { s = s, p = { val } -> val > lo + hi } }
            main {}
        """
        assertEquals(100L, eval(src)) // 101..200
    }

    // ── MAP bodies: the same gate, reached from the other matcher ──

    @Test
    fun `map adds a compound capture to each element`() {
        val src = """
            $prelude
            let main = {} -> do
              let s = build { i = 200, s = set::empty {} }
              let xs = set::to_seq { s = s }
              let half = 100
              seq::sum { xs = seq::map { xs = xs, f = { x } -> x + (half + 0) } }
            main {}
        """
        assertEquals(40100L, eval(src)) // 20100 + 200 * 100
    }

    @Test
    fun `map multiplies by a compound capture`() {
        val src = """
            $prelude
            let main = {} -> do
              let xs = iter::to_seq { it = iter::range { start = 1, end = 11 } }
              let two = 2
              seq::sum { xs = seq::map { xs = xs, f = { x } -> x * (two + 1) } }
            main {}
        """
        assertEquals(165L, eval(src)) // (1..10) * 3 = 55 * 3
    }

    // ── A fold, where the capture reaches a third matcher ──

    @Test
    fun `fold adds a compound capture per element`() {
        val src = """
            $prelude
            let main = {} -> do
              let mul = 3
              iter::fold {
                it = iter::range { start = 1, end = 5 }, init = 0,
                f = { acc, x } -> acc + x * (mul + 1)
              }
            main {}
        """
        assertEquals(40L, eval(src)) // (1+2+3+4) * 4
    }

    // ── Capture-free composites, which were always right and must stay so ──

    @Test
    fun `a literal composite still filters correctly`() {
        val src = """
            $prelude
            let main = {} -> do
              let s = build { i = 200, s = set::empty {} }
              set::size { s = set::filter { s = s, p = { val } -> val > 100 + 1 } }
            main {}
        """
        assertEquals(99L, eval(src))
    }

    /** A BARE capture, the shape the original lift already handled. */
    @Test
    fun `a bare capture still filters correctly`() {
        val src = """
            $prelude
            let main = {} -> do
              let s = build { i = 200, s = set::empty {} }
              let hundred = 100
              set::size { s = set::filter { s = s, p = { val } -> val > hundred } }
            main {}
        """
        assertEquals(100L, eval(src))
    }

    /** Arithmetic on the ELEMENT, with a captured threshold: both halves of the
     *  comparison are non-trivial at once. */
    @Test
    fun `element arithmetic against a compound capture`() {
        val src = """
            $prelude
            let main = {} -> do
              let s = build { i = 200, s = set::empty {} }
              let fifty = 50
              set::size { s = set::filter { s = s, p = { val } -> val * 2 > fifty * 2 + 100 } }
            main {}
        """
        assertEquals(100L, eval(src)) // val > 100, so 101..200
    }
}
