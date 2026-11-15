package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.FusedChainIR
import com.mezze.language.truffleir.IfIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.transformChildren
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A pipeline chosen by an `if` must still fuse.
 *
 * `peelSource` has no case for an `IfIR`, so a helper that picks between two
 * pipelines left the source Opaque and the caller's whole chain ran
 * element-at-a-time. `bench/cases/dotprobe` k4 measured **43 416 ps/elem
 * against the fused 152 — 286x** — and the A/B on one CLI with
 * `MEZZE_CHAIN_IF_DISTRIBUTE=0` moved it from 360x its own build's control to
 * 0.95x, with `stages_cond` disappearing from the compiled output entirely.
 *
 * Such a helper is reached by neither of the two existing routes: the wrapper
 * inliner will not inline it, because its parameter is read in both branches
 * and the caller's argument is not cheap to duplicate; and
 * `peekThroughMonoWrapper` refuses it, because it accepts a body that is a
 * single call and nothing else.
 *
 * The transformation is sound because the branches are MUTUALLY EXCLUSIVE:
 * putting the operation into both duplicates the IR, not the work.
 */
class ChainIfDistributionLoweringTest {

    private fun lower(src: String): List<FunctionIR> =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        ).loweredFunctions.filter { it.sourceFile == "p.mz" }

    private fun walk(node: NodeIR, f: (NodeIR) -> Unit) {
        f(node)
        node.transformChildren { child -> walk(child, f); child }
    }

    /** Functions holding at least one fused chain. */
    private fun fusedFns(src: String): Int = lower(src).count { fn ->
        var found = false
        walk(fn.body) { if (it is FusedChainIR) found = true }
        found
    }

    @Test
    fun `a pipeline chosen by an if fuses in both branches`() {
        val src = """
            use std::collections::iter
            let stages = { xs, on } ->
              if on then iter::filter { it = iter::map { it = xs, f = { x } -> x * 3 }, p = { x } -> x > 2 }
              else xs
            let pipeline = { n } -> iter::fold {
              it = stages { xs = iter::range { start = 0, end = n }, on = n > 0 },
              init = 0, f = { acc, x } -> acc + x,
            }
        """
        assertTrue(fusedFns(src) >= 1, "the conditional pipeline did not fuse anywhere")
    }

    /**
     * The distributed form is an `if` whose BOTH branches carry the operation —
     * that is the shape, and asserting it keeps the test from passing on some
     * unrelated fusion elsewhere in the module.
     */
    @Test
    fun `the sink ends up inside both branches`() {
        val src = """
            use std::collections::iter
            let stages = { xs, on } ->
              if on then iter::map { it = xs, f = { x } -> x * 3 }
              else xs
            let pipeline = { n } -> iter::fold {
              it = stages { xs = iter::range { start = 0, end = n }, on = n > 0 },
              init = 0, f = { acc, x } -> acc + x,
            }
        """
        val fn = lower(src).single { it.name == "pipeline" }
        var bothBranchesFused = false
        walk(fn.body) { node ->
            if (node is IfIR) {
                var t = false; var e = false
                walk(node.thenBr) { if (it is FusedChainIR) t = true }
                walk(node.elseBr) { if (it is FusedChainIR) e = true }
                if (t && e) bothBranchesFused = true
            }
        }
        assertTrue(
            bothBranchesFused,
            "expected an `if` with a fused chain in each branch; body=${fn.body.toString().take(300)}",
        )
    }

    /**
     * CONTROL. Neither branch is a pipeline — both are opaque values arriving as
     * parameters — so there is no fusion to win and distributing would only
     * duplicate the operation into both arms. Growth with no payoff is a
     * regression no value test would notice.
     *
     * The first version of this control chose between two `iter::range` calls
     * through a helper, and FAILED: the helper is trivially inlinable, so the
     * `if` lands in the body legitimately, and its branches are ranges — which
     * ARE chains, so distributing them is right and both arms fuse. The control
     * has to make the branches genuinely unfusable to test the refusal.
     */
    @Test
    fun `an if over two opaque sources is left alone`() {
        val src = """
            use std::collections::iter
            let pipeline = { n, xs, ys } -> iter::fold {
              it = if n > 0 then xs else ys,
              init = 0, f = { acc, x } -> acc + x,
            }
        """
        val fn = lower(src).single { it.name == "pipeline" }
        var fused = 0
        walk(fn.body) { if (it is FusedChainIR) fused++ }
        assertTrue(
            fused == 0,
            "an if whose branches are opaque was distributed and fused: $fused",
        )
    }
}
