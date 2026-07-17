package com.mezze.interpreter.bytecode

import com.mezze.datastructures.seq.MezzeSeq
import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.MakeSeqIR
import com.mezze.language.truffleir.SeqElementIR
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase 2 acceptance test for MakeSeqIR — no-spread sequence literal.
 * Verifies that a hand-built `[10, 20, 30]` produces a MezzeSeq of
 * size 3 with the expected elements.
 */
class BytecodeCompilerPhase2SeqTest {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    private fun buildSeqMain(): FunctionIR = FunctionIR(
        hash = Hash("21".repeat(32)),
        name = "main_seq",
        qualifiedName = "main_seq",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = MakeSeqIR(
            elements = listOf(
                SeqElementIR.Regular(LongLitIR(10L, src)),
                SeqElementIR.Regular(LongLitIR(20L, src)),
                SeqElementIR.Regular(LongLitIR(30L, src)),
            ),
            src = src,
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    private fun buildEmptySeq(): FunctionIR = FunctionIR(
        hash = Hash("22".repeat(32)),
        name = "main_empty",
        qualifiedName = "main_empty",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = MakeSeqIR(elements = emptyList(), src = src),
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
    fun `MakeSeq 10 20 30 yields MezzeSeq of size 3`() {
        val result = run(buildSeqMain())
        assertEquals(true, result is MezzeSeq<*>)
        val seq = result as MezzeSeq<*>
        assertEquals(3, seq.size())
        assertEquals(10L, seq.get(0))
        assertEquals(20L, seq.get(1))
        assertEquals(30L, seq.get(2))
    }

    @Test
    fun `MakeSeq empty yields MezzeSeq of size 0`() {
        val result = run(buildEmptySeq())
        assertEquals(true, result is MezzeSeq<*>)
        assertEquals(0, (result as MezzeSeq<*>).size())
    }
}
