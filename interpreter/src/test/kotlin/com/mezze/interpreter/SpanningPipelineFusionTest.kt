package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A pipeline SPLIT ACROSS A CALL must fuse, and must still read the values it
 * captured.
 *
 * THE SHAPE. A function returns a lazy stage and its caller supplies the sink:
 *
 *     let stage = { k } -> iter::map { it = …, f = { x } -> x * k }
 *     let main  = {} -> iter::fold { it = stage { k = 2 }, … }
 *
 * `ChainCanonicalizer` has to look through the call to find the stages, and the
 * stage lambda captures `k`. A `CaptureIR` names a frame SLOT, not a value, so
 * moving that lambda into the caller means re-expressing the capture there —
 * and when the caller passed a LITERAL the value lives in no slot at all. The
 * move was therefore refused, and refusing it lost the whole fusion: measured
 * on the lowered IR, this program produced no fused chain anywhere and ran
 * element-at-a-time, while the identical program with a non-capturing stage
 * (`x * 2`) fused. The capture was the only difference.
 *
 * Now the value is bound to a fresh local in the caller and the capture points
 * at that — the let-binding form of inlining — so the chain fuses with the
 * captured value intact. Two further things had to follow: the peel looks
 * through a CHAIN of forwarding wrappers, because a user function forwarding to
 * the stdlib function whose body is the native is two hops, not one; and the
 * binding is emitted around the chain rather than hoisted to the function's
 * entry, so an argument is still evaluated where it was written.
 *
 * WHAT THESE GUARD. Re-expressing a capture against a different frame is the
 * failure that does not announce itself: point it at the wrong slot and the
 * stage silently multiplies by whatever lives there. So every case pins an
 * answer that a misread slot would change — several pass TWO captured values of
 * different magnitude, so swapping them is visible, and one captures a value
 * the caller holds in a slot while another captures a literal, which take
 * different paths through the remap.
 */
class SpanningPipelineFusionTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun run(src: String): Any? = MezzeTestHelper.run(src)

    @Test
    fun `a captured literal survives the move into the caller`() {
        // 0..4 -> *2 -> 0,2,4,6,8 -> sum 20.
        assertEquals(
            20L,
            run(
                """
                use std::collections::iter
                let stage = { k } -> iter::map { it = iter::range { start = 0, end = 5 }, f = { x } -> x * k }
                let main = {} -> iter::fold { it = stage { k = 2 }, init = 0, f = { acc, x } -> acc + x }
                main {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `two captured values are not swapped`() {
        // Distinct magnitudes, so reading the wrong slot changes the answer:
        // (x * 10) + 1 for x in 0..4 -> 1,11,21,31,41 -> 105. Swapped
        // (x * 1) + 10 would give 60.
        assertEquals(
            105L,
            run(
                """
                use std::collections::iter
                let stage = { mul, add } ->
                  iter::map { it = iter::range { start = 0, end = 5 }, f = { x } -> x * mul + add }
                let main = {} -> iter::fold { it = stage { mul = 10, add = 1 }, init = 0, f = { acc, x } -> acc + x }
                main {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a captured value the caller holds in a slot still reads correctly`() {
        // The caller passes its own parameter rather than a literal, so the
        // capture is re-pointed at an existing slot instead of a new one —
        // the other branch of the remap.
        assertEquals(
            20L,
            run(
                """
                use std::collections::iter
                let stage = { k } -> iter::map { it = iter::range { start = 0, end = 5 }, f = { x } -> x * k }
                let go = { k } -> iter::fold { it = stage { k = k }, init = 0, f = { acc, x } -> acc + x }
                let main = {} -> go { k = 2 }
                main {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a captured filter threshold is applied to the right values`() {
        // keep x < lim after *10: 0,10,20,30 -> 60. A misread threshold either
        // keeps everything (100) or nothing (0).
        assertEquals(
            60L,
            run(
                """
                use std::collections::iter
                let stage = { mul, lim } -> iter::filter {
                  it = iter::map { it = iter::range { start = 0, end = 5 }, f = { x } -> x * mul },
                  p = { x } -> x < lim,
                }
                let main = {} -> iter::fold { it = stage { mul = 10, lim = 40 }, init = 0, f = { acc, x } -> acc + x }
                main {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a non-capturing spanning stage is unaffected`() {
        // The case that already fused; it must keep its answer.
        assertEquals(
            20L,
            run(
                """
                use std::collections::iter
                let stage = {} -> iter::map { it = iter::range { start = 0, end = 5 }, f = { x } -> x * 2 }
                let main = {} -> iter::fold { it = stage {}, init = 0, f = { acc, x } -> acc + x }
                main {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the same stage called twice with different captures stays distinct`() {
        // One materialised local per relocation; sharing one would make both
        // folds agree. 20 + 50 = 70, not 40 or 100.
        assertEquals(
            70L,
            run(
                """
                use std::collections::iter
                let stage = { k } -> iter::map { it = iter::range { start = 0, end = 5 }, f = { x } -> x * k }
                let sum_of = { k } -> iter::fold { it = stage { k = k }, init = 0, f = { acc, x } -> acc + x }
                let main = {} -> sum_of { k = 2 } + sum_of { k = 5 }
                main {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a spanning pipeline agrees with the inline form`() {
        val spanning = run(
            """
            use std::collections::iter
            let stage = { k } -> iter::map { it = iter::range { start = 0, end = 10 }, f = { x } -> x * k }
            let main = {} -> iter::fold { it = stage { k = 3 }, init = 0, f = { acc, x } -> acc + x }
            main {}
            """.trimIndent(),
        )
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        val inline = run(
            """
            use std::collections::iter
            let main = {} -> iter::fold {
              it = iter::map { it = iter::range { start = 0, end = 10 }, f = { x } -> x * 3 },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            main {}
            """.trimIndent(),
        )
        assertEquals(135L, spanning)
        assertEquals(inline, spanning)
    }
}
