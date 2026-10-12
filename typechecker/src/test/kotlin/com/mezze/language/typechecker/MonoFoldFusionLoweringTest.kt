package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.FusedChainIR
import com.mezze.language.truffleir.MakeLambdaIR
import com.mezze.language.truffleir.SinkIR
import com.mezze.language.truffleir.SlotKind
import com.mezze.language.truffleir.SourceIR
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * An unannotated fold, specialised at Int, lowers to a fused chain.
 *
 * `{ xs } -> list::fold_left { xs = xs, init = 10, f = {acc, x} -> acc + x }`
 * generalises its element over every 'a with `NumAdd 'a for Int having 'out = Int`.
 * Mono specialises it at the Int call site — but the specialised copy only
 * fuses if its call into the closure-specialised `fold_left` is inlined and its
 * folder reduces to an op over its two params. Unfused it ran ~10x slower than
 * the same function annotated.
 *
 * Lowered HERE, in the typechecker, with no interpreter on the classpath — the
 * situation of `mez build`, where the native registry that would collapse
 * `__native_int_add` into an inline `ADD` is not loaded.
 */
class MonoFoldFusionLoweringTest {

    private fun lower(src: String): List<FunctionIR> {
        val r = TypeChecker().typeCheck(MezzeParser().parseParsedModule(src.trimIndent(), "p.mz"), Paths.get("."), true, null)
        return r.loweredFunctions
    }

    @Test
    fun `the Int specialisation of an unannotated fold is a fused chain over Int params`() {
        val fns = lower("""
            use std::collections::list
            let tf = { xs } -> list::fold_left { xs = xs, init = 10, f = {acc, x} -> acc + x }
            let main = {} -> tf { xs = list::from_seq { xs = [1, 2, 3] } }
        """)
        val byHash = fns.associateBy { it.hash }
        val intFolds = fns.mapNotNull { it.body as? FusedChainIR }
            .filter { it.source is SourceIR.RawList && it.stages.isEmpty() }
            .mapNotNull { (it.sink as? SinkIR.Fold)?.f }
            .mapNotNull { f: MakeLambdaIR -> byHash[f.functionHash] }
            .filter { it.paramSlots == listOf(SlotKind.LONG, SlotKind.LONG) }
        assertTrue(intFolds.isNotEmpty(),
            "no fused chain with an Int folder; bodies: " + fns.filter { it.sourceFile == "p.mz" }.map { it.body::class.simpleName })
    }
}
