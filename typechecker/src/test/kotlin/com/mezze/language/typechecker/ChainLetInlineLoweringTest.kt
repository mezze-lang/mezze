package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.CallNativeIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.FusedChainIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.transformChildren
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Naming a pipeline step must not cost the pipeline.
 *
 * `ChainCanonicalizer` recognises a fusable chain by walking an EXPRESSION TREE,
 * and had no case for a slot read — so the moment a step was given a name the
 * walk hit a `ReadLocalIR`, classified the source as Opaque, and the whole
 * pipeline ran element-at-a-time. `bench/cases/fusecross` kernel 2 lowered with
 * `FusedChainIR=0` and residual `__native_iter_map`/`_filter`/`_fold` where
 * kernel 1 — the same pipeline as one expression — fused.
 *
 * WHY A LOWERING TEST AND NOT A VALUE TEST. Both spellings compute the same
 * answer; one of them element-at-a-time. Only the lowered shape tells them
 * apart, which is the rule every perf change here follows — see
 * [SizeChainSinkLoweringTest].
 *
 * Each test states what it measures and what it is a control FOR, because the
 * pass is only correct if it leaves the second kind alone.
 */
class ChainLetInlineLoweringTest {

    private fun lower(src: String): List<FunctionIR> =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        ).loweredFunctions

    private fun walk(node: NodeIR, f: (NodeIR) -> Unit) {
        f(node)
        node.transformChildren { child -> walk(child, f); child }
    }

    private fun ours(src: String) = lower(src).filter { it.sourceFile == "p.mz" }

    /**
     * Fused chains in this module's functions, counting only the REAL ones.
     *
     * A `FusedChainIR` carries the original un-fused expression as its
     * `fallback`, and that fallback holds a nested `FusedChainIR` of its own, so
     * a naive walk double-counts and a naive search for residual natives finds
     * the fallback's and reports failure on a chain that fused perfectly. This
     * counts top-level chains only.
     */
    private fun fusedChains(src: String): Int {
        var n = 0
        for (fn in ours(src)) {
            var found: FusedChainIR? = null
            walk(fn.body) { if (found == null && it is FusedChainIR) found = it }
            if (found != null) n++
        }
        return n
    }

    /** Functions whose body still holds a chain op OUTSIDE any fused chain. */
    private fun unfusedStageFns(src: String): List<String> {
        val out = mutableListOf<String>()
        for (fn in ours(src)) {
            var fused = false
            walk(fn.body) { if (it is FusedChainIR) fused = true }
            if (fused) continue
            var stage = false
            walk(fn.body) {
                if (it is CallNativeIR &&
                    (it.name == "__native_iter_fold" || it.name == "__native_seq_fold")
                ) stage = true
            }
            if (stage) out.add(fn.name ?: "<anon>")
        }
        return out
    }

    private val letBound = """
        use std::collections::iter
        let pipeline = { n } -> do
          let src = iter::range { start = 0, end = n }
          let tripled = iter::map { it = src, f = { x } -> x * 3 }
          let big = iter::filter { it = tripled, p = { x } -> x > 2 }
          iter::fold { it = big, init = 0, f = { acc, x } -> acc + x }
    """

    @Test
    fun `a pipeline whose every step is named still fuses`() {
        assertTrue(
            fusedChains(letBound) >= 1,
            "the let-bound pipeline did not fuse; unfused: ${unfusedStageFns(letBound)}",
        )
    }

    @Test
    fun `no sink is left running element-at-a-time`() {
        assertEquals(
            emptyList<String>(), unfusedStageFns(letBound),
            "a fold outside any fused chain means the pipeline runs per element",
        )
    }

    /**
     * THE CROSS-FUNCTION HALF. `peekThroughMonoWrapper` refuses a callee
     * outright when `target.localSlots.isNotEmpty()`, so ONE named intermediate
     * inside a helper took its caller's whole pipeline down with it. Emptying
     * the callee's locals is what stops that refusal firing, so the two helpers
     * below must lower to the SAME thing.
     */
    @Test
    fun `a helper with a named intermediate lowers like one without`() {
        fun helperBody(withLet: Boolean): String {
            val body = if (withLet) {
                """
                let stages = { xs } -> do
                  let tripled = iter::map { it = xs, f = { x } -> x * 3 }
                  iter::filter { it = tripled, p = { x } -> x > 2 }
                """
            } else {
                """
                let stages = { xs } -> iter::filter {
                  it = iter::map { it = xs, f = { x } -> x * 3 },
                  p = { x } -> x > 2,
                }
                """
            }
            return "use std::collections::iter\n" + body.trimIndent() +
                "\nlet pipeline = { n } -> iter::fold {\n" +
                "  it = stages { xs = iter::range { start = 0, end = n } },\n" +
                "  init = 0, f = { acc, x } -> acc + x,\n}\n"
        }

        val withLet = ours(helperBody(true)).single { it.name == "stages" }
        val without = ours(helperBody(false)).single { it.name == "stages" }

        // NOT `localSlots.isEmpty()`. The substituted slot stays DECLARED on
        // purpose — reclaiming it would renumber every later slot — so the
        // invariant the cross-function peek needs is that the body READS no slot
        // outside the parameters. Asserting the stronger thing failed here and
        // sent me to fix the peek's condition, which was testing declarations
        // rather than reads.
        val paramCount = withLet.paramSlots.size
        var readsLocal = false
        walk(withLet.body) {
            if (it is com.mezze.language.truffleir.ReadLocalIR && it.slot >= paramCount) {
                readsLocal = true
            }
        }
        assertTrue(
            !readsLocal,
            "the helper body still reads a local slot, so the cross-function peek refuses it",
        )
        assertEquals(
            without.body.toString().replace(Regex("src=SourceInfo\\([^)]*\\)"), ""),
            withLet.body.toString().replace(Regex("src=SourceInfo\\([^)]*\\)"), ""),
            "the two helpers differ only by a `let` and must lower identically",
        )
    }

    /**
     * CONTROL. A step read TWICE is not a single-use binding, and substituting
     * it would run the pipeline twice — for an eager `seq` stage, the whole
     * traversal twice. The binding must survive.
     */
    @Test
    fun `a step used twice keeps its binding`() {
        val src = """
            use std::collections::iter
            let pipeline = { n } -> do
              let tripled = iter::map { it = iter::range { start = 0, end = n }, f = { x } -> x * 3 }
              let a = iter::fold { it = tripled, init = 0, f = { acc, x } -> acc + x }
              let b = iter::fold { it = tripled, init = 1, f = { acc, x } -> acc + x }
              a + b
        """
        val fn = ours(src).single { it.name == "pipeline" }
        assertTrue(
            fn.localSlots.isNotEmpty(),
            "a binding read twice was substituted, which duplicates the pipeline",
        )
    }
}
