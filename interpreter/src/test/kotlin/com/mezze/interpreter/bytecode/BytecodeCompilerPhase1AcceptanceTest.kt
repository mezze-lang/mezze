package com.mezze.interpreter.bytecode

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.CallDirectIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.InlineBinaryOpIR
import com.mezze.language.truffleir.InlineOp
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.ReadLocalIR
import com.mezze.language.truffleir.SlotKind
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase 1 acceptance test — the two-function program that #278's
 * success criterion demands runs end-to-end through BDSL:
 *
 *   let add = { a, b } -> a + b
 *   add { a = 1, b = 2 }   // expect 3
 *
 * We hand-build the [FunctionIR]s for both `add` and the caller `main`,
 * then compile them through [BytecodeCompiler.compileWithCallees] with
 * `add` first so the caller's [CallDirectIR] can bake in `add`'s
 * CallTarget. Execute `main.callTarget.call()` and assert 3L.
 *
 * If this passes, Phase 1's walking skeleton is functionally complete.
 */
class BytecodeCompilerPhase1AcceptanceTest {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    /** Build the `add` function: `{ a, b } -> a + b`. */
    private fun buildAdd(): FunctionIR = FunctionIR(
        hash = Hash("a".repeat(64)),
        name = "add",
        qualifiedName = "add",
        // Two params a, b at slots 0, 1 (frame layout = [captures..., params..., locals...]).
        paramSlots = listOf(SlotKind.LONG, SlotKind.LONG),
        paramNames = listOf("a", "b"),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = InlineBinaryOpIR(
            op = InlineOp.ADD,
            left = ReadLocalIR(slot = 0, kind = SlotKind.LONG, src = src),
            right = ReadLocalIR(slot = 1, kind = SlotKind.LONG, src = src),
            src = src,
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    /** Build the caller `main`: `add { a = 1, b = 2 }`. */
    private fun buildMain(): FunctionIR = FunctionIR(
        hash = Hash("b".repeat(64)),
        name = "main",
        qualifiedName = "main",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = CallDirectIR(
            targetHash = Hash("a".repeat(64)),   // matches `add`'s hash
            args = listOf(LongLitIR(1L, src), LongLitIR(2L, src)),
            isTailCall = false,
            src = src,
            isScalarABI = true,
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    @Test
    fun `let add a b -- a + b -- add 1 2 -- yields 3`() {
        val context = Context.newBuilder("mezze")
            .allowExperimentalOptions(true)
            .allowAllAccess(true)
            .build()
        try {
            context.enter()
            try {
                @Suppress("UNCHECKED_CAST")
                val language: MezzeLanguage? = MezzeLanguage.get(null) as MezzeLanguage?
                val compiler = BytecodeCompiler(language)

                // Compile add first (callee), then main (caller). The
                // compiler patches main's CallDirect2 with add's
                // CallTarget once add is compiled.
                val roots = compiler.compileWithCallees(listOf(buildAdd(), buildMain()))
                val addRoot = roots[0]
                val mainRoot = roots[1]

                // Independent sanity: add(5, 7) via its own callTarget.
                assertEquals(12L, addRoot.callTarget.call(5L, 7L))

                // The acceptance case: main() calls add(1, 2), returns 3.
                assertEquals(3L, mainRoot.callTarget.call())
            } finally {
                context.leave()
            }
        } finally {
            context.close()
        }
    }
}
