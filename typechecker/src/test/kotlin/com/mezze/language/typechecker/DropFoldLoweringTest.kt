package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.FusedChainIR
import com.mezze.language.truffleir.InlineBinaryOpIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.SourceIR
import com.mezze.language.truffleir.StageIR
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A `drop` applied straight to a counted range disappears into the range's
 * start.
 *
 * `range{start, end}` yields `start + i`, so skipping k elements is exactly
 * `range{start + k, end}`. `FusedLongPipeline` has no drop stage, so leaving
 * the stage in place sent the whole pipeline to the general scalar loop:
 * measured 658 ps/elem for `drop+map` against 88 for the same map alone, and
 * 84 after this fold.
 *
 * WHY THE IR AND NOT THE VALUE. The unfolded pipeline computes the same answer,
 * slowly, so a value test passes either way — `DropFoldTest` covers the
 * semantics, and this covers the fold actually happening.
 *
 * THE SECOND CASE IS THE IMPORTANT ONE. A drop with a filter below it counts
 * SURVIVORS, not range positions, so it must NOT fold: over 0..9, keeping odds
 * then dropping two leaves 5,7,9, while moving the start by two then filtering
 * leaves 3,5,7,9. A fold that ignored stage order would be a wrong answer.
 */
class DropFoldLoweringTest {

    private fun unwrap(n: NodeIR): NodeIR = if (n is BlockIR) unwrap(n.result) else n

    private fun chains(src: String): List<FusedChainIR> =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        ).loweredFunctions
            .filter { it.sourceFile == "p.mz" }
            .mapNotNull { unwrap(it.body) as? FusedChainIR }

    @Test
    fun `a drop directly on a range leaves no drop stage`() {
        val cs = chains(
            """
            use std::collections::iter
            let go = { hi } -> iter::range { start = 0, end = hi }
              .drop { n = 3 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            let main = {} -> go { hi = 10 }
            """,
        )
        assertTrue(cs.isNotEmpty(), "nothing fused, so the fold is untested")
        assertTrue(
            cs.none { c -> c.stages.any { it is StageIR.Drop } },
            "a Drop stage survived: " + cs.map { c -> c.stages.map { it::class.simpleName } },
        )
        // And it went into the start, which is now an addition rather than the
        // literal 0 the source was written with.
        assertTrue(
            cs.any { c -> (c.source as? SourceIR.RangeSeq)?.start is InlineBinaryOpIR },
            "the range start did not absorb the drop: " + cs.map { it.source },
        )
    }

    @Test
    fun `a drop after a filter keeps its stage`() {
        val cs = chains(
            """
            use std::collections::iter
            let go = { hi } -> iter::range { start = 0, end = hi }
              .filter { p = { x } -> x % 2 != 0 }
              .drop { n = 2 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            let main = {} -> go { hi = 10 }
            """,
        )
        assertTrue(cs.isNotEmpty(), "nothing fused, so the refusal is untested")
        assertTrue(
            cs.any { c -> c.stages.any { it is StageIR.Drop } },
            "the drop was folded past a filter, which changes the answer: " +
                cs.map { c -> c.stages.map { it::class.simpleName } },
        )
    }

    @Test
    fun `a drop on a materialised seq keeps its stage`() {
        // Not a counted range, so there is no start to move.
        val cs = chains(
            """
            use std::collections::seq
            let go = { xs } -> seq::fold {
              xs = seq::drop { xs = xs, n = 2 },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            let main = {} -> go { xs = [1, 2, 3, 4] }
            """,
        )
        if (cs.isNotEmpty()) {
            assertTrue(
                cs.any { c -> c.stages.any { it is StageIR.Drop } },
                "a seq drop should keep its stage: " +
                    cs.map { c -> c.stages.map { it::class.simpleName } },
            )
        }
    }
}
