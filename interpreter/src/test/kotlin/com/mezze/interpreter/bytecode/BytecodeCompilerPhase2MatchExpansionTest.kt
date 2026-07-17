package com.mezze.interpreter.bytecode

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.BindingIR
import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.CaseIR
import com.mezze.language.truffleir.FieldIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.InlineBinaryOpIR
import com.mezze.language.truffleir.InlineOp
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.MatchIR
import com.mezze.language.truffleir.PatternIR
import com.mezze.language.truffleir.ReadLocalIR
import com.mezze.language.truffleir.SlotKind
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase 2 acceptance tests for the expanded match subset:
 * literal patterns and record patterns.
 */
class BytecodeCompilerPhase2MatchExpansionTest {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    /**
     * match 2
     *   1 -> 100
     *   2 -> 200
     *   _ -> 999
     * Expected: 200.
     */
    private fun buildLiteralMatch(): FunctionIR = FunctionIR(
        hash = Hash("aa".repeat(32)),
        name = "main",
        qualifiedName = "main",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = MatchIR(
            scrutinee = LongLitIR(2L, src),
            cases = listOf(
                CaseIR(
                    pattern = PatternIR.Literal(LongLitIR(1L, src)),
                    bindings = emptyList(),
                    guard = null,
                    body = LongLitIR(100L, src),
                    src = src,
                ),
                CaseIR(
                    pattern = PatternIR.Literal(LongLitIR(2L, src)),
                    bindings = emptyList(),
                    guard = null,
                    body = LongLitIR(200L, src),
                    src = src,
                ),
                CaseIR(
                    pattern = PatternIR.Wildcard(),
                    bindings = emptyList(),
                    guard = null,
                    body = LongLitIR(999L, src),
                    src = src,
                ),
            ),
            src = src,
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    /**
     * match { x = 5, y = 10 }
     *   { x, y } -> x + y   -- record pattern, extracts and binds
     * Expected: 15.
     */
    private fun buildRecordMatch(): FunctionIR = FunctionIR(
        hash = Hash("bb".repeat(32)),
        name = "main_rec",
        qualifiedName = "main_rec",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = listOf(SlotKind.LONG, SlotKind.LONG),
        localNames = listOf("x", "y"),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = MatchIR(
            scrutinee = MakeRecordIR(
                fields = listOf(
                    FieldIR("x", LongLitIR(5L, src)),
                    FieldIR("y", LongLitIR(10L, src)),
                ),
                src = src,
            ),
            cases = listOf(
                CaseIR(
                    pattern = PatternIR.Record(
                        fieldNames = listOf("x", "y"),
                        fieldSlots = listOf(0, 1),
                        isOpen = false,
                    ),
                    bindings = listOf(
                        BindingIR(slot = 0, kind = SlotKind.LONG),
                        BindingIR(slot = 1, kind = SlotKind.LONG),
                    ),
                    guard = null,
                    body = InlineBinaryOpIR(
                        op = InlineOp.ADD,
                        left = ReadLocalIR(slot = 0, kind = SlotKind.LONG, src = src),
                        right = ReadLocalIR(slot = 1, kind = SlotKind.LONG, src = src),
                        src = src,
                    ),
                    src = src,
                ),
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
    fun `match literal 2 -- hits second arm yielding 200`() {
        assertEquals(200L, run(buildLiteralMatch()))
    }

    @Test
    fun `match record x y -- extracts and sums yielding 15`() {
        assertEquals(15L, run(buildRecordMatch()))
    }
}
