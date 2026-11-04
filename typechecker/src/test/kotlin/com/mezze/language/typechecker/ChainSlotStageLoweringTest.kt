package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.CallIndirectIR
import com.mezze.language.truffleir.CallIndirectScalarIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.FusedChainIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.transformChildren
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A stage written as a local helper reaches the fuser.
 *
 * `peelSource` walks expressions and cannot follow a call through a frame slot,
 * so a stage parameterised by a value from the enclosing scope —
 *
 *     let factor = …
 *     let scaled = { xs } -> iter::map { it = xs, f = { x } -> x * factor }
 *     iter::fold { it = iter::filter { it = scaled { xs = … }, … }, … }
 *
 * left the source un-typable. `MEZZE_TRACE_FUSION` named it exactly:
 * `native=__native_iter_fold source=CallIndirectScalarIR:through-a-slot`.
 * `bench/cases/dotprobe` k3 measures 49 280 ps/elem against the fused 152.
 *
 * ── THIS TEST PINS THE LOWERING, AND THE CLOCK HAS NOT MOVED ───────────────
 *
 * The canonicalizer now resolves that call: the chain canonicalizes where it
 * previously could not, and `FusedChainIR` appears where there was none. But k3
 * still measures ~49-62 µs/elem, because the EMITTER then declines the chain and
 * falls back to the original node. With `MEZZE_CHAIN_CANON_EMIT_DEBUG=1`:
 *
 *     [chain-canon-emit] reject: unclassifiable map lambda
 *
 * for `{ x } -> x * factor`, whose `factor` is a capture — a shape
 * `classifyUnaryLiteralLambda`'s Shape A claims to accept, through
 * `liftCaptureRhs`. That is a defect in a different subsystem and is recorded
 * as the second of two blockers on this gap.
 *
 * So this test asserts what actually changed: the call is gone and the chain is
 * canonical. It would pass equally if the emitter were fixed, and fail if the
 * resolution regressed — which is what it is for.
 */
class ChainSlotStageLoweringTest {

    private fun lower(src: String): List<FunctionIR> =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        ).loweredFunctions.filter { it.sourceFile == "p.mz" }

    private fun walk(node: NodeIR, f: (NodeIR) -> Unit) {
        f(node)
        node.transformChildren { child -> walk(child, f); child }
    }

    private val capturingStage = """
        use std::collections::iter
        let factor_of = { n } -> if n > 0 then 3 else 1
        let pipeline = { n } -> do
          let factor = factor_of { n = n }
          let scaled = { xs } -> iter::map { it = xs, f = { x } -> x * factor }
          iter::fold {
            it = iter::filter { it = scaled { xs = iter::range { start = 0, end = n } }, p = { x } -> x > 2 },
            init = 0, f = { acc, x } -> acc + x,
          }
    """

    @Test
    fun `a stage behind a local helper canonicalizes`() {
        val fn = lower(capturingStage).single { it.name == "pipeline" }
        var fused = 0
        walk(fn.body) { if (it is FusedChainIR) fused++ }
        assertTrue(fused > 0, "the chain through a local stage helper did not canonicalize")
    }

    @Test
    fun `the call through the slot is gone`() {
        val fn = lower(capturingStage).single { it.name == "pipeline" }
        var indirect = 0
        walk(fn.body) {
            if (it is CallIndirectScalarIR || it is CallIndirectIR) indirect++
        }
        // The fallback a FusedChainIR carries holds the ORIGINAL expression, so
        // a surviving indirect call there is expected; what must not survive is
        // one on the fused path. Counting zero across the whole body is the
        // stronger statement and is what the resolution achieves.
        assertTrue(
            indirect == 0,
            "a call through a slot survived: $indirect — peelSource cannot follow one",
        )
    }
}
