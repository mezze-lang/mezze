package com.mezze.language.truffleir

import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * `transformChildren` must reach EVERY child slot. The compiler forces each node
 * TYPE to be listed; this pins that each listed type hands over all of its
 * children — a slot left out would be skipped by every pass built on the walker.
 */
class IRWalkTest {

    private val src = SourceInfo(0, 1, 1, 1, 1)
    private val h = Hash("0".repeat(128))
    private fun marker() = LongLitIR(1, src)
    private val lambda = MakeLambdaIR(h, emptyList(), src)

    /** Every node kind that has children, with a marker in each child slot. */
    private fun everything(): NodeIR = BlockIR(
        stmts = listOf(
            StmtIR.Let(0, SlotKind.OBJECT, "x", h, marker(), src),
            StmtIR.Expr(marker(), src),
            StmtIR.Expr(InterpolatedStringIR(listOf(StringPartIR.Lit("a"), StringPartIR.Expr(marker())), src), src),
            StmtIR.Expr(WriteLocalIR(0, SlotKind.LONG, marker(), src), src),
            StmtIR.Expr(CallDirectIR(h, listOf(marker(), marker()), false, src), src),
            StmtIR.Expr(PartialApplyDirectIR(h, listOf(FieldIR("f", marker())), false, src), src),
            StmtIR.Expr(CallLocalIR(0, marker(), false, src), src),
            StmtIR.Expr(CallNativeIR("__native_x", marker(), src, args = listOf(marker())), src),
            StmtIR.Expr(CallIndirectIR(marker(), marker(), false, src), src),
            StmtIR.Expr(InspectIR(marker(), false, PrintType.Frac, src), src),
            StmtIR.Expr(CallIndirectScalarIR(marker(), listOf(marker()), listOf("a"), false, src), src),
            StmtIR.Expr(DictMethodCallIR(0, marker(), src), src),
            StmtIR.Expr(InlineBinaryOpIR(InlineOp.ADD, marker(), marker(), src), src),
            StmtIR.Expr(SpecializedCallIR(SpecializedOp.SEQ_LENGTH, listOf(marker()), src), src),
            StmtIR.Expr(MakeRecordIR(listOf(FieldIR("a", marker())), src), src),
            StmtIR.Expr(ReadFieldIR(marker(), "a", 0, src), src),
            StmtIR.Expr(RecordUpdateIR(marker(), listOf(FieldIR("a", marker())), src), src),
            StmtIR.Expr(MakeVariantIR(0, "Some", marker(), src), src),
            StmtIR.Expr(MatchIR(marker(), listOf(
                CaseIR(PatternIR.Literal(marker()), emptyList(), marker(), marker(), src,
                    listOf(IfLetBindingIR(marker(), PatternIR.Literal(marker()), emptyList(), emptyList(), src))),
            ), src), src),
            StmtIR.Expr(MakeSeqIR(listOf(SeqElementIR.Regular(marker()), SeqElementIR.Spread(marker())), src), src),
            StmtIR.Expr(SeqGetIR(marker(), marker(), src), src),
            StmtIR.Expr(IfIR(marker(), marker(), marker(), src), src),
            StmtIR.Expr(FusedChainIR(SourceIR.RangeStepSeq(marker(), marker(), marker()),
                listOf(StageIR.Take(marker()), StageIR.Drop(marker()), StageIR.Map(lambda)),
                SinkIR.Fold(marker(), lambda), src, fallback = marker()), src),
            StmtIR.Expr(FusedChainIR(SourceIR.Opaque(marker()), emptyList(), SinkIR.Scan(marker(), lambda), src), src),
            StmtIR.Expr(HandleIR(marker(), listOf(HandlerBindIR(h, h, "P", marker())), src), src),
            StmtIR.Expr(PerformOpIR(h, "op", marker(), src), src),
        ),
        result = marker(),
        src = src,
    )

    private fun countMarkers(node: NodeIR): Int {
        var n = if (node is LongLitIR && node.value == 1L) 1 else 0
        node.forEachChildIR { n += countMarkers(it) }
        return n
    }

    @Test
    fun `every child slot of every node is handed to the transform`() {
        val rewritten = everything().transformBottomUp { if (it is LongLitIR && it.value == 1L) LongLitIR(2, src) else it }
        assertFalse(rewritten.toString().contains("LongLitIR(value=1,"), "a child slot was skipped:\n$rewritten")
    }

    @Test
    fun `the read-only visit sees the same children`() {
        // The count is what a skipped slot would change.
        val placed = Regex("LongLitIR\\(value=1,").findAll(everything().toString()).count()
        assertEquals(placed, countMarkers(everything()))
    }
}
