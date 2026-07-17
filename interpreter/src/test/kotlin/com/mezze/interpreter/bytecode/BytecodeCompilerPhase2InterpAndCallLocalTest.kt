package com.mezze.interpreter.bytecode

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.CallLocalIR
import com.mezze.language.truffleir.CaptureIR
import com.mezze.language.truffleir.FieldIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.InlineBinaryOpIR
import com.mezze.language.truffleir.InlineOp
import com.mezze.language.truffleir.InterpolatedStringIR
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.MakeLambdaIR
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.ReadFieldIR
import com.mezze.language.truffleir.ReadLocalIR
import com.mezze.language.truffleir.SlotKind
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.truffleir.StmtIR
import com.mezze.language.truffleir.StringLitIR
import com.mezze.language.truffleir.StringPartIR
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BytecodeCompilerPhase2InterpAndCallLocalTest {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    /**
     * Interpolate: "hello $(name), age $(age)" with name="world", age=42.
     * Expected: "hello world, age 42".
     */
    private fun buildInterp(): FunctionIR = FunctionIR(
        hash = Hash("51".repeat(32)),
        name = "main_interp",
        qualifiedName = "main_interp",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = InterpolatedStringIR(
            parts = listOf(
                StringPartIR.Lit("hello "),
                StringPartIR.Expr(StringLitIR("world", src)),
                StringPartIR.Lit(", age "),
                StringPartIR.Expr(LongLitIR(42L, src)),
            ),
            src = src,
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    /**
     * inc_record: captures [n@LONG], one record param (`arg`).
     * body: n + arg.x.
     */
    private fun buildIncRecord(): FunctionIR = FunctionIR(
        hash = Hash("52".repeat(32)),
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
            right = ReadFieldIR(
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
     * main_call_local: same shape as closure test, but the closure is
     * stored in a local (slot 1), then invoked via CallLocalIR (slot 1)
     * with { x = 5 }. Locals: [n, closure]. Expected 15 (n=10 + x=5).
     */
    private fun buildCallLocal(): FunctionIR = FunctionIR(
        hash = Hash("53".repeat(32)),
        name = "main_call_local",
        qualifiedName = "main_call_local",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = listOf(SlotKind.LONG, SlotKind.OBJECT),
        localNames = listOf("n", "closure"),
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
                StmtIR.Let(
                    slot = 1,
                    kind = SlotKind.OBJECT,
                    name = "closure",
                    contentHash = Hash("1".repeat(64)),
                    value = MakeLambdaIR(
                        functionHash = Hash("52".repeat(32)),
                        captures = listOf(CaptureIR(slot = 0, kind = SlotKind.LONG)),
                        src = src,
                    ),
                    src = src,
                ),
            ),
            result = CallLocalIR(
                slot = 1,
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

    private fun runOne(fn: FunctionIR): Any? = run(listOf(fn))
    private fun run(fns: List<FunctionIR>): Any? {
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
                val roots = compiler.compileWithCallees(fns)
                return roots[roots.size - 1].callTarget.call()
            } finally {
                context.leave()
            }
        } finally {
            context.close()
        }
    }

    @Test
    fun `InterpolatedString hello world age 42 concatenates correctly`() {
        assertEquals("hello world, age 42", runOne(buildInterp()))
    }

    @Test
    fun `CallLocal on a lambda in a local slot yields expected result`() {
        assertEquals(15L, run(listOf(buildIncRecord(), buildCallLocal())))
    }
}
