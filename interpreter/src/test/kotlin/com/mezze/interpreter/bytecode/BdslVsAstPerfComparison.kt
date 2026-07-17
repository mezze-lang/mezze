package com.mezze.interpreter.bytecode

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.BlockIR
import com.mezze.language.truffleir.CallDirectIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.InlineBinaryOpIR
import com.mezze.language.truffleir.InlineOp
import com.mezze.language.truffleir.LongLitIR
import com.mezze.language.truffleir.ReadLocalIR
import com.mezze.language.truffleir.SlotKind
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.truffleir.StmtIR
import com.mezze.language.typedast.Hash
import com.oracle.truffle.api.CallTarget
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Apples-to-apples micro-benchmark: identical FunctionIR programs
 * compiled twice — once via BDSL BytecodeCompiler, once via the AST
 * TruffleCompiler — then invoked in a Java loop to measure per-call
 * throughput.
 *
 * Not a JMH bench — we do enough warmup for Truffle JIT to fire and
 * report 5 median-of-5 timings for each.
 *
 * Programs:
 *   add(a, b) = a + b                — direct add
 *   sum3(a, b, c) = a + b + c        — nested add
 *   caller() = { let x = 1 + 2; x }  — locals + let
 *
 * Report format:
 *   [name]  ast_ns=A  bdsl_ns=B  ratio=B/A  verdict
 */
class BdslVsAstPerfComparison {

    private val src = SourceInfo(fileIndex = 0, startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    private fun buildAdd(): FunctionIR = FunctionIR(
        hash = Hash("b0".repeat(32)),
        name = "add",
        qualifiedName = "add",
        paramSlots = listOf(SlotKind.LONG, SlotKind.LONG),
        paramNames = listOf("a", "b"),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = InlineBinaryOpIR(
            op = InlineOp.ADD,
            left = ReadLocalIR(slot = 0, kind = SlotKind.LONG, src = src),
            right = ReadLocalIR(slot = 1, kind = SlotKind.LONG, src = src),
            src = src,
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    private fun buildSum3(): FunctionIR = FunctionIR(
        hash = Hash("b1".repeat(32)),
        name = "sum3",
        qualifiedName = "sum3",
        paramSlots = listOf(SlotKind.LONG, SlotKind.LONG, SlotKind.LONG),
        paramNames = listOf("a", "b", "c"),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = emptyList(),
        captureNames = emptyList(),
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
        sourceFile = "test.mz",
        sourceSection = src,
    )

    /** caller() { let x = 1 + 2; x } — measures locals + let overhead. */
    private fun buildCallerLocals(): FunctionIR = FunctionIR(
        hash = Hash("b2".repeat(32)),
        name = "caller_locals",
        qualifiedName = "caller_locals",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = listOf(SlotKind.LONG),
        localNames = listOf("x"),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = BlockIR(
            stmts = listOf(
                StmtIR.Let(
                    slot = 0,
                    kind = SlotKind.LONG,
                    name = "x",
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
        sourceFile = "test.mz",
        sourceSection = src,
    )

    /** main() { add(3, 4) } — measures 2-arg direct call. */
    private fun buildAddCaller(): FunctionIR = FunctionIR(
        hash = Hash("b3".repeat(32)),
        name = "add_caller",
        qualifiedName = "add_caller",
        paramSlots = emptyList(),
        paramNames = emptyList(),
        localSlots = emptyList(),
        localNames = emptyList(),
        captureSlots = emptyList(),
        captureNames = emptyList(),
        body = CallDirectIR(
            targetHash = Hash("b0".repeat(32)),
            args = listOf(LongLitIR(3L, src), LongLitIR(4L, src)),
            isTailCall = false,
            src = src,
            isScalarABI = true,
        ),
        sourceFile = "test.mz",
        sourceSection = src,
    )

    // ────── measurement plumbing ──────

    private data class Result(val name: String, val astNs: Long, val bdslNs: Long) {
        val ratio: Double get() = bdslNs.toDouble() / astNs.toDouble()
        override fun toString(): String {
            val verdict = when {
                ratio < 0.9 -> "BDSL FASTER"
                ratio > 1.1 -> "AST FASTER"
                else -> "roughly equal"
            }
            return String.format("  %-24s  ast=%6d ns  bdsl=%6d ns  ratio=%.2f  %s",
                name, astNs, bdslNs, ratio, verdict)
        }
    }

    private fun timeIt(iters: Int, fn: () -> Unit): Long {
        // Warmup — many invocations to let Truffle profile + compile.
        for (w in 0 until 10) {
            for (i in 0 until iters) fn()
        }
        // 5 timed runs, take median.
        val timings = LongArray(5)
        for (i in 0 until 5) {
            System.gc()
            Thread.sleep(20)
            val start = System.nanoTime()
            for (j in 0 until iters) fn()
            timings[i] = (System.nanoTime() - start) / iters
        }
        timings.sort()
        return timings[2]
    }

    private fun benchOne(name: String, iters: Int, callArgs: Array<Any>,
                        astRoots: List<com.oracle.truffle.api.nodes.RootNode>,
                        bdslRoots: List<com.oracle.truffle.api.nodes.RootNode>,
                        entryIdx: Int): Result {
        val astTarget = astRoots[entryIdx].callTarget
        val bdslTarget = bdslRoots[entryIdx].callTarget
        val astNs = timeIt(iters) { astTarget.call(*callArgs) }
        val bdslNs = timeIt(iters) { bdslTarget.call(*callArgs) }
        return Result(name, astNs, bdslNs)
    }

    @Test
    fun `bench BDSL vs AST on identical FunctionIRs`() {
        val context = Context.newBuilder("mezze")
            .allowExperimentalOptions(true)
            .allowAllAccess(true)
            .build()
        try {
            // Boot the language by evaluating a trivial expression.
            context.eval("mezze", "1")
            context.enter()
            try {
                @Suppress("UNCHECKED_CAST")
                val language: MezzeLanguage = MezzeLanguage.get(null) as MezzeLanguage
                val bdslCompiler = BytecodeCompiler(language)
                val astCompiler = com.mezze.interpreter.compiler.TruffleCompiler(language)

                // Compile each program via BOTH pipelines.
                // Leaf-only benches — no cross-function calls.
                // (AST's CallDirectScalarNode does global registry lookup
                // which isn't populated by direct compilation, so we can't
                // fairly compare cross-function-call paths here.)
                val programs = listOf(
                    Triple("add(1, 2)", listOf(buildAdd()), 0),
                    Triple("sum3(1, 2, 3)", listOf(buildSum3()), 0),
                    Triple("caller_locals", listOf(buildCallerLocals()), 0),
                )
                val results = mutableListOf<Result>()
                for ((name, irs, entryIdx) in programs) {
                    // AST side compiles one FunctionIR at a time.
                    val astRoots = irs.map { astCompiler.compile(it) }
                    val bdslRoots = bdslCompiler.compileWithCallees(irs)

                    val args: Array<Any> = when (name) {
                        "add(1, 2)" -> arrayOf(1L, 2L)
                        "sum3(1, 2, 3)" -> arrayOf(1L, 2L, 3L)
                        else -> emptyArray()
                    }
                    // Sanity — both should return the same value.
                    val astResult = astRoots[entryIdx].callTarget.call(*args)
                    val bdslResult = bdslRoots[entryIdx].callTarget.call(*args)
                    // Normalize numerics.
                    val a = if (astResult is Number) astResult.toLong() else astResult
                    val b = if (bdslResult is Number) bdslResult.toLong() else bdslResult
                    assertEquals(a, b, "$name: AST=$astResult BDSL=$bdslResult")

                    // Bench.
                    val iters = 100_000
                    results.add(benchOne(name, iters, args, astRoots, bdslRoots, entryIdx))
                }

                // Write to a file (per CLAUDE.md convention) + println.
                val out = StringBuilder()
                out.appendLine("=== BDSL vs AST micro-bench (per-call ns) ===")
                out.appendLine("(all measurements 100K-iter loops after 10× warmup, median of 5)")
                out.appendLine()
                for (r in results) out.appendLine(r)
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
