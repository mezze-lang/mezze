package com.mezze.interpreter.bytecode

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.FieldIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.ReadFieldIR
import com.mezze.language.truffleir.ReadLocalIR
import com.mezze.language.truffleir.SlotKind
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.truffleir.StmtIR
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase 2 acceptance test for MakeRecordIR with __spread__ entries.
 *
 * IR mimics:
 *   let r = { x = 1, y = 20 }
 *   let s = { ...r, z = 300 }
 *   s.x + s.y + s.z    -- 321
 *
 * Also verifies later-explicit-field-overrides-spread:
 *   let r = { x = 1 }
 *   let s = { ...r, x = 99 }
 *   s.x                 -- 99
 */
class BytecodeCompilerPhase2SpreadTest {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    private fun buildSpreadAndAdd(): FunctionIR = FunctionIR(
        hash = Hash("31".repeat(32)),
        name = "main_spread",
        qualifiedName = "main_spread",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = listOf(SlotKind.OBJECT, SlotKind.OBJECT),
        localNames = listOf("r", "s"),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = BlockIR(
            stmts = listOf(
                StmtIR.Let(
                    slot = 0,
                    kind = SlotKind.OBJECT,
                    name = "r",
                    contentHash = Hash("0".repeat(64)),
                    value = MakeRecordIR(
                        fields = listOf(
                            FieldIR("x", LongLitIR(1L, src)),
                            FieldIR("y", LongLitIR(20L, src)),
                        ),
                        src = src,
                    ),
                    src = src,
                ),
                StmtIR.Let(
                    slot = 1,
                    kind = SlotKind.OBJECT,
                    name = "s",
                    contentHash = Hash("1".repeat(64)),
                    value = MakeRecordIR(
                        fields = listOf(
                            FieldIR("__spread__", ReadLocalIR(slot = 0, kind = SlotKind.OBJECT, src = src)),
                            FieldIR("z", LongLitIR(300L, src)),
                        ),
                        src = src,
                    ),
                    src = src,
                ),
            ),
            result = com.mezze.language.truffleir.InlineBinaryOpIR(
                op = com.mezze.language.truffleir.InlineOp.ADD,
                left = com.mezze.language.truffleir.InlineBinaryOpIR(
                    op = com.mezze.language.truffleir.InlineOp.ADD,
                    left = ReadFieldIR(
                        record = ReadLocalIR(slot = 1, kind = SlotKind.OBJECT, src = src),
                        fieldName = "x",
                        fieldIndex = 0,
                        src = src,
                    ),
                    right = ReadFieldIR(
                        record = ReadLocalIR(slot = 1, kind = SlotKind.OBJECT, src = src),
                        fieldName = "y",
                        fieldIndex = 1,
                        src = src,
                    ),
                    src = src,
                ),
                right = ReadFieldIR(
                    record = ReadLocalIR(slot = 1, kind = SlotKind.OBJECT, src = src),
                    fieldName = "z",
                    fieldIndex = 2,
                    src = src,
                ),
                src = src,
            ),
            src = src,
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    private fun buildOverride(): FunctionIR = FunctionIR(
        hash = Hash("32".repeat(32)),
        name = "main_override",
        qualifiedName = "main_override",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = listOf(SlotKind.OBJECT, SlotKind.OBJECT),
        localNames = listOf("r", "s"),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = BlockIR(
            stmts = listOf(
                StmtIR.Let(
                    slot = 0,
                    kind = SlotKind.OBJECT,
                    name = "r",
                    contentHash = Hash("0".repeat(64)),
                    value = MakeRecordIR(
                        fields = listOf(FieldIR("x", LongLitIR(1L, src))),
                        src = src,
                    ),
                    src = src,
                ),
                StmtIR.Let(
                    slot = 1,
                    kind = SlotKind.OBJECT,
                    name = "s",
                    contentHash = Hash("1".repeat(64)),
                    value = MakeRecordIR(
                        fields = listOf(
                            FieldIR("__spread__", ReadLocalIR(slot = 0, kind = SlotKind.OBJECT, src = src)),
                            FieldIR("x", LongLitIR(99L, src)),
                        ),
                        src = src,
                    ),
                    src = src,
                ),
            ),
            result = ReadFieldIR(
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

    private fun run(fn: FunctionIR): Any? {
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
                val roots = compiler.compileWithCallees(listOf(fn))
                return roots[0].callTarget.call()
            } finally {
                context.leave()
            }
        } finally {
            context.close()
        }
    }

    @Test
    fun `spread merges source fields into new record — 1 plus 20 plus 300`() {
        assertEquals(321L, run(buildSpreadAndAdd()))
    }

    @Test
    fun `later explicit field overrides same-named spread entry`() {
        assertEquals(99L, run(buildOverride()))
    }
}
