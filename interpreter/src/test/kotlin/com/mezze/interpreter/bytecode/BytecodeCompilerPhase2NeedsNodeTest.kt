package com.mezze.interpreter.bytecode

import com.mezze.interpreter.nodes.builtin.dsl.NativeHandler
import com.mezze.interpreter.nodes.builtin.dsl.NativeHandlers
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
import kotlin.test.assertNotNull

/**
 * Phase 2 acceptance test for the needsNode native path.
 *
 * Registers a synthetic "__test_needs_node_probe" native that reads
 * PositionalArgs.callNode (which should be non-null since we're
 * calling through CallNativeScalarWithNode) and returns its
 * identityHashCode as a Long. Verifies BDSL threads the
 * IndirectCallNode through the handler.
 */
class BytecodeCompilerPhase2NeedsNodeTest {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    companion object {
        const val OP_NAME = "__test_needs_node_probe"

        init {
            val handler = NativeHandler { args ->
                val callNode = args.callNode
                // Sanity: callNode should be non-null on the needsNode path.
                // Return a sentinel long: 1 if we got a callNode, 0 otherwise.
                if (callNode != null) 42L else 0L
            }
            try {
                NativeHandlers.register(OP_NAME, handler, needsNode = true)
            } catch (_: IllegalStateException) {
                // Already registered from a previous test run in same JVM.
            }
        }
    }

    private fun buildMain(): FunctionIR = FunctionIR(
        hash = Hash("81".repeat(32)),
        name = "main_probe",
        qualifiedName = "main_probe",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = CallNativeIR(
            name = OP_NAME,
            arg = LongLitIR(0L, src),
            src = src,
            args = emptyList(),
            argKinds = listOf(SlotKind.OBJECT), // scalar-ABI marker
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    @Test
    fun `needsNode native receives IndirectCallNode via CallNativeScalarWithNode`() {
        // Force class-load to run the companion init.
        assertNotNull(NativeHandlers.handlerOf(OP_NAME))
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
}
