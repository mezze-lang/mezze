package com.mezze.interpreter.bytecode

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.FieldIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.ReadFieldIR
import com.mezze.language.truffleir.ReadLocalIR
import com.mezze.language.truffleir.RecordUpdateIR
import com.mezze.language.truffleir.SlotKind
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.truffleir.StmtIR
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase 2 acceptance test for RecordUpdateIR — hand-builds an IR that
 * mimics {@code { x = 1, y = 20 } { x = 100 }} — i.e. take a source
 * record and produce a new one with `x` overridden.
 *
 * Exercises the `UpdateRecord` op end-to-end through BDSL.
 */
class BytecodeCompilerPhase2RecordUpdateTest {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    /**
     * Build main:
     *   let r = { x = 1, y = 20 }
     *   let s = r { x = 100 }
     *   s.x
     * Expected: 100 (the overridden x).
     */
    private fun buildOverrideCase(): FunctionIR = FunctionIR(
        hash = Hash("11".repeat(32)),
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
                    value = RecordUpdateIR(
                        record = ReadLocalIR(slot = 0, kind = SlotKind.OBJECT, src = src),
                        updates = listOf(FieldIR("x", LongLitIR(100L, src))),
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

    /**
     * Build main:
     *   let r = { x = 1, y = 2 }
     *   let s = r { z = 99 }    -- z is brand-new
     *   s.z
     * Expected: 99.
     */
    private fun buildExtendCase(): FunctionIR = FunctionIR(
        hash = Hash("22".repeat(32)),
        name = "main_extend",
        qualifiedName = "main_extend",
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
                            FieldIR("y", LongLitIR(2L, src)),
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
                    value = RecordUpdateIR(
                        record = ReadLocalIR(slot = 0, kind = SlotKind.OBJECT, src = src),
                        updates = listOf(FieldIR("z", LongLitIR(99L, src))),
                        src = src,
                    ),
                    src = src,
                ),
            ),
            result = ReadFieldIR(
                record = ReadLocalIR(slot = 1, kind = SlotKind.OBJECT, src = src),
                fieldName = "z",
                fieldIndex = 2,
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
    fun `record update override — yields overridden field`() {
        assertEquals(100L, run(buildOverrideCase()))
    }

    @Test
    fun `record update extend — yields new field`() {
        assertEquals(99L, run(buildExtendCase()))
    }
}
