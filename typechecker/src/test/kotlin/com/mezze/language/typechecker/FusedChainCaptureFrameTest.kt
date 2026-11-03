package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.FusedChainIR
import com.mezze.language.truffleir.MakeLambdaIR
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

    /** Every capture of every fused chain must index its own function's frame. */
    private fun check(label: String, src: String) {
        val violations = mutableListOf<String>()
        var chains = 0
        for (fn in lower(src)) {
            val chain = fn.body as? FusedChainIR ?: continue
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
}
