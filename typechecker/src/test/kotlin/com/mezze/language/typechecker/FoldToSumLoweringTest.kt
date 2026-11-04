package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.CallNativeIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.transformChildren
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A plain summing fold does not need a loop built for it — the collection
 * already has `sum`, which walks without calling back into guest code.
 *
 * `FoldToSumRewriter` turns `coll::fold { init = 0, f = { acc, x } -> acc + x }`
 * into `coll::sum { coll }`. MEASURED on `bench/cases/fuseall` at n=2M, min of
 * 3, with the `sum` kernels added to price the callback first:
 *
 * | | fold before | `sum` alone | fold after |
 * |---|---|---|---|
 * | deque | 9 638 ps/elem | 1 664 | **1 726** |
 * | set | 17 376 | 7 270 | **7 521** |
 *
 * 5.6x and 2.3x, each landing on the `sum` measurement that predicted it —
 * which is the evidence that the guest callback WAS the cost rather than the
 * walk. Controls unmoved: seq 391→406, list 1521→1478, and nel 8 858 untouched
 * because it has no `sum` native to rewrite to.
 *
 * WHY A LOWERING TEST IS HONEST HERE, where it was not for chain fusion. That
 * blindness came from the stdlib: a typechecker test lowers it FROM SOURCE while
 * `mez build` serves `PrecompiledStdlib`, and the fused shape differed between
 * them. This pass matches on the USER function's own body — the `fold` call and
 * its literal folder — so the lowered shape here is the shape that ships.
 * Verified the usual way: with the pass unhooked, the first test fails.
 *
 * EVERY CONTROL IS A CORRECTNESS CASE, not a performance one. `sum`'s boxed path
 * reads `Long`/`Integer` and SILENTLY DROPS anything else, so a Float fold
 * rewritten to `sum` returns 0 instead of the total — a wrong number, not a type
 * error.
 */
class FoldToSumLoweringTest {

    private fun lower(src: String): List<FunctionIR> =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        ).loweredFunctions.filter { it.sourceFile == "p.mz" }

    private fun natives(src: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: NodeIR) {
            if (node is CallNativeIR) out.add(node.name)
            node.transformChildren { child -> walk(child); child }
        }
        for (fn in lower(src)) walk(fn.body)
        return out
    }

    private val PRELUDE = """
        use std::collections::deque
        use std::collections::seq
    """.trimIndent()

    /** THE REWRITE. */
    @Test
    fun `a zero-init add fold over a deque becomes sum`() {
        val src = """
            $PRELUDE
            pub let total = { d: Deque Int } -> deque::fold {
              d = d, init = 0, f = { acc, x } -> acc + x
            }
        """
        val found = natives(src)
        assertTrue(
            "__native_deque_sum" in found,
            "the summing fold was not rewritten to `sum`; natives: $found",
        )
        assertTrue(
            "__native_deque_fold" !in found,
            "the `fold` call survived alongside the rewrite; natives: $found",
        )
    }

    /**
     * The same rewrite for `nonemptylist`, which spells its natives `nel` and
     * its fold `fold_left`.
     *
     * Its `sum` did not exist until this rewrite gave it a reason to — every
     * other element-based collection had one, and the absence was costing 8.9 ns
     * per element. `bench/cases/fuseall` kernel 3: 8 858 ps/elem to 1 206, 7.3x.
     */
    @Test
    fun `a zero-init add fold_left over a nonemptylist becomes sum`() {
        val src = """
            use std::collections::nonemptylist
            use std::collections::seq
            pub let total = { xs: NonEmptyList Int } -> nonemptylist::fold_left {
              xs = xs, init = 0, f = { acc, x } -> acc + x
            }
        """
        val found = natives(src)
        assertTrue(
            "__native_nel_sum" in found,
            "the summing fold_left was not rewritten to `sum`; natives: $found",
        )
        assertTrue(
            "__native_nel_fold_left" !in found,
            "the `fold_left` call survived alongside the rewrite; natives: $found",
        )
    }

    /**
     * CONTROL, AND A CORRECTNESS CASE. A fold over FLOATS must keep its folder.
     *
     * `sum`'s boxed path reads Long/Integer and drops everything else, so
     * rewriting this would return 0.0 rather than the total. The guard is the
     * folder's parameter SLOT KINDS — a folder over Ints has two LONG
     * parameters and this one does not — the same evidence
     * `ListFoldElementKindFusionTest` uses, for the same reason: a source says
     * nothing about what is in it.
     */
    @Test
    fun `a float fold over a deque keeps its folder`() {
        val src = """
            use std::collections::deque
            use std::collections::seq
            use std::float
            pub let total = { d: Deque Float } -> deque::fold {
              d = d, init = 0.0, f = { acc, x } -> acc + x
            }
        """
        val found = natives(src)
        assertTrue(
            "__native_deque_sum" !in found,
            "a FLOAT fold was rewritten to `sum`, whose boxed path drops " +
                "non-integers — it would return 0. Natives: $found",
        )
    }

    /** CONTROL: `sum` starts from zero and cannot carry an init. */
    @Test
    fun `a non-zero init keeps its folder`() {
        val src = """
            $PRELUDE
            pub let total = { d: Deque Int } -> deque::fold {
              d = d, init = 10, f = { acc, x } -> acc + x
            }
        """
        val found = natives(src)
        assertTrue(
            "__native_deque_sum" !in found,
            "a non-zero init was rewritten to `sum`, which would drop the 10; natives: $found",
        )
    }

    /** CONTROL: a compound folder has an element transform `sum` would ignore. */
    @Test
    fun `a compound folder keeps its folder`() {
        val src = """
            $PRELUDE
            pub let total = { d: Deque Int } -> deque::fold {
              d = d, init = 0, f = { acc, x } -> acc + x * x
            }
        """
        val found = natives(src)
        assertTrue(
            "__native_deque_sum" !in found,
            "`acc + x * x` was rewritten to `sum`, which would ignore the square; " +
                "natives: $found",
        )
    }
}
