package com.mezze.interpreter.bytecode

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.CallDirectIR
import com.mezze.language.truffleir.CallNativeIR
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
 * throws a TailCallException from TailCallDirectN / TailCallIndirect
 * ops; BytecodeCompiler wraps every compiled BDSL root in a
 * BdslTrampolineRootNode that catches and re-drives, so the tail
 * call is transparent to callers.
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
    fun `tail-call to another function returns callee's result through the trampoline`() {
        val context = Context.newBuilder("mezze")
            .allowExperimentalOptions(true)
            .allowAllAccess(true)
            .build()
        try {
            context.enter()
            try {
                val language: MezzeLanguage? = MezzeLanguage.get(null) as MezzeLanguage?
                val compiler = BytecodeCompiler(language)
                val roots = compiler.compileWithCallees(listOf(buildAdd(), buildMain()))
                val mainRoot = roots[1]
                // Direct call — trampoline in BdslTrampolineRootNode catches
                // the TailCallException from TailCallDirectN and re-invokes
                // add with [10, 20], returning 30.
                val result = mainRoot.callTarget.call()
                assertEquals(30L, result)
            } finally {
                context.leave()
            }
        } finally {
            context.close()
        }
    }

    /**
     * loop(n, acc): if n == 0 then acc else tail-call loop(n - 1, acc + 1)
     *
     * With the trampoline in place, invoking loop(100_000, 0) must not
     * grow the JVM stack — proves TailCallException is absorbed by
     * BdslTrampolineRootNode instead of unwinding through each frame.
     */
    private fun buildLoop(): FunctionIR = FunctionIR(
        hash = Hash("93".repeat(32)),
        name = "loop",
        qualifiedName = "loop",
        paramSlots = listOf(SlotKind.LONG, SlotKind.LONG),
        paramNames = listOf("n", "acc"),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = com.mezze.language.truffleir.IfIR(
            // n == 0? use __native_int_eq(n, 0)
            cond = CallNativeIR(
                name = "__native_int_eq",
                arg = LongLitIR(0L, src),
                src = src,
                args = listOf(
                    ReadLocalIR(slot = 0, kind = SlotKind.LONG, src = src),
                    LongLitIR(0L, src),
                ),
                argKinds = listOf(SlotKind.LONG, SlotKind.LONG),
            ),
            thenBr = ReadLocalIR(slot = 1, kind = SlotKind.LONG, src = src),
            elseBr = CallDirectIR(
                targetHash = Hash("93".repeat(32)),
                args = listOf(
                    // n - 1 via __native_int_subtract(n, 1)
                    CallNativeIR(
                        name = "__native_int_subtract",
                        arg = LongLitIR(0L, src),
                        src = src,
                        args = listOf(
                            ReadLocalIR(slot = 0, kind = SlotKind.LONG, src = src),
                            LongLitIR(1L, src),
                        ),
                        argKinds = listOf(SlotKind.LONG, SlotKind.LONG),
                    ),
                    // acc + 1 via inline ADD (already supported)
                    InlineBinaryOpIR(
                        op = InlineOp.ADD,
                        left = ReadLocalIR(slot = 1, kind = SlotKind.LONG, src = src),
                        right = LongLitIR(1L, src),
                        src = src,
                    ),
                ),
                isTailCall = true,
                src = src,
                isScalarABI = true,
            ),
            src = src,
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    @Test
    fun `deep self-tail-recursion stays stack-flat under the trampoline`() {
        val context = Context.newBuilder("mezze")
            .allowExperimentalOptions(true)
            .allowAllAccess(true)
            .build()
        try {
            context.enter()
            try {
                val language: MezzeLanguage? = MezzeLanguage.get(null) as MezzeLanguage?
                val compiler = BytecodeCompiler(language)
                val roots = compiler.compileWithCallees(listOf(buildLoop()))
                val loopRoot = roots[0]
                // 100_000 iterations — well past any reasonable JVM stack
                // budget for a raw recursive call. If the trampoline is
                // missing this either SOFs or throws through the test.
                val result = loopRoot.callTarget.call(100_000L, 0L)
                assertEquals(100_000L, result)
            } finally {
                context.leave()
            }
        } finally {
            context.close()
        }
    }
}
