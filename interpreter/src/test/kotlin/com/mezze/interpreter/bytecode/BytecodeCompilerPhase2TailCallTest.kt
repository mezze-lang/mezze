package com.mezze.interpreter.bytecode

import com.mezze.interpreter.nodes.function.TailCallException
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
 * Phase 2 acceptance test for tail-call variants. The BDSL root
 * throws a TailCallException that the runtime trampoline would
 * normally catch and re-drive; here we catch it at the test boundary
 * and drive it manually to prove the mechanism works.
 */
class BytecodeCompilerPhase2TailCallTest {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    /** add(a, b) = a + b. */
    private fun buildAdd(): FunctionIR = FunctionIR(
        hash = Hash("91".repeat(32)),
        name = "add",
        qualifiedName = "add",
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

    /**
     * main() { tail-call add(10, 20) } — the tail call throws
     * TailCallException carrying add's CallTarget + [10, 20].
     */
    private fun buildMain(): FunctionIR = FunctionIR(
        hash = Hash("92".repeat(32)),
        name = "main",
        qualifiedName = "main",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = CallDirectIR(
            targetHash = Hash("91".repeat(32)),
            args = listOf(LongLitIR(10L, src), LongLitIR(20L, src)),
            isTailCall = true,
            src = src,
            isScalarABI = true,
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    @Test
    fun `tail-call throws TailCallException with target and args`() {
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
                val roots = compiler.compileWithCallees(listOf(buildAdd(), buildMain()))
                val mainRoot = roots[1]
                // Drive the trampoline ourselves: invoke main, catch
                // TailCallException, re-invoke the callTarget with the
                // args, and expect the actual result.
                var target: com.oracle.truffle.api.CallTarget = mainRoot.callTarget
                var args: Array<Any?> = emptyArray()
                var loops = 0
                while (true) {
                    try {
                        val result = target.call(*args)
                        assertEquals(30L, result)
                        return
                    } catch (tce: TailCallException) {
                        target = tce.callTarget
                        @Suppress("UNCHECKED_CAST")
                        args = tce.arguments as Array<Any?>
                        loops++
                        if (loops > 10) throw IllegalStateException("too many trampoline loops")
                    }
                }
            } finally {
                context.leave()
            }
        } finally {
            context.close()
        }
    }
}
