package com.mezze.interpreter.bytecode

import com.mezze.datastructures.seq.MezzeSeq
import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.BindingIR
import com.mezze.language.truffleir.CaseIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.InlineBinaryOpIR
import com.mezze.language.truffleir.InlineOp
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.MakeSeqIR
import com.mezze.language.truffleir.MatchIR
import com.mezze.language.truffleir.PatternIR
import com.mezze.language.truffleir.ReadLocalIR
import com.mezze.language.truffleir.SeqElementIR
import com.mezze.language.truffleir.SlotKind
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase 2 acceptance tests for Seq patterns in match:
 *   match [10, 20, 30] is
 *     [a, b, c] -> a + b + c   -- 60
 *     _        -> 999
 *
 *   match [10, 20, 30, 40] is
 *     [a, b, ...rest] -> a + b + rest.size()    -- 30 + 2 = 32
 *     _              -> 999
 */
class BytecodeCompilerPhase2SeqPatternTest {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    private fun buildLenMatch(): FunctionIR = FunctionIR(
        hash = Hash("71".repeat(32)),
        name = "main_len",
        qualifiedName = "main_len",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = listOf(SlotKind.LONG, SlotKind.LONG, SlotKind.LONG),
        localNames = listOf("a", "b", "c"),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = MatchIR(
            scrutinee = MakeSeqIR(
                elements = listOf(
                    SeqElementIR.Regular(LongLitIR(10L, src)),
                    SeqElementIR.Regular(LongLitIR(20L, src)),
                    SeqElementIR.Regular(LongLitIR(30L, src)),
                ),
                src = src,
            ),
            cases = listOf(
                CaseIR(
                    pattern = PatternIR.Seq(
                        elementSlots = listOf(0, 1, 2),
                        restSlot = -1,
                    ),
                    bindings = listOf(
                        BindingIR(slot = 0, kind = SlotKind.LONG),
                        BindingIR(slot = 1, kind = SlotKind.LONG),
                        BindingIR(slot = 2, kind = SlotKind.LONG),
                    ),
                    guard = null,
                    body = InlineBinaryOpIR(
                        op = InlineOp.ADD,
                        left = InlineBinaryOpIR(
                            op = InlineOp.ADD,
                            left = ReadLocalIR(slot = 0, kind = SlotKind.LONG, src = src),
                            right = ReadLocalIR(slot = 1, kind = SlotKind.LONG, src = src),
                            src = src,
                        ),
                        right = ReadLocalIR(slot = 2, kind = SlotKind.LONG, src = src),
                        src = src,
                    ),
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

    /** Match [10, 20, 30, 40] with [a, b, ...rest] — bind rest into slot 2. */
    private fun buildRestMatch(): FunctionIR = FunctionIR(
        hash = Hash("72".repeat(32)),
        name = "main_rest",
        qualifiedName = "main_rest",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = listOf(SlotKind.LONG, SlotKind.LONG, SlotKind.OBJECT),
        localNames = listOf("a", "b", "rest"),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = MatchIR(
            scrutinee = MakeSeqIR(
                elements = listOf(
                    SeqElementIR.Regular(LongLitIR(10L, src)),
                    SeqElementIR.Regular(LongLitIR(20L, src)),
                    SeqElementIR.Regular(LongLitIR(30L, src)),
                    SeqElementIR.Regular(LongLitIR(40L, src)),
                ),
                src = src,
            ),
            cases = listOf(
                CaseIR(
                    pattern = PatternIR.Seq(
                        elementSlots = listOf(0, 1),
                        restSlot = 2,
                    ),
                    bindings = listOf(
                        BindingIR(slot = 0, kind = SlotKind.LONG),
                        BindingIR(slot = 1, kind = SlotKind.LONG),
                        BindingIR(slot = 2, kind = SlotKind.OBJECT),
                    ),
                    guard = null,
                    // body simply returns the rest — we assert on
                    // its size in the test.
                    body = ReadLocalIR(slot = 2, kind = SlotKind.OBJECT, src = src),
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
    fun `seq pattern length exact 3 binds a b c yields 60`() {
        assertEquals(60L, run(buildLenMatch()))
    }

    @Test
    fun `seq pattern with rest binds head 2 and tail of 2 elements`() {
        val result = run(buildRestMatch())
        assertEquals(true, result is MezzeSeq<*>)
        val rest = result as MezzeSeq<*>
        assertEquals(2, rest.size())
        assertEquals(30L, rest.get(0))
        assertEquals(40L, rest.get(1))
    }
}
