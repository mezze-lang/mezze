package com.mezze.language.truffleir

import com.mezze.language.ast.OperatorMapping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every operator has an inline form, and there is only one table saying so.
 *
 * Two copies of this mapping would drift SILENTLY: a new operator would simply
 * stop folding into arithmetic, and the only symptom would be that programs
 * using it got slower — no error, no failing assertion, nothing to read. So the
 * binary half is derived from [OperatorMapping.BINARY_OPERATORS] and this pins
 * that the derivation covers all of it.
 */
class InlineOpMappingTest {

    @Test
    fun `every binary operator has an inline form`() {
        val missing = InlineOpMapping.symbolsWithoutInlineForm()
        assertTrue(missing.isEmpty(), "operators with no inline form: $missing")
    }

    @Test
    fun `every operator method resolves`() {
        for (op in OperatorMapping.BINARY_OPERATORS.values) {
            assertNotNull(
                InlineOpMapping.forAbilityMethod(op.method),
                "no inline form for ${op.symbol} (${op.method})",
            )
        }
    }

    @Test
    fun `the unary operations resolve too`() {
        // Listed by hand, because OperatorMapping covers binary operators only.
        assertEquals(InlineOp.NEG, InlineOpMapping.forAbilityMethod("neg"))
        assertEquals(InlineOp.NOT, InlineOpMapping.forAbilityMethod("not"))
    }

    @Test
    fun `an ability method that is not an operator does not resolve`() {
        assertEquals(null, InlineOpMapping.forAbilityMethod("to_str"))
        assertEquals(null, InlineOpMapping.forAbilityMethod("encode"))
    }

    private val src = SourceInfo(0, 1, 1, 1, 1)

    @Test
    fun `the desugared operator folds back to arithmetic`() {
        // `i - 1` as it lowers when the receiver's type is still a variable.
        val i = ReadLocalIR(0, SlotKind.LONG, src)
        val call = CallIndirectIR(
            ReadFieldIR(i, "sub", -1, src),
            MakeRecordIR(listOf(FieldIR("r", LongLitIR(1, src))), src),
            false, src,
        )
        val folded = InlineOpMapping.foldAbilityOp(call)
        assertTrue(folded is InlineBinaryOpIR, "did not fold: $folded")
        assertEquals(InlineOp.SUB, (folded as InlineBinaryOpIR).op)
        assertEquals(i, folded.left)
    }

    @Test
    fun `a TAIL call is not folded`() {
        // The caller's position is not the callee's, the same reason the
        // direct-call inliner refuses one.
        val i = ReadLocalIR(0, SlotKind.LONG, src)
        val call = CallIndirectIR(
            ReadFieldIR(i, "sub", -1, src),
            MakeRecordIR(listOf(FieldIR("r", LongLitIR(1, src))), src),
            true, src,
        )
        assertTrue(InlineOpMapping.foldAbilityOp(call) is CallIndirectIR, "a tail call was folded")
    }

    @Test
    fun `a call through a field that is not an operator is left alone`() {
        val r = ReadLocalIR(0, SlotKind.OBJECT, src)
        val call = CallIndirectIR(
            ReadFieldIR(r, "handler", -1, src),
            MakeRecordIR(listOf(FieldIR("r", LongLitIR(1, src))), src),
            false, src,
        )
        assertTrue(InlineOpMapping.foldAbilityOp(call) is CallIndirectIR, "an ordinary call was folded")
    }
}
