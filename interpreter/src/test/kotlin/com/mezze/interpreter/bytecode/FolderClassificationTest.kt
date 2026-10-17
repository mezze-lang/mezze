package com.mezze.interpreter.bytecode

import com.mezze.interpreter.nodes.builtin.dsl.NativeBootstrap
import com.mezze.language.truffleir.CallNativeIR
import com.mezze.language.truffleir.FieldIR
import com.mezze.language.truffleir.InlineOp
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.ReadLocalIR
import com.mezze.language.truffleir.SlotKind
import com.mezze.language.truffleir.SourceInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A fold's folder that is a NATIVE call over its two params classifies as the
 * native's inline op.
 *
 * Under `mez build` the interpreter is not on the compiler's classpath, so the
 * native registry that says `__native_int_add` IS `ADD` is empty there and the
 * compiler cannot collapse the call. A mono-specialised folder
 * `{acc, x} -> add(acc, x)` therefore reaches the emitter as
 * `__native_int_add { l = acc, r = x }` — recognised here, where the registry
 * is loaded, or the fold does not fuse.
 */
class FolderClassificationTest {

    private val src = SourceInfo(0, 1, 1, 1, 1)
    private fun read(slot: Int) = ReadLocalIR(slot, SlotKind.LONG, src)
    private fun native(name: String, vararg fields: Pair<String, Int>) =
        CallNativeIR(name, MakeRecordIR(fields.map { (n, s) -> FieldIR(n, read(s)) }, src), src)

    @BeforeEach
    fun loadRegistry() = NativeBootstrap.ensureLoaded()

    @Test
    fun `a native add over the two params is an ADD folder`() {
        val shape = BytecodeCompiler.classifyFolderBody(native("__native_int_add", "l" to 0, "r" to 1), 0, 1)
        assertEquals(InlineOp.ADD, shape?.op)
        assertEquals(0, shape?.leftSlot)
        assertEquals(1, shape?.rightSlot)
    }

    @Test
    fun `operands follow the signature, not the record's field order`() {
        val shape = BytecodeCompiler.classifyFolderBody(native("__native_int_subtract", "r" to 1, "l" to 0), 0, 1)
        assertEquals(InlineOp.SUB, shape?.op)
        assertEquals(0, shape?.leftSlot)
        assertEquals(1, shape?.rightSlot)
    }

    @Test
    fun `a native with no inline op is not a folder`() {
        assertNull(BytecodeCompiler.classifyFolderBody(native("__native_int_sign", "n" to 0), 0, 1))
    }
}
