package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.FusedChainIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.SinkIR
import com.mezze.language.truffleir.transformChildren
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * `size` over a chain becomes a fused chain with a Count sink; a bare `size`
 * does not.
 *
 * WHY THIS TEST AND NOT A VALUE TEST. `SizeChainSinkTest` checks the answers,
 * and it passes with the sink DISABLED — both paths count correctly, one of
 * them slowly. That is the rule for every perf change here: the old path
 * computes the same answer, so only the lowered shape tells them apart.
 *
 * MEASURED, A/B on one CLI with `MEZZE_SIZE_SINK=0`, `ds_matrix` at N=1e6:
 * `seq::map`+size 9 247 376 → 6 151 ns, `seq::filter`+size 7 567 659 →
 * 208 724, with `seq::fold` unchanged as the control.
 *
 * THE SECOND CASE IS THE ONE THAT MATTERS. `size` on a materialised collection
 * is O(1), and routing it through a counting sink would make it O(n) — a
 * regression dressed as an optimisation, and one no value test would catch. The
 * pass refuses when the peeled chain has no stages, and this pins that.
 */
class SizeChainSinkLoweringTest {

    private fun lower(src: String): List<FunctionIR> =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        ).loweredFunctions

    private fun count(node: NodeIR, f: (NodeIR) -> Unit) {
        f(node)
        node.transformChildren { child -> count(child, f); child }
    }

    /** Fused chains whose sink counts, in this module's own functions. */
    private fun countSinkChains(src: String): Int {
        var n = 0
        for (fn in lower(src).filter { it.sourceFile == "p.mz" }) {
            count(fn.body) { if (it is FusedChainIR && it.sink === SinkIR.Count) n++ }
        }
        return n
    }

    @Test
    fun `size over a stage fuses with a counting sink`() {
        val src = """
            use std::collections::seq
            let main = { n } -> do
              let xs = seq::range { start = 0, end = n }
              xs.map { f = { x } -> x * 2 }.size {}
        """
        assertTrue(countSinkChains(src) >= 1,
            "size over a map did not fuse: " + countSinkChains(src))
    }

    @Test
    fun `size over a filter fuses too`() {
        val src = """
            use std::collections::seq
            let main = { n } -> do
              let xs = seq::range { start = 0, end = n }
              xs.filter { p = { x } -> x % 2 == 0 }.size {}
        """
        assertTrue(countSinkChains(src) >= 1,
            "size over a filter did not fuse: " + countSinkChains(src))
    }

    @Test
    fun `a BARE size is left as it was`() {
        // The refusal, and the reason the pass has one: this call is O(1) on a
        // materialised collection. Fusing it would make it a traversal.
        val src = """
            use std::collections::seq
            let main = { n } -> do
              let xs = seq::range { start = 0, end = n }
              seq::size { xs = xs }
        """
        assertTrue(countSinkChains(src) == 0,
            "a bare size was turned into a counting traversal: " + countSinkChains(src))
    }
}
