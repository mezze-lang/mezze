package com.mezze.interpreter.bytecode

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.BindingIR
import com.mezze.language.truffleir.CaseIR
import com.mezze.language.truffleir.FieldIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.IfLetBindingIR
import com.mezze.language.truffleir.InlineBinaryOpIR
import com.mezze.language.truffleir.InlineOp
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.MakeVariantIR
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
 * Phase 2 acceptance test for match if-let bindings.
 *
 * Setup:
 *   match (Some { val = 7 }) is
 *     Some { val }
 *       if let Some { val = extra } = (Some { val = 100 })  -> val + extra
 *     _ -> 999
 *
 * The iflet matches (Some payload with val=100), binds extra=100,
 * body computes val + extra = 7 + 100 = 107.
 *
 * Test 2: iflet fails (source is None), falls through to wildcard.
 */
class BytecodeCompilerPhase2IfLetTest {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    /**
     * Build a match with a Some { val } case that has an iflet on
     * a separately-constructed Option; wildcard case yields 999.
     * iletHits controls whether the iflet's expr is Some (should hit)
     * or None (should miss, fall through).
     */
    private fun buildIfLet(iletHits: Boolean): FunctionIR = FunctionIR(
        hash = Hash("61".repeat(31) + if (iletHits) "01" else "00"),
        name = "main_iflet",
        qualifiedName = "main_iflet",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = listOf(SlotKind.LONG, SlotKind.LONG),
        localNames = listOf("val", "extra"),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = MatchIR(
            scrutinee = MakeVariantIR(
                tag = 1,
                tagName = "Some",
                payload = MakeRecordIR(
                    fields = listOf(FieldIR("val", LongLitIR(7L, src))),
                    src = src,
                ),
                src = src,
            ),
            cases = listOf(
                CaseIR(
                    pattern = PatternIR.Variant(
                        tag = 1,
                        tagName = "Some",
                        fieldNames = listOf("val"),
                        fieldSlots = listOf(0),
                    ),
                    bindings = listOf(BindingIR(slot = 0, kind = SlotKind.LONG)),
                    guard = null,
                    ifLetBindings = listOf(
                        IfLetBindingIR(
                            expr = if (iletHits) MakeVariantIR(
                                tag = 1,
                                tagName = "Some",
                                payload = MakeRecordIR(
                                    fields = listOf(FieldIR("val", LongLitIR(100L, src))),
                                    src = src,
                                ),
                                src = src,
                            ) else MakeVariantIR(
                                tag = 2,
                                tagName = "None",
                                payload = null,
                                src = src,
                            ),
                            pattern = PatternIR.Variant(
                                tag = 1,
                                tagName = "Some",
                                fieldNames = listOf("val"),
                                fieldSlots = listOf(1),
                            ),
                            fieldNames = listOf("val"),
                            fieldSlots = listOf(1),
                            src = src,
                        ),
                    ),
                    body = InlineBinaryOpIR(
                        op = InlineOp.ADD,
                        left = ReadLocalIR(slot = 0, kind = SlotKind.LONG, src = src),
                        right = ReadLocalIR(slot = 1, kind = SlotKind.LONG, src = src),
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
    fun `if-let Some binds extra 100 val 7 body yields 107`() {
        assertEquals(107L, run(buildIfLet(iletHits = true)))
    }

    @Test
    fun `if-let None mismatch falls through to wildcard yields 999`() {
        assertEquals(999L, run(buildIfLet(iletHits = false)))
    }
}
