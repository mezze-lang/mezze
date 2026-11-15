package com.mezze.interpreter.bytecode

import com.mezze.language.truffleir.FieldIR
import com.mezze.language.truffleir.InlineOp
import com.mezze.language.truffleir.CallNativeIR
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.NativeParam
import com.mezze.language.truffleir.NativeSignature
import com.mezze.language.truffleir.SlotKind
import com.mezze.language.truffleir.SourceInfo
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a scalar native's operands out of its RECORD argument.
 *
 * WHY THIS IS A UNIT TEST AND NOT A PROGRAM. The defect only appears when the
 * NativeSignatures registry is EMPTY at compile time, which is the case under
 * `mez build` (the interpreter module is `compileOnly` in the CLI) and is NOT
 * the case in a test JVM. An end-to-end interpreter test therefore CANNOT
 * reproduce it: the registry is loaded, the lowering-side collapse to
 * `InlineBinaryOpIR` succeeds, and the record never survives to be mishandled.
 *
 * That was measured, not assumed — an allocation-scaling test written first
 * passed identically with the fix disabled. So this tests the changed logic
 * directly, on the IR shape that actually arrives in a built binary:
 *
 *     CallNativeIR(__native_int_add,
 *         arg = MakeRecordIR([l = .., r = ..]),
 *         args = [], inlineOp = null)
 *
 * In a real build that shape came from the stdlib snapshot (`int.mz`'s NumAdd
 * body). Mishandling it cost 48x on a numeric tail loop — 12 ns/iter against
 * 0.2 — because the record both allocated ~100 bytes per iteration AND made
 * `FusedLongTailLoop` silently refuse the enclosing function.
 */
class NativeRecordOperandTest {

    private val src = SourceInfo(0, 1, 1, 1, 1)

    private val intAdd = NativeSignature(
        name = "__native_int_add",
        params = listOf(NativeParam("l", SlotKind.LONG), NativeParam("r", SlotKind.LONG)),
        returns = SlotKind.LONG,
        inlineOp = InlineOp.ADD,
    )

    private fun recordCall(vararg fields: Pair<String, Long>): CallNativeIR =
        CallNativeIR(
            name = "__native_int_add",
            arg = MakeRecordIR(
                fields = fields.map { (n, v) -> FieldIR(n, LongLitIR(v, src)) },
                src = src,
            ),
            src = src,
        )

    @Test
    fun `operands bind by declared parameter name, not field position`() {
        // Written in signature order.
        val inOrder = BytecodeCompiler.fltlRecordOperands(recordCall("l" to 7L, "r" to 3L), intAdd)
        assertEquals(2, inOrder?.size)
        assertEquals(7L, (inOrder!![0] as LongLitIR).value)
        assertEquals(3L, (inOrder[1] as LongLitIR).value)

        // Written in the OTHER order. A record's fields are named, so `l` must
        // still be operand 0 — binding by position here would silently compute
        // `3 - 7` for a subtract.
        val swapped = BytecodeCompiler.fltlRecordOperands(recordCall("r" to 3L, "l" to 7L), intAdd)
        assertEquals(7L, (swapped!![0] as LongLitIR).value)
        assertEquals(3L, (swapped[1] as LongLitIR).value)
    }

    @Test
    fun `a record missing a declared parameter is declined, not guessed`() {
        assertNull(BytecodeCompiler.fltlRecordOperands(recordCall("l" to 7L), intAdd))
        assertNull(
            BytecodeCompiler.fltlRecordOperands(recordCall("l" to 7L, "x" to 3L), intAdd),
        )
        // An extra field would be dropped silently, so decline that too.
        assertNull(
            BytecodeCompiler.fltlRecordOperands(
                recordCall("l" to 7L, "r" to 3L, "extra" to 1L), intAdd,
            ),
        )
    }

    @Test
    fun `a duplicate field name is declined rather than resolved arbitrarily`() {
        // Which `l` wins is not something the emitter should decide.
        assertNull(
            BytecodeCompiler.fltlRecordOperands(recordCall("l" to 7L, "l" to 9L), intAdd),
        )
    }

    @Test
    fun `a native with no inline-op equivalent is declined`() {
        val noOp = intAdd.copy(name = "__native_int_gcd", inlineOp = null)
        assertNull(BytecodeCompiler.fltlRecordOperands(recordCall("l" to 7L, "r" to 3L), noOp))
    }

    /**
     * The EMISSION path additionally requires written order to match declared
     * order, because an `InlineBinaryOp` evaluates left-then-right while a
     * record evaluates its fields as written. Operands can be calls, so
     * collapsing an out-of-order record would reorder two effects.
     */
    @Test
    fun `field order decides whether the emission collapse is safe`() {
        val inOrder = recordCall("l" to 7L, "r" to 3L).arg as MakeRecordIR
        val swapped = recordCall("r" to 3L, "l" to 7L).arg as MakeRecordIR
        assertTrue(BytecodeCompiler.fltlRecordFieldsInSigOrder(inOrder, intAdd))
        assertEquals(false, BytecodeCompiler.fltlRecordFieldsInSigOrder(swapped, intAdd))
    }
}
