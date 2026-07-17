package com.mezze.interpreter.bytecode

import com.mezze.interpreter.runtime.MezzeContext
import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.interpreter.values.MezzeFunction
import com.mezze.language.truffleir.BindingIR
import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.CallDirectIR
import com.mezze.language.truffleir.CallNativeIR
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
import com.mezze.language.truffleir.ReadFieldIR
import com.mezze.language.truffleir.ReadLocalIR
import com.mezze.language.truffleir.SlotKind
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.truffleir.StmtIR
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test

/**
 * Apples-to-apples micro-benchmark: identical FunctionIR programs
 * compiled twice — once via BDSL BytecodeCompiler, once via the AST
 * TruffleCompiler — invoked in a hot Java loop, per-call ns reported.
 *
 * Coverage matrix (what we're actually measuring, not "3 trivial cases"):
 *   NUMERIC      — add/sum3       : baseline call + arith
 *   LOCALS       — caller_locals  : let-bind + read
 *   RECORDS      — build + read   : where AST has typed storage, BDSL all-Object
 *   MATCH        — Option match   : where AST has PIC on scrutinee shape
 *   NATIVES      — __native_int_add : where AST has DS-Wired hot paths
 *   CROSS-FN     — direct call    : populates registry so AST's resolveFunction works
 *
 * These are the categories we NEED to be at least at parity in before
 * BDSL can replace AST end-to-end (goal: BDSL becomes the only path).
 */
class BdslVsAstPerfComparison {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    // ─── programs ───────────────────────────────────────────────

    private fun buildAdd(): FunctionIR = FunctionIR(
        hash = Hash("b0".repeat(32)),
        name = "add",
        qualifiedName = "add",
        paramSlots = listOf(SlotKind.LONG, SlotKind.LONG),
        paramNames = listOf("a", "b"),
        localSlots = emptyList(), localNames = emptyList(),
        captureSlots = emptyList(), captureNames = emptyList(),
        body = InlineBinaryOpIR(
            op = InlineOp.ADD,
            left = ReadLocalIR(slot = 0, kind = SlotKind.LONG, src = src),
            right = ReadLocalIR(slot = 1, kind = SlotKind.LONG, src = src),
            src = src,
        ),
        sourceFile = "b.mz", sourceSection = src,
    )

    private fun buildSum3(): FunctionIR = FunctionIR(
        hash = Hash("b1".repeat(32)),
        name = "sum3", qualifiedName = "sum3",
        paramSlots = listOf(SlotKind.LONG, SlotKind.LONG, SlotKind.LONG),
        paramNames = listOf("a", "b", "c"),
        localSlots = emptyList(), localNames = emptyList(),
        captureSlots = emptyList(), captureNames = emptyList(),
        body = InlineBinaryOpIR(
            op = InlineOp.ADD,
            left = InlineBinaryOpIR(
                op = InlineOp.ADD,
                left = ReadLocalIR(slot = 0, kind = SlotKind.LONG, src = src),
                right = ReadLocalIR(slot = 1, kind = SlotKind.LONG, src = src),
                src = src,
            ),
            right = ReadLocalIR(slot = 2, kind = SlotKind.LONG, src = src),
            src = src,
        ),
        sourceFile = "b.mz", sourceSection = src,
    )

    private fun buildCallerLocals(): FunctionIR = FunctionIR(
        hash = Hash("b2".repeat(32)),
        name = "caller_locals", qualifiedName = "caller_locals",
        paramSlots = emptyList(), paramNames = emptyList(),
        localSlots = listOf(SlotKind.LONG), localNames = listOf("x"),
        captureSlots = emptyList(), captureNames = emptyList(),
        body = BlockIR(
            stmts = listOf(
                StmtIR.Let(
                    slot = 0, kind = SlotKind.LONG, name = "x",
                    contentHash = Hash("0".repeat(64)),
                    value = InlineBinaryOpIR(
                        op = InlineOp.ADD,
                        left = LongLitIR(1L, src),
                        right = LongLitIR(2L, src),
                        src = src,
                    ),
                    src = src,
                ),
            ),
            result = ReadLocalIR(slot = 0, kind = SlotKind.LONG, src = src),
            src = src,
        ),
        sourceFile = "b.mz", sourceSection = src,
    )

    /** Record: build { x=1, y=2 } then read .x + .y. */
    private fun buildRecordReadSum(): FunctionIR = FunctionIR(
        hash = Hash("b4".repeat(32)),
        name = "rec_read_sum", qualifiedName = "rec_read_sum",
        paramSlots = emptyList(), paramNames = emptyList(),
        localSlots = listOf(SlotKind.OBJECT), localNames = listOf("r"),
        captureSlots = emptyList(), captureNames = emptyList(),
        body = BlockIR(
            stmts = listOf(
                StmtIR.Let(
                    slot = 0, kind = SlotKind.OBJECT, name = "r",
                    contentHash = Hash("0".repeat(64)),
                    value = MakeRecordIR(
                        fields = listOf(
                            FieldIR("x", LongLitIR(1L, src)),
                            FieldIR("y", LongLitIR(2L, src)),
                        ),
                        src = src,
                    ),
                    src = src,
                ),
            ),
            result = InlineBinaryOpIR(
                op = InlineOp.ADD,
                left = ReadFieldIR(
                    record = ReadLocalIR(slot = 0, kind = SlotKind.OBJECT, src = src),
                    fieldName = "x", fieldIndex = 0, src = src,
                ),
                right = ReadFieldIR(
                    record = ReadLocalIR(slot = 0, kind = SlotKind.OBJECT, src = src),
                    fieldName = "y", fieldIndex = 1, src = src,
                ),
                src = src,
            ),
            src = src,
        ),
        sourceFile = "b.mz", sourceSection = src,
    )

    /** Match: Some { val = 7 } → val + 1 / _ → 0. Measures match-dispatch overhead. */
    private fun buildMatchOption(): FunctionIR = FunctionIR(
        hash = Hash("b5".repeat(32)),
        name = "match_opt", qualifiedName = "match_opt",
        paramSlots = emptyList(), paramNames = emptyList(),
        localSlots = listOf(SlotKind.LONG), localNames = listOf("val"),
        captureSlots = emptyList(), captureNames = emptyList(),
        body = MatchIR(
            scrutinee = MakeVariantIR(
                tag = 1, tagName = "Some",
                payload = MakeRecordIR(
                    fields = listOf(FieldIR("val", LongLitIR(7L, src))),
                    src = src,
                ),
                src = src,
            ),
            cases = listOf(
                CaseIR(
                    pattern = PatternIR.Variant(
                        tag = 1, tagName = "Some",
                        fieldNames = listOf("val"), fieldSlots = listOf(0),
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
                    bindings = emptyList(), guard = null,
                    body = LongLitIR(0L, src),
                    src = src,
                ),
            ),
            src = src,
        ),
        sourceFile = "b.mz", sourceSection = src,
    )

    /** Native call: __native_int_add(3, 4). */
    private fun buildNativeCall(): FunctionIR = FunctionIR(
        hash = Hash("b6".repeat(32)),
        name = "native_add", qualifiedName = "native_add",
        paramSlots = emptyList(), paramNames = emptyList(),
        localSlots = emptyList(), localNames = emptyList(),
        captureSlots = emptyList(), captureNames = emptyList(),
        body = CallNativeIR(
            name = "__native_int_add",
            arg = LongLitIR(0L, src),
            src = src,
            args = listOf(LongLitIR(3L, src), LongLitIR(4L, src)),
            argKinds = listOf(SlotKind.LONG, SlotKind.LONG),
        ),
        sourceFile = "b.mz", sourceSection = src,
    )

    /** Cross-fn: main() calls add(3, 4). */
    private fun buildAddCaller(): FunctionIR = FunctionIR(
        hash = Hash("b3".repeat(32)),
        name = "add_caller", qualifiedName = "add_caller",
        paramSlots = emptyList(), paramNames = emptyList(),
        localSlots = emptyList(), localNames = emptyList(),
        captureSlots = emptyList(), captureNames = emptyList(),
        body = CallDirectIR(
            targetHash = Hash("b0".repeat(32)),
            args = listOf(LongLitIR(3L, src), LongLitIR(4L, src)),
            isTailCall = false,
            src = src,
            isScalarABI = true,
        ),
        sourceFile = "b.mz", sourceSection = src,
    )

    // ─── measurement plumbing ───────────────────────────────────

    private data class Result(val name: String, val astNs: Long, val bdslNs: Long) {
        val ratio: Double get() = bdslNs.toDouble() / astNs.toDouble()
        override fun toString(): String {
            val verdict = when {
                ratio < 0.85 -> "BDSL FASTER"
                ratio > 1.15 -> "AST FASTER"
                else -> "roughly equal"
            }
            return String.format("  %-24s  ast=%6d ns  bdsl=%6d ns  ratio=%.2f  %s",
                name, astNs, bdslNs, ratio, verdict)
        }
    }

    private fun timeIt(iters: Int, fn: () -> Unit): Long {
        for (w in 0 until 10) for (i in 0 until iters) fn()
        val timings = LongArray(5)
        for (i in 0 until 5) {
            System.gc(); Thread.sleep(20)
            val start = System.nanoTime()
            for (j in 0 until iters) fn()
            timings[i] = (System.nanoTime() - start) / iters
        }
        timings.sort()
        return timings[2]
    }

    @Test
    fun `bench BDSL vs AST across every category we care about`() {
        val context = Context.newBuilder("mezze")
            .allowExperimentalOptions(true)
            .allowAllAccess(true)
            .build()
        try {
            context.eval("mezze", "1") // boot the language
            // Force IntOps init so __native_int_add is registered.
            @Suppress("UNUSED_EXPRESSION")
            com.mezze.interpreter.nodes.builtin.dsl.IntOps
            context.enter()
            try {
                @Suppress("UNCHECKED_CAST")
                val language: MezzeLanguage = MezzeLanguage.get(null) as MezzeLanguage
                val bdslCompiler = BytecodeCompiler(language)
                val astCompiler = com.mezze.interpreter.compiler.TruffleCompiler(language)
                val mezzeCtx: MezzeContext = language.getCurrentContext()

                // Compile every program via both pipelines.
                val astAdd = astCompiler.compile(buildAdd())
                val astSum3 = astCompiler.compile(buildSum3())
                val astLocals = astCompiler.compile(buildCallerLocals())
                val astRec = astCompiler.compile(buildRecordReadSum())
                val astMatch = astCompiler.compile(buildMatchOption())
                val astNative = astCompiler.compile(buildNativeCall())
                val astAdd2 = astCompiler.compile(buildAdd())  // second compile of add for cross-fn
                val astCaller = astCompiler.compile(buildAddCaller())

                val bdslPerCategory = mapOf(
                    "numeric_add"   to bdslCompiler.compileWithCallees(listOf(buildAdd())),
                    "numeric_sum3"  to bdslCompiler.compileWithCallees(listOf(buildSum3())),
                    "locals_let"    to bdslCompiler.compileWithCallees(listOf(buildCallerLocals())),
                    "record_build_read" to bdslCompiler.compileWithCallees(listOf(buildRecordReadSum())),
                    "match_option"  to bdslCompiler.compileWithCallees(listOf(buildMatchOption())),
                    "native_int_add" to bdslCompiler.compileWithCallees(listOf(buildNativeCall())),
                )
                // Cross-fn: BDSL bakes callee's CallTarget; AST looks up global by hash.
                // Register add's AST target as a global MezzeFunction so
                // CallDirectScalarNode.resolveFunction can find it.
                val addFn = MezzeFunction.create(astAdd2.callTarget, "add")
                mezzeCtx.defineGlobal(Hash("b0".repeat(32)), addFn)
                val bdslCross = bdslCompiler.compileWithCallees(listOf(buildAdd(), buildAddCaller()))

                val results = mutableListOf<Result>()
                fun runOne(name: String, iters: Int, args: Array<Any>,
                           astTarget: com.oracle.truffle.api.CallTarget,
                           bdslTarget: com.oracle.truffle.api.CallTarget) {
                    val astNs = timeIt(iters) { astTarget.call(*args) }
                    val bdslNs = timeIt(iters) { bdslTarget.call(*args) }
                    results.add(Result(name, astNs, bdslNs))
                }

                val iters = 100_000
                runOne("numeric_add", iters, arrayOf(1L, 2L),
                    astAdd.callTarget, bdslPerCategory["numeric_add"]!![0].callTarget)
                runOne("numeric_sum3", iters, arrayOf(1L, 2L, 3L),
                    astSum3.callTarget, bdslPerCategory["numeric_sum3"]!![0].callTarget)
                runOne("locals_let", iters, emptyArray(),
                    astLocals.callTarget, bdslPerCategory["locals_let"]!![0].callTarget)
                runOne("record_build_read", iters, emptyArray(),
                    astRec.callTarget, bdslPerCategory["record_build_read"]!![0].callTarget)
                runOne("match_option", iters, emptyArray(),
                    astMatch.callTarget, bdslPerCategory["match_option"]!![0].callTarget)
                runOne("native_int_add", iters, emptyArray(),
                    astNative.callTarget, bdslPerCategory["native_int_add"]!![0].callTarget)
                runOne("cross_fn_call", iters, emptyArray(),
                    astCaller.callTarget, bdslCross[1].callTarget)

                val out = StringBuilder()
                out.appendLine("=== BDSL vs AST — full category matrix (per-call ns) ===")
                out.appendLine("(100K-iter loop, 10× warmup, median of 5, MEZZE_JIT=1 recommended)")
                out.appendLine()
                for (r in results) out.appendLine(r)
                out.appendLine()
                out.appendLine("Verdict rule: ratio<0.85 → BDSL wins; ratio>1.15 → AST wins; else parity.")
                val text = out.toString()
                println(text)
                java.io.File("/tmp/bdsl-vs-ast-bench.txt").writeText(text)
            } finally {
                context.leave()
            }
        } finally {
            context.close()
        }
    }
}
