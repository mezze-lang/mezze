package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.FusedChainIR
import com.mezze.language.truffleir.MakeLambdaIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.SinkIR
import com.mezze.language.truffleir.StageIR
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A fused chain's lambdas may only capture slots that exist in the frame of the
 * function holding the chain.
 *
 * WHY THIS IS A WHOLE-PROGRAM INVARIANT. `CaptureIR` holds a frame SLOT INDEX,
 * not a value, so a lambda is only meaningful in the frame it was built in.
 * Fusion moves chains between functions — `peekThroughMonoWrapper` substitutes
 * a call's arguments into its callee's body, `composeThroughFusedCallee`
 * splices a callee's chain into its caller — and each such move has to
 * re-express every capture against the new frame or refuse the move.
 *
 * THE BUG. `peekThroughMonoWrapper` rewrote a field whose value IS a parameter
 * read and copied every other field VERBATIM, so a `MakeLambdaIR` argument
 * travelled into the caller still addressing the callee's slots. For
 * `go = { k, lim, bump } -> range.map{x*k}.filter{x<lim}.fold{...}` called as
 * `go { k = 10, lim = 40, bump = 2 }`, the chain landed in a `main` with an
 * EMPTY frame while its stages still captured slots 0 and 1 — an out-of-bounds
 * read at emit (`[BDSL] compile failed ... entry-emit-crash`). A caller whose
 * frame merely differs would have been worse: no crash, just a stage reading
 * someone else's value.
 *
 * NOT only a dot-chain problem. Removing the guard fails the free-function
 * case below too, so this was reachable on any capturing pipeline whose caller
 * has a different frame — it had simply never been asserted. Dot-chain fusion
 * is what made it fail loudly, by producing many more chains inside functions
 * that take parameters.
 *
 * WHY AN INVARIANT RATHER THAN A CASE. The relocation paths are several and
 * each could lose captures differently, so this asserts the property every one
 * of them must preserve, over whole programs. The refusal costs a fusion
 * opportunity, never an answer — `go` itself still fuses; only the move into
 * `main` is declined.
 */
class FusedChainCaptureFrameTest {

    private fun lower(src: String): List<FunctionIR> =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"),
            Paths.get("."), true, null,
        ).loweredFunctions

    private fun lambdasOf(chain: FusedChainIR): List<MakeLambdaIR> {
        val out = mutableListOf<MakeLambdaIR>()
        for (st in chain.stages) {
            when (st) {
                is StageIR.Map -> out.add(st.f)
                is StageIR.Filter -> out.add(st.p)
                is StageIR.TakeWhile -> out.add(st.p)
                is StageIR.DropWhile -> out.add(st.p)
                is StageIR.Take, is StageIR.Drop -> {}
                // No lambda, so no capture to check.
                StageIR.Enumerate -> {}
                // Carries a SOURCE, not a lambda — its captures, if any, live
                // in that source's expressions and are checked with the chain's
                // own source rather than here.
                is StageIR.Zip -> {}
            }
        }
        when (val sk = chain.sink) {
            is SinkIR.Fold -> out.add(sk.f)
            is SinkIR.Scan -> out.add(sk.f)
            is SinkIR.ForEach -> out.add(sk.f)
            is SinkIR.Reduce -> out.add(sk.f)
            is SinkIR.Any -> out.add(sk.p)
            is SinkIR.All -> out.add(sk.p)
            is SinkIR.Find -> out.add(sk.p)
            is SinkIR.FindIndex -> out.add(sk.p)
            is SinkIR.None -> out.add(sk.p)
            is SinkIR.MinBy -> out.add(sk.f)
            is SinkIR.MaxBy -> out.add(sk.f)
            is SinkIR.Partition -> out.add(sk.p)
            else -> {}
        }
        return out
    }

    /** The fused chain a body computes, through any binding wrapper. */
    private fun chainIn(node: NodeIR): FusedChainIR? = when (node) {
        is FusedChainIR -> node
        is BlockIR -> chainIn(node.result)
        else -> null
    }

    /** Every capture of every fused chain must index its own function's frame. */
    private fun check(label: String, src: String) {
        val violations = mutableListOf<String>()
        var chains = 0
        for (fn in lower(src)) {
            // A relocated chain is wrapped in a BlockIR that BINDS the values
            // its captures were re-pointed at, so the chain is the block's
            // result rather than the body. Matching only on the body would quietly
            // stop checking the very case that relocation introduced.
            val chain = chainIn(fn.body) ?: continue
            chains++
            val frame = fn.captureSlots.size + fn.paramSlots.size + fn.localSlots.size
            for (lam in lambdasOf(chain)) {
                for (cap in lam.captures) {
                    if (cap.slot >= frame) {
                        violations.add(
                            "$label: fn '${fn.name}' frame=$frame captures slot=${cap.slot}",
                        )
                    }
                }
            }
        }
        assertTrue(chains > 0, "$label: nothing fused, so the invariant is untested")
        assertTrue(violations.isEmpty(), violations.joinToString("; "))
    }

    @Test
    fun `a capturing dot-chained pipeline never relocates its captures out of frame`() {
        // THE REGRESSION. `main` has an empty frame; the stages capture k and
        // lim from `go`'s. Relocating the chain unchanged read slot 0 of a
        // zero-length frame.
        check(
            "capturing stages",
            """
            use std::collections::iter
            let go = { k, lim, bump } -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x * k }
              .filter { p = { x } -> x < lim }
              .fold { init = bump, f = { acc, x } -> acc + x }
            let main = {} -> go { k = 10, lim = 40, bump = 2 }
            """,
        )
    }

    @Test
    fun `closed stages relocate freely and stay in frame`() {
        // A lambda with no captures is a closed constant, so moving it is a
        // no-op — these chains DO relocate, and must still satisfy the
        // invariant.
        check(
            "closed stages",
            """
            use std::collections::iter
            let go = {} -> iter::range { start = 0, end = 5 }
              .map { f = { x } -> x * 10 }
              .filter { p = { x } -> x < 40 }
              .fold { init = 0, f = { acc, x } -> acc + x }
            let main = {} -> go {}
            """,
        )
    }

    @Test
    fun `a capturing free-function pipeline stays in frame`() {
        check(
            "capturing freefn",
            """
            use std::collections::seq
            let go = { k, lim } -> seq::fold {
              xs = seq::filter {
                xs = seq::map { xs = seq::range { start = 0, end = 5 }, f = { x } -> x * k },
                p = { x } -> x < lim,
              },
              init = 0,
              f = { acc, x } -> acc + x,
            }
            let main = {} -> go { k = 10, lim = 40 }
            """,
        )
    }

    @Test
    fun `a pipeline split across a call fuses, with its captures in frame`() {
        // THE SHAPE THAT NEEDED THIS. The stage is in the callee and the sink
        // in the caller, so the chain only fuses if the peel looks through the
        // call — and the stage lambda captures `k`, which the caller passed as
        // a LITERAL and so holds in no slot. Binding it to a fresh local is
        // what makes the move possible; `chains > 0` below fails without that,
        // because refusing the move lost the fusion entirely rather than
        // costing one call.
        check(
            "spanning capturing stage",
            """
            use std::collections::iter
            let stage = { k } -> iter::map { it = iter::range { start = 0, end = 5 }, f = { x } -> x * k }
            let main = {} -> iter::fold { it = stage { k = 2 }, init = 0, f = { acc, x } -> acc + x }
            """,
        )
    }

    @Test
    fun `a pipeline split across a call with two captures keeps both in frame`() {
        check(
            "spanning, two captures",
            """
            use std::collections::iter
            let stage = { mul, lim } -> iter::filter {
              it = iter::map { it = iter::range { start = 0, end = 5 }, f = { x } -> x * mul },
              p = { x } -> x < lim,
            }
            let main = {} -> iter::fold { it = stage { mul = 10, lim = 40 }, init = 0, f = { acc, x } -> acc + x }
            """,
        )
    }

}
