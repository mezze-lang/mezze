package com.mezze.interpreter.bytecode

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.BindingIR
import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.CaseIR
import com.mezze.language.truffleir.FieldIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.IfIR
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
import com.mezze.language.truffleir.StmtIR
import com.mezze.language.truffleir.WriteLocalIR
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase 2 acceptance test for variants — hand-builds FunctionIRs for
 * variant-tagged match without needing to route through Mezze's Option
 * type sugar. Proves MakeVariantSinglePayload + MakeVariantNoPayload +
 * VariantTagEquals + UnwrapSinglePayload + ReadFieldByName all wire up
 * end-to-end through BDSL.
 *
 * The equivalent Mezze code the IR mimics:
 *   let v = Some { val = 41 }        // MakeVariantSinglePayload(tag=1, "Some", MakeRecord(["val"], [41]))
 *   match v is
 *     Some { val } -> val + 1
 *     _ -> 0
 * Expected: 42.
 */
class BytecodeCompilerPhase2VariantAcceptanceTest {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    /** paramSlots=0, locals=[val, wildcardBinding], and the body:
     *
     *   do
     *     let match_result =
     *       match (Some { val = 41 })
     *         Some { val } -> val + 1      (val binds to local slot 0)
     *         _ -> 0                       (wildcard, no bind here — slot -1)
     *     match_result
     *
     * We use two locals: slot 0 for `val`, slot 1 for `match_result`.
     */
    private fun buildMain(): FunctionIR = FunctionIR(
        hash = Hash("c".repeat(64)),
        name = "main",
        qualifiedName = "main",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = listOf(SlotKind.LONG, SlotKind.LONG),
        localNames = listOf("val", "match_result"),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = BlockIR(
            stmts = listOf(
                StmtIR.Let(
                    slot = 1,
                    kind = SlotKind.LONG,
                    name = "match_result",
                    contentHash = Hash("0".repeat(64)),
                    value = MatchIR(
                        scrutinee = MakeVariantIR(
                            tag = 1,
                            tagName = "Some",
                            payload = MakeRecordIR(
                                fields = listOf(FieldIR("val", LongLitIR(41L, src))),
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
                                body = InlineBinaryOpIR(
                                    op = InlineOp.ADD,
                                    left = ReadLocalIR(slot = 0, kind = SlotKind.LONG, src = src),
                                    right = LongLitIR(1L, src),
                                    src = src,
                                ),
                                src = src,
                            ),
                            CaseIR(
                                pattern = PatternIR.Wildcard(),
                                bindings = emptyList(),
                                guard = null,
                                body = LongLitIR(0L, src),
                                src = src,
                            ),
                        ),
                        src = src,
                    ),
                    src = src,
                ),
            ),
            result = ReadLocalIR(slot = 1, kind = SlotKind.LONG, src = src),
            src = src,
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    /** Same shape but scrutinee is None — expects wildcard branch, 0. */
    private fun buildNoneCase(): FunctionIR = FunctionIR(
        hash = Hash("d".repeat(64)),
        name = "main_none",
        qualifiedName = "main_none",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = listOf(SlotKind.LONG, SlotKind.LONG),
        localNames = listOf("val", "match_result"),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = BlockIR(
            stmts = listOf(
                StmtIR.Let(
                    slot = 1,
                    kind = SlotKind.LONG,
                    name = "match_result",
                    contentHash = Hash("0".repeat(64)),
                    value = MatchIR(
                        scrutinee = MakeVariantIR(
                            tag = 2,
                            tagName = "None",
                            payload = null,
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
                                body = ReadLocalIR(slot = 0, kind = SlotKind.LONG, src = src),
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
                    src = src,
                ),
            ),
            result = ReadLocalIR(slot = 1, kind = SlotKind.LONG, src = src),
            src = src,
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    @Test
    fun `match Some -- val - val + 1 yields 42`() {
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
                val roots = compiler.compileWithCallees(listOf(buildMain()))
                assertEquals(42L, roots[0].callTarget.call())
            } finally {
                context.leave()
            }
        } finally {
            context.close()
        }
    }

    @Test
    fun `match None -- wildcard fires yielding 999`() {
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
                val roots = compiler.compileWithCallees(listOf(buildNoneCase()))
                assertEquals(999L, roots[0].callTarget.call())
            } finally {
                context.leave()
            }
        } finally {
            context.close()
        }
    }
}
