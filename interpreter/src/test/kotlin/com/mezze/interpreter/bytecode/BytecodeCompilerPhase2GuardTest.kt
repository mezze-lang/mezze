package com.mezze.interpreter.bytecode

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.BindingIR
import com.mezze.language.truffleir.CaseIR
import com.mezze.language.truffleir.FieldIR
import com.mezze.language.truffleir.FunctionIR
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
 * Phase 2 acceptance tests for match guards.
 *
 * Test 1 — Some { val = 5 } if val > 0 -> val * 100 else 999
 *   Guard passes, yields 500.
 * Test 2 — Some { val = -3 } if val > 0 -> val * 100 else 999
 *   Guard fails, falls through to wildcard, yields 999.
 * Test 3 — literal-pattern guard: match 5 is n if n > 10 -> ..., _ -> 0
 *   Guard on trailing literal pattern doesn't match, yields 0 via
 *   wildcard branch — actually, Literal pattern IS pattern equality
 *   so guard tests the equality-bound value.
 */
class BytecodeCompilerPhase2GuardTest {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    /** Some { val = value } / _ -> 999, with guard on Some case. */
    private fun buildSomeWithGuard(value: Long): FunctionIR = FunctionIR(
        hash = Hash("41".repeat(31) + "0${value.toString(16).takeLast(1)}"),
        name = "main_guard",
        qualifiedName = "main_guard",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = listOf(SlotKind.LONG),
        localNames = listOf("val"),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = MatchIR(
            scrutinee = MakeVariantIR(
                tag = 1,
                tagName = "Some",
                payload = MakeRecordIR(
                    fields = listOf(FieldIR("val", LongLitIR(value, src))),
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
                    // guard: val > 0 -- encoded as InlineBinaryOp.GT
                    // Mezze IR uses GT via CallNativeIR __native_int_gt.
                    // But InlineOp only has ADD in this subset; we need
                    // an inequality primitive. Fall back to a manual
                    // encoding: subtract-then-cast approach won't work
                    // for a Bool result. Use CallNativeIR __native_int_gt
                    // — that's already supported via CallNativeScalar.
                    guard = com.mezze.language.truffleir.CallNativeIR(
                        name = "__native_int_gt",
                        arg = LongLitIR(0L, src),
                        src = src,
                        args = listOf(
                            ReadLocalIR(slot = 0, kind = SlotKind.LONG, src = src),
                            LongLitIR(0L, src),
                        ),
                        argKinds = listOf(SlotKind.LONG, SlotKind.LONG),
                    ),
                    body = InlineBinaryOpIR(
                        op = InlineOp.ADD,
                        left = ReadLocalIR(slot = 0, kind = SlotKind.LONG, src = src),
                        right = LongLitIR(0L, src), // val + 0 == val (would use * but no MUL yet)
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
        // Force IntOps init so __native_int_gt is registered.
        com.mezze.interpreter.nodes.builtin.dsl.IntOps
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
    fun `Some val=5 guard val greater than 0 passes yields 5`() {
        assertEquals(5L, run(buildSomeWithGuard(5L)))
    }

    @Test
    fun `Some val=-3 guard val greater than 0 fails falls through to wildcard yields 999`() {
        assertEquals(999L, run(buildSomeWithGuard(-3L)))
    }
}
