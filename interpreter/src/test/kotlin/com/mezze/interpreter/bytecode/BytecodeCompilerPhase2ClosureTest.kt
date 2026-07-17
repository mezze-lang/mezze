package com.mezze.interpreter.bytecode

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.interpreter.values.MezzeFunction
import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.CallIndirectIR
import com.mezze.language.truffleir.CaptureIR
import com.mezze.language.truffleir.FieldIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.InlineBinaryOpIR
import com.mezze.language.truffleir.InlineOp
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.MakeLambdaIR
import com.mezze.language.truffleir.ReadLocalIR
import com.mezze.language.truffleir.SlotKind
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.truffleir.StmtIR
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase 2 acceptance test for closures — captureSlots + MakeLambdaIR.
 *
 * inc captures n (slot 0) and takes param x (slot 1); body reads
 * both and adds. main constructs an inc closure with n = 10, then
 * we invoke inc's CallTarget directly with the captured n first,
 * then param x, matching MezzeFunctionRootNode's convention.
 */
class BytecodeCompilerPhase2ClosureTest {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    /**
     * inc: captures [n@LONG], params [x@LONG] — frame layout [n, x].
     * body: n + x.
     */
    private fun buildInc(): FunctionIR = FunctionIR(
        hash = Hash("cc".repeat(32)),
        name = "inc",
        qualifiedName = "inc",
        paramSlots = listOf(SlotKind.LONG),
        paramNames = listOf("x"),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = listOf(SlotKind.LONG),
        captureNames = listOf("n"),
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
     * main: build a lambda with n = 10, return it (nullary main).
     * localSlots = [lambda-storage] so we can Let into it.
     */
    private fun buildMain(): FunctionIR = FunctionIR(
        hash = Hash("dd".repeat(32)),
        name = "main",
        qualifiedName = "main",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = listOf(SlotKind.OBJECT),
        localNames = listOf("lambda"),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = BlockIR(
            stmts = listOf(
                StmtIR.Let(
                    slot = 0,
                    kind = SlotKind.OBJECT,
                    name = "lambda",
                    contentHash = Hash("0".repeat(64)),
                    // Even though we have no local for n, MakeLambdaIR
                    // captures a *literal* Long via the same slot-read
                    // scheme; to keep this hand-built test simple we
                    // synthesize the capture from a literal by writing
                    // it into a local first. Skip that overhead — build
                    // MakeLambda with an empty capture list, then
                    // synthesize a value into it via callTarget.call.
                    value = MakeLambdaIR(
                        functionHash = Hash("cc".repeat(32)),
                        captures = listOf(CaptureIR(slot = 0, kind = SlotKind.OBJECT)),
                        src = src,
                    ),
                    src = src,
                ),
            ),
            result = ReadLocalIR(slot = 0, kind = SlotKind.OBJECT, src = src),
            src = src,
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    @Test
    fun `inc callTarget with captured n plus param x yields n plus x`() {
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
                val roots = compiler.compileWithCallees(listOf(buildInc()))
                // Simulate the caller pushing captures first, then param:
                // callTarget.call(n = 10, x = 5) -> 15.
                assertEquals(15L, roots[0].callTarget.call(10L, 5L))
            } finally {
                context.leave()
            }
        } finally {
            context.close()
        }
    }

    /**
     * inc_record: captures [n@LONG], one record param (`arg` at slot 1).
     * body: n + arg.x. This matches what CallIndirect delivers — the
     * single record arg lands in slot captureCount, and we extract `x`
     * via ReadFieldByName.
     */
    private fun buildIncRecord(): FunctionIR = FunctionIR(
        hash = Hash("ff".repeat(32)),
        name = "inc_record",
        qualifiedName = "inc_record",
        paramSlots = listOf(SlotKind.OBJECT),
        paramNames = listOf("arg"),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = listOf(SlotKind.LONG),
        captureNames = listOf("n"),
        body = InlineBinaryOpIR(
            op = InlineOp.ADD,
            left = ReadLocalIR(slot = 0, kind = SlotKind.LONG, src = src),
            right = com.mezze.language.truffleir.ReadFieldIR(
                record = ReadLocalIR(slot = 1, kind = SlotKind.OBJECT, src = src),
                fieldName = "x",
                fieldIndex = 0,
                src = src,
            ),
            src = src,
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    /**
     * main_call: build inc_record closure with n captured from a
     * literal (which we've already stored in a local first — the
     * MakeLambda captures read from local slot 0), call it via
     * CallIndirect with { x = 5 } as the arg record.
     * Expected: 10 + 5 = 15.
     */
    private fun buildMainCall(): FunctionIR = FunctionIR(
        hash = Hash("10".repeat(32)),
        name = "main_call",
        qualifiedName = "main_call",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = listOf(SlotKind.LONG),
        localNames = listOf("n"),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = BlockIR(
            stmts = listOf(
                StmtIR.Let(
                    slot = 0,
                    kind = SlotKind.LONG,
                    name = "n",
                    contentHash = Hash("0".repeat(64)),
                    value = LongLitIR(10L, src),
                    src = src,
                ),
            ),
            result = CallIndirectIR(
                function = MakeLambdaIR(
                    functionHash = Hash("ff".repeat(32)),
                    captures = listOf(CaptureIR(slot = 0, kind = SlotKind.LONG)),
                    src = src,
                ),
                arg = MakeRecordIR(
                    fields = listOf(FieldIR("x", LongLitIR(5L, src))),
                    src = src,
                ),
                isTailCall = false,
                src = src,
            ),
            src = src,
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    @Test
    fun `CallIndirect on a closure yields captured n plus arg x`() {
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
                val roots = compiler.compileWithCallees(listOf(buildIncRecord(), buildMainCall()))
                assertEquals(15L, roots[1].callTarget.call())
            } finally {
                context.leave()
            }
        } finally {
            context.close()
        }
    }

    @Test
    fun `MakeLambda packages callee and captures into MezzeFunction`() {
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
                // Build a main function that captures its local slot 0
                // — but main has no captures/locals to read *from*. Trick:
                // make main's body just MakeLambda(inc, []) — no captures.
                // Then verify the returned MezzeFunction wraps inc's
                // CallTarget and has empty closureValues.
                val simplerMain = FunctionIR(
                    hash = Hash("ee".repeat(32)),
                    name = "main_simple",
                    qualifiedName = "main_simple",
                    paramSlots = emptyList(),
                    paramNames = emptyList(),
                    localSlots = emptyList(),
                    localNames = emptyList(),
                    captureSlots = emptyList(),
                    captureNames = emptyList(),
                    body = MakeLambdaIR(
                        functionHash = Hash("cc".repeat(32)),
                        captures = emptyList(),
                        src = src,
                    ),
                    sourceFile = "test.mz",
                    sourceSection = src,
                )
                val roots = compiler.compileWithCallees(listOf(buildInc(), simplerMain))
                val result = roots[1].callTarget.call()
                assertEquals(true, result is MezzeFunction)
                val fn = result as MezzeFunction
                assertEquals(roots[0].callTarget, fn.callTarget)
                assertEquals(0, fn.closureValues.size)
            } finally {
                context.leave()
            }
        } finally {
            context.close()
        }
    }
}
