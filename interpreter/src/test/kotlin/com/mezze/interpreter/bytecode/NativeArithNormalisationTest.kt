package com.mezze.interpreter.bytecode

import com.mezze.interpreter.nodes.builtin.dsl.NativeBootstrap
import com.mezze.language.truffleir.CallNativeIR
import com.mezze.language.truffleir.FieldIR
import com.mezze.language.truffleir.InlineBinaryOpIR
import com.mezze.language.truffleir.InlineOp
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.ReadLocalIR
import com.mezze.language.truffleir.SlotKind
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.truffleir.UnitLitIR
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A stage lambda whose body is a NATIVE arith/compare call normalises to the
 * inline op it denotes.
 *
 * WHY THIS EXISTS. Under `mez build` the interpreter is not on the compiler's
 * classpath, so the registry that says `__native_int_lt` IS `LT` is empty there
 * and the compiler cannot collapse the call. `classifyFolderBody` already
 * recognised the form — see FolderClassificationTest — but the UNARY
 * classifiers did not, so a predicate like `{x} -> x < n` with `n` captured was
 * unclassifiable wherever monomorphisation had not inlined the impl. An
 * unclassifiable stage declines the WHOLE chain.
 *
 * WHAT IT COST. A pipeline reached through a higher-order parameter ran at
 * 41 600–71 000 ps/elem against 90 called directly: the chain fell back to the
 * lazy walker, `IterNatives.foldRange`, which was caught OSR-compiling in the
 * slow run and absent from the fast one. Writing the types out made it fast,
 * which is the signature of a cliff inference alone should not create — fusion
 * must not depend on whether mono happened to fire. After this: 86–101 ps,
 * level with the direct call and with the annotated version. `seq` map+filter
 * went 229 -> 92 and a compound fold 1448 -> 877 with it, since both met the
 * same unclassifiable shape.
 *
 * WHY THE ORDER CASES. A record evaluates its fields in the order they are
 * WRITTEN; an InlineBinaryOp evaluates left then right. An operand can be a
 * call, so collapsing a record whose fields were written in the other order
 * would swap two evaluations — a wrong answer for anything effectful, and
 * invisible for anything pure. The guard is the subtle half of this change, so
 * it gets its own cases.
 */
class NativeArithNormalisationTest {

    private val src = SourceInfo(0, 1, 1, 1, 1)
    private fun read(slot: Int) = ReadLocalIR(slot, SlotKind.LONG, src)
    private fun lit(v: Long) = LongLitIR(v, src)

    private fun recordNative(name: String, vararg fields: Pair<String, com.mezze.language.truffleir.NodeIR>) =
        CallNativeIR(name, MakeRecordIR(fields.map { (n, v) -> FieldIR(n, v) }, src), src)

    @BeforeEach
    fun loadRegistry() = NativeBootstrap.ensureLoaded()

    @Test
    fun `a native less-than over param and literal becomes an LT inline op`() {
        val out = BytecodeCompiler.normalizeNativeArith(
            recordNative("__native_int_lt", "l" to read(0), "r" to lit(40)),
        )
        assertTrue(out is InlineBinaryOpIR, "expected an inline op, got ${out::class.simpleName}")
        val bin = out as InlineBinaryOpIR
        assertEquals(InlineOp.LT, bin.op)
        assertEquals(read(0), bin.left)
        assertEquals(lit(40), bin.right)
    }

    @Test
    fun `a native multiply over two params becomes a MUL inline op`() {
        val out = BytecodeCompiler.normalizeNativeArith(
            recordNative("__native_int_multiply", "l" to read(0), "r" to read(1)),
        )
        val bin = out as InlineBinaryOpIR
        assertEquals(InlineOp.MUL, bin.op)
        assertEquals(read(0), bin.left)
        assertEquals(read(1), bin.right)
    }

    @Test
    fun `fields written in the OTHER order are left alone`() {
        // A REAL native, so this cannot pass vacuously on an unknown name:
        // `__native_int_subtract` with `l` first DOES collapse, asserted below.
        // `r` before `l` means the record evaluates the right operand first.
        // Collapsing would reorder two evaluations, so the body is returned as
        // it came — correct and unfused rather than fast and wrong.
        val body = recordNative("__native_int_subtract", "r" to read(1), "l" to read(0))
        assertSame(body, BytecodeCompiler.normalizeNativeArith(body))

        // The control: the same native with the fields written in signature
        // order collapses, so the refusal above is the ORDER and nothing else.
        val ordered = recordNative("__native_int_subtract", "l" to read(0), "r" to read(1))
        assertEquals(
            InlineOp.SUB,
            (BytecodeCompiler.normalizeNativeArith(ordered) as InlineBinaryOpIR).op,
        )
    }

    @Test
    fun `a scalar-ABI native collapses without an order question`() {
        // Positional args are already in operand order, so there is nothing to
        // reorder and the guard does not apply.
        // Scalar ABI is `argKinds` being populated — `arg` is then unused.
        val body = CallNativeIR(
            "__native_int_add", UnitLitIR(src), src,
            args = listOf(read(0), read(1)),
            argKinds = listOf(SlotKind.LONG, SlotKind.LONG),
        )
        val bin = BytecodeCompiler.normalizeNativeArith(body) as InlineBinaryOpIR
        assertEquals(InlineOp.ADD, bin.op)
    }

    @Test
    fun `a native with no inline-op twin is left alone`() {
        // Two params, but `seq::get` is not an operator — nothing to collapse to.
        val body = recordNative("__native_seq_get", "xs" to read(0), "i" to read(1))
        assertSame(body, BytecodeCompiler.normalizeNativeArith(body))
    }

    @Test
    fun `a body that is not a native call is returned unchanged`() {
        val body = InlineBinaryOpIR(InlineOp.ADD, read(0), read(1), src)
        assertSame(body, BytecodeCompiler.normalizeNativeArith(body))
    }

    @Test
    fun `a record with the wrong field count is left alone`() {
        // A partially applied or spread-bearing record is not an operand pair.
        val body = recordNative("__native_int_add", "l" to read(0))
        assertSame(body, BytecodeCompiler.normalizeNativeArith(body))
    }
}
