package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.FusedChainIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.StageIR
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A fused chain's stages are lowered OUTER-TO-INNER: `stages[0]` is the stage
 * applied LAST, the one nearest the sink.
 *
 * That convention is not a detail — `ChainCanonicalizer.peelSource` walks the
 * nesting inward (`xs.map(f).filter(p)` nests as `filter(map(xs))`) and
 * `BytecodeCompiler.emitCanonicalIterChain` reverses the classified list once,
 * after which the fused loop's emission order, its `filterFirst` flag and its
 * take-after-filter refusal all read application order. Everything downstream
 * of that single reverse is consistent.
 *
 * THE BUG. A stage list built by hand has to match the convention, and the
 * `count` sink's did not. `count` has no filter stage of its own: the
 * canonicalizer takes the peeled stages and adds its predicate, which is
 * applied LAST and so belongs at the FRONT. It was APPENDED instead, making it
 * the innermost stage — so the predicate the user wrote for mapped values was
 * tested against the source. `range{0,5}.map{*10}.count{<40}` answered 5
 * where the answer is 4: every element passed, because every raw element is
 * under 40. No crash, no diagnostic, a wrong number.
 *
 * `composeThroughFusedCallee` splices two stage lists and had the same
 * inversion — a callee's stages consume what the caller's argument produces,
 * so they run later and come first. It is inert while the callees that reach
 * it are stage-free wrappers like `seq::sum`, which is why no case here
 * distinguishes it, but the order is now the one the convention requires.
 *
 * WHY HERE. Order is a property of the LOWERED IR, so asserting it directly is
 * what pins the contract for both emitters — and it reproduces without an
 * interpreter on the classpath, the `mez build` situation. The values are
 * guarded from the other side by `interpreter`'s FusedChainStageOrderTest.
 */
class FusedChainStageOrderLoweringTest {

    private fun lower(src: String): List<FunctionIR> {
        val r = TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        )
        return r.loweredFunctions
    }

    /** The fused chain a body computes, through any binding wrapper: a
     *  relocated chain is the result of a BlockIR that binds what its captures
     *  point at. */
    private fun chainIn(node: NodeIR): FusedChainIR? = when (node) {
        is FusedChainIR -> node
        is BlockIR -> chainIn(node.result)
        else -> null
    }

    /**
     * The DISTINCT stage orders of the fused chains lowered from `p.mz`.
     *
     * Distinct, not one per function: the same chain is lowered into more than
     * one function whenever a wrapper holding it is also inlined into its
     * caller, so `go` and the `main` that calls it both carry it. The count is
     * an artefact of inlining decisions; the ORDER is the contract, and every
     * copy must agree on it.
     */
    private fun stageKinds(src: String): List<List<String>> =
        lower(src)
            .filter { it.sourceFile == "p.mz" }
            .mapNotNull { chainIn(it.body) }
            .filter { it.stages.isNotEmpty() }
            .map { chain ->
                chain.stages.map { st ->
                    when (st) {
                        is StageIR.Map -> "map"
                        is StageIR.Filter -> "filter"
                        is StageIR.Take -> "take"
                        is StageIR.Drop -> "drop"
                        is StageIR.TakeWhile -> "take_while"
                        is StageIR.DropWhile -> "drop_while"
                        StageIR.Enumerate -> "enumerate"
                        is StageIR.Zip -> "zip"
                        is StageIR.FlatMap -> "flat_map"
                    }
                }
            }
            .distinct()

    @Test
    fun `the count sink's own predicate is the LAST-applied stage`() {
        // THE REGRESSION. `map{*10}.count{<40}`: the predicate runs after the
        // map, so outer-to-inner it is stages[0]. Appended instead it became
        // the innermost stage, filtered raw elements, and counted 5 not 4.
        val chains = stageKinds(
            """
            use std::collections::seq
            let go = { lo, hi } ->
              seq::count {
                xs = seq::map { xs = seq::range { start = lo, end = hi }, f = { x } -> x * 10 },
                p = { x } -> x < 40,
              }
            let main = {} -> go { lo = 0, hi = 5 }
            """,
        )
        assertEquals(
            listOf(listOf("filter", "map")), chains,
            "the sink's predicate runs last, so it must come first",
        )
    }

    /**
     * `flat_map` REACHES THE CHAIN AT ALL. Before the stage existed the peel
     * had no case for it, so the call classified Opaque and the whole chain
     * declined: `fold(flat_map(range))` ran element-at-a-time at 135 405 802 ns
     * against 4 940 896 fused, measured as a self-A/B on two builds from
     * identical source. 27.4x.
     *
     * This is the assertion that fails without the peel. The VALUE tests pass
     * either way — both paths compute the same sum, which is the point — so
     * nothing but the lowering can tell the fused path from the fallback.
     */
    @Test
    fun `flat_map lowers to a stage rather than declining the chain`() {
        val chains = stageKinds(
            """
            use std::collections::iter
            let go = { lo, hi } ->
              iter::fold {
                it = iter::flat_map {
                  it = iter::range { start = lo, end = hi },
                  f = { x } -> iter::range { start = x, end = x + 10 },
                },
                init = 0,
                f = { acc, x } -> acc + x,
              }
            """,
        )
        assertEquals(
            listOf(listOf("flat_map")), chains,
            "an empty list means the chain declined and nothing fused",
        )
    }

    @Test
    fun `map then filter lowers outermost-first`() {
        // Written map-then-filter, so the filter is outermost and leads.
        val chains = stageKinds(
            """
            use std::collections::seq
            let go = { lo, hi } ->
              seq::fold {
                xs = seq::filter {
                  xs = seq::map { xs = seq::range { start = lo, end = hi }, f = { x } -> x * 10 },
                  p = { x } -> x < 40,
                },
                init = 0,
                f = { acc, x } -> acc + x,
              }
            let main = {} -> go { lo = 0, hi = 5 }
            """,
        )
        assertEquals(listOf(listOf("filter", "map")), chains)
    }

    @Test
    fun `filter then map lowers outermost-first`() {
        val chains = stageKinds(
            """
            use std::collections::seq
            let go = { lo, hi } ->
              seq::fold {
                xs = seq::map {
                  xs = seq::filter { xs = seq::range { start = lo, end = hi }, p = { x } -> x < 4 },
                  f = { x } -> x * 10,
                },
                init = 0,
                f = { acc, x } -> acc + x,
              }
            let main = {} -> go { lo = 0, hi = 5 }
            """,
        )
        assertEquals(listOf(listOf("map", "filter")), chains)
    }

    @Test
    fun `a three-stage pipeline keeps all three in nesting order`() {
        // take is the stage whose fused form can only clamp the SOURCE count,
        // so its position relative to the filter decides whether the fast path
        // may fire at all — the order here is not cosmetic.
        val chains = stageKinds(
            """
            use std::collections::seq
            let go = { lo, hi } ->
              seq::fold {
                xs = seq::filter {
                  xs = seq::map {
                    xs = seq::take { xs = seq::range { start = lo, end = hi }, n = 3 },
                    f = { x } -> x * 10,
                  },
                  p = { x } -> x < 40,
                },
                init = 0,
                f = { acc, x } -> acc + x,
              }
            let main = {} -> go { lo = 0, hi = 10 }
            """,
        )
        assertEquals(listOf(listOf("filter", "map", "take")), chains)
    }
}
