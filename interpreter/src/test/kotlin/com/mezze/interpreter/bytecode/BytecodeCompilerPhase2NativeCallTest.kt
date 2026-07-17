package com.mezze.interpreter.bytecode

import com.mezze.interpreter.nodes.builtin.dsl.IntOps
import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.CallNativeIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.SlotKind
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase 2 acceptance test for CallNativeIR — hand-builds an IR that
 * invokes `__native_int_add(3, 4)` and verifies the result is 7.
 *
 * References [IntOps] as a Kotlin `object` up front to force the
 * NativeOp registration (which happens on first class-load). Without
 * that, the NativeHandlers registry lookup in supports() would return
 * null and BDSL would reject the compile.
 */
class BytecodeCompilerPhase2NativeCallTest {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    init {
        // Force IntOps init so its NativeOp constructors register the
        // handlers into NativeHandlers before any BDSL compile runs.
        @Suppress("UNUSED_EXPRESSION")
        IntOps
    }

    /**
     * Build main:
     *   __native_int_add(3, 4)
     * Expected: 7L.
     */
    private fun buildMain(): FunctionIR = FunctionIR(
        hash = Hash("33".repeat(32)),
        name = "main",
        qualifiedName = "main",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = CallNativeIR(
            name = "__native_int_add",
            arg = LongLitIR(0L, src), // unused on scalar-ABI path
            src = src,
            args = listOf(LongLitIR(3L, src), LongLitIR(4L, src)),
            argKinds = listOf(SlotKind.LONG, SlotKind.LONG),
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    @Test
    fun `native int add 3 4 yields 7`() {
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
                assertEquals(7L, roots[0].callTarget.call())
            } finally {
                context.leave()
            }
        } finally {
            context.close()
        }
    }
}
