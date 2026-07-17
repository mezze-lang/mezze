package com.mezze.interpreter.bytecode

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.InlineBinaryOpIR
import com.mezze.language.truffleir.InlineOp
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 1 walking skeleton — lower a hand-built [FunctionIR] to BDSL
 * and execute it end-to-end via [BytecodeCompiler].
 *
 * The Kotlin test references only [BytecodeCompiler] (Java) — no
 * direct reference to the generated `MezzeBytecodeRootNodeGen`, so
 * Kotlin's default compile-order doesn't cause resolution problems.
 *
 * Task #278, plan doc docs/perf/bytecode-dsl-migration-2026-07.md.
 */
class BytecodeCompilerTest {

    private val srcInfo = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    private fun makeFn(body: com.mezze.language.truffleir.NodeIR): FunctionIR =
        FunctionIR(
            hash = Hash("0".repeat(64)),
            name = "test",
            qualifiedName = "test",
            paramSlots = emptyList(),
            paramNames = emptyList(),
            localSlots = emptyList(),
            localNames = emptyList(),
            captureSlots = emptyList(),
            captureNames = emptyList(),
            body = body,
            sourceFile = "test.mz",
            sourceSection = srcInfo,
        )

    /**
     * Enter a Mezze polyglot Context, run the block with whatever
     * [MezzeLanguage] instance the current thread resolves (possibly
     * null — see [MezzeBytecodeRootNodeTest]; the BDSL generator
     * tolerates null during construction).
     */
    private fun <T> withContext(block: (MezzeLanguage?) -> T): T {
        val context = Context.newBuilder("mezze")
            .allowExperimentalOptions(true)
            .allowAllAccess(true)
            .build()
        try {
            context.enter()
            try {
                @Suppress("UNCHECKED_CAST")
                val language: MezzeLanguage? = MezzeLanguage.get(null) as MezzeLanguage?
                return block(language)
            } finally {
                context.leave()
            }
        } finally {
            context.close()
        }
    }

    @Test
    fun `supports rejects any node type outside phase 1 scope`() {
        val doubleLit = com.mezze.language.truffleir.DoubleLitIR(1.5, srcInfo)
        val body = InlineBinaryOpIR(
            op = InlineOp.ADD,
            left = LongLitIR(1L, srcInfo),
            right = doubleLit,
            src = srcInfo,
        )
        withContext { lang ->
            assertFalse(BytecodeCompiler(lang).supports(makeFn(body)))
        }
    }

    @Test
    fun `supports accepts pure long-add tree`() {
        val body = InlineBinaryOpIR(
            op = InlineOp.ADD,
            left = LongLitIR(1L, srcInfo),
            right = LongLitIR(2L, srcInfo),
            src = srcInfo,
        )
        withContext { lang ->
            assertTrue(BytecodeCompiler(lang).supports(makeFn(body)))
        }
    }

    @Test
    fun `compile and execute 1 plus 2 yields 3`() {
        val body = InlineBinaryOpIR(
            op = InlineOp.ADD,
            left = LongLitIR(1L, srcInfo),
            right = LongLitIR(2L, srcInfo),
            src = srcInfo,
        )
        withContext { lang ->
            val root = BytecodeCompiler(lang).compile(makeFn(body))
            val result = root.callTarget.call()
            assertEquals(3L, result)
        }
    }

    @Test
    fun `compile and execute nested add`() {
        // (1 + 2) + (3 + 4) = 10
        val body = InlineBinaryOpIR(
            op = InlineOp.ADD,
            left = InlineBinaryOpIR(
                op = InlineOp.ADD,
                left = LongLitIR(1L, srcInfo),
                right = LongLitIR(2L, srcInfo),
                src = srcInfo,
            ),
            right = InlineBinaryOpIR(
                op = InlineOp.ADD,
                left = LongLitIR(3L, srcInfo),
                right = LongLitIR(4L, srcInfo),
                src = srcInfo,
            ),
            src = srcInfo,
        )
        withContext { lang ->
            val root = BytecodeCompiler(lang).compile(makeFn(body))
            val result = root.callTarget.call()
            assertEquals(10L, result)
        }
    }
}
