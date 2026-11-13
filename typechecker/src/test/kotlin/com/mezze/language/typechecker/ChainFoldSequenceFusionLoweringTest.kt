package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.CallNativeIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.FusedChainIR
import com.mezze.language.truffleir.SinkIR
import com.mezze.language.truffleir.SourceIR
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A fold over `iter::chain` is rewritten into two folds, and BOTH HALVES MUST
 * STILL FUSE.
 *
 * WHY THIS TEST EXISTS, and why `ChainFoldSequenceTest` could not stand in for
 * it. That suite checks the rewrite's SEMANTICS — order, seed, emptiness — and
 * it passed throughout a 15.6x regression, because the rewrite stayed correct
 * and merely stopped fusing. A correctness test cannot tell a fused loop from
 * an interpreted walk that computes the same number. This one asserts the
 * lowering, which is the only place the difference is visible.
 *
 * WHAT WENT WRONG. `trySequenceChainFold` rebuilds the two folds with
 * `CallNativeIR(name = node.name, ...)` and nothing else. That sufficed while
 * `isFoldOp` asked the NAME, because the name is copied. When the
 * name-to-decision refactor made `isFoldOp` read `opKind`/`ownerKind`, the
 * rebuilt nodes carried neither — so each half answered `opKind == null`, was
 * not recognised as a fold, and did not fuse. `chain+fold(0..10M)` went from
 * 554 ps an element to ~8 600, and returned to 540 once the constructor
 * carried them.
 *
 * THE GENERAL LESSON: replacing a name check with a carried decision makes
 * every CONSTRUCTOR responsible for carrying it, and one that forgets builds a
 * node every carried-decision predicate silently declines. The name survived
 * copying precisely because it was redundant.
 *
 * THE SHAPE ASSERTED HERE is the rewrite's own: `fold(b, fold(a, init))`, so
 * the inner fold is the OUTER fold's `init`. Navigating that explicitly rather
 * than scanning the tree keeps the test honest about what it is checking.
 */
class ChainFoldSequenceFusionLoweringTest {

    private fun lower(src: String): List<FunctionIR> {
        val r = TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        )
        return r.loweredFunctions
    }

    private fun localBodies(src: String) =
        lower(src).filter { it.sourceFile == "p.mz" }.map { it.body }

    private val chainFold = """
        use std::collections::iter
        let main = {} -> iter::fold {
          it = iter::chain {
            a = iter::range { start = 0, end = 50 },
            b = iter::range { start = 50, end = 100 },
          },
          init = 0,
          f = { acc, x } -> acc + x,
        }
    """

    /** The outer half: a fused chain over `b`'s range. */
    private fun outerFused(): FusedChainIR {
        val outer = localBodies(chainFold).filterIsInstance<FusedChainIR>().firstOrNull()
        assertNotNull(
            outer,
            "the chain-fold did not fuse at all. Zero fused chains means the " +
                "rebuilt folds carry no opKind, so `isFoldOp` declines them — " +
                "see ChainCanonicalizer.trySequenceChainFold.",
        )
        return outer!!
    }

    @Test
    fun `the outer half of the rewritten chain-fold is a fused range`() {
        val outer = outerFused()
        assertTrue(
            outer.source is SourceIR.RangeSeq,
            "outer source is ${outer.source::class.simpleName}, expected RangeSeq",
        )
    }

    /**
     * THE INNER HALF, which is the one that matters: it sits in the outer
     * fold's `init`, and it is what a reader would most easily forget to check.
     * Before the fix this was an unfused `__native_iter_fold` call.
     */
    @Test
    fun `the inner half is fused too, not an unfused native call`() {
        val sink = outerFused().sink
        assertTrue(sink is SinkIR.Fold, "outer sink is ${sink::class.simpleName}")
        val init = (sink as SinkIR.Fold).init
        assertTrue(
            init is FusedChainIR,
            "the inner fold is ${init::class.simpleName}, not a FusedChainIR" +
                (if (init is CallNativeIR) " (${init.name}, opKind=${init.opKind})" else "") +
                ". The rewrite produced it and the fuser then refused it.",
        )
    }

    /**
     * THE CAUSE, asserted directly rather than only through its symptom: no
     * fold native may survive without its `opKind`.
     *
     * Checked on the outer fold's `init` chain specifically, because that is
     * where an un-carried node lands. A rewrite may restructure the pipeline
     * however it likes, but a node it builds has to carry the decisions later
     * passes read — `name` alone is no longer enough.
     */
    @Test
    fun `no rebuilt fold is left carrying a null opKind`() {
        val init = (outerFused().sink as? SinkIR.Fold)?.init
        if (init is CallNativeIR) {
            assertTrue(
                init.opKind != null,
                "the rebuilt inner fold `${init.name}` carries opKind=null, so every " +
                    "carried-decision predicate declines it",
            )
        }
    }
}
