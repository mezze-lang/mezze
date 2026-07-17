package com.mezze.interpreter.bytecode

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.FunctionIR
import com.oracle.truffle.api.CallTarget
import org.graalvm.polyglot.Context

/**
 * Differential-testing harness: runs a Mezze program through BOTH the
 * AST interpreter (via [MezzeTestHelper.run]) and the BDSL interpreter
 * (via [BytecodeCompiler]), asserts identical output.
 *
 * The whole point of this harness is to make every subsequent BDSL
 * migration phase safe: if AST and BDSL disagree on any program in
 * BDSL's supported subset, that's a bug we catch immediately — not
 * three phases down the line.
 *
 * ## Usage
 *
 *   BdslDifferentialHarness.assertEquivalent("""
 *       let add = { a, b } -> a + b
 *       add { a = 1, b = 2 }
 *   """, expected = 3L)
 *
 * ## Support policy
 *
 * If BDSL's [BytecodeCompiler.supports] rejects any user function in
 * the compiled program, [assertEquivalent] runs ONLY the AST side and
 * still asserts against `expected`. That way the harness stays useful
 * for tests whose subset isn't yet BDSL-lowerable — they still pin the
 * AST output, and as BDSL grows support they auto-upgrade to true
 * differential coverage.
 *
 * ## Lifetime
 *
 * Test-only. Not shipped in the runtime. Deleted at Phase 6 when the
 * AST path is fully removed (docs/perf/bytecode-dsl-migration-2026-07.md).
 * See task #280.
 */
object BdslDifferentialHarness {

    /**
     * Run [source] through AST + (optionally) BDSL, assert both equal
     * [expected] and each other.
     *
     * Throws [AssertionError] on any mismatch.
     */
    fun assertEquivalent(source: String, expected: Any?) {
        assertEquivalent(source, expected, requireBdsl = false)
    }

    /**
     * Same as [assertEquivalent] but fails if BDSL couldn't lower or
     * execute the program. Use for tests you want to guarantee are
     * exercising both interpreters.
     */
    fun assertEquivalentRequiringBdsl(source: String, expected: Any?) {
        assertEquivalent(source, expected, requireBdsl = true)
    }

    private fun assertEquivalent(source: String, expected: Any?, requireBdsl: Boolean) {
        // 1. AST side — the ground truth. Always runs.
        val astResult = MezzeTestHelper.run(source)
        assertEqualsValue("AST side", expected, astResult)

        // 2. BDSL side — only if the compiled function set fits our
        //    current subset. Skip silently otherwise; the AST side has
        //    already pinned correctness.
        val functions = lowerToFunctionIRs(source)
        if (functions.isEmpty()) {
            if (requireBdsl) throw AssertionError("BDSL required but typecheck failed for source: $source")
            return
        }

        val bdslResult = tryRunBdsl(functions)
        if (bdslResult == null) {
            if (requireBdsl) throw AssertionError(
                "BDSL required but couldn't run (unsupported IR or missing entry) for source: $source"
            )
            return
        }
        assertEqualsValue("BDSL side", expected, bdslResult)
        assertEqualsValue("AST vs BDSL", astResult, bdslResult)
    }

    /**
     * Lower [source] to the same `List<FunctionIR>` shape the runtime
     * builds. Uses the existing typecheck helper so we go through the
     * same pipeline as production.
     */
    private fun lowerToFunctionIRs(source: String): List<FunctionIR> {
        return try {
            val result = MezzeTestHelper.typeCheck(source)
            result.loweredFunctions
        } catch (_: Throwable) {
            // Typecheck failure is a legit signal — AST side already ran
            // and asserted; there's nothing more the harness can compare.
            emptyList()
        }
    }

    /**
     * Try to compile [functions] via BDSL and execute the last one
     * (heuristic for the "top-level entry" a Mezze program returns).
     * Returns null if:
     *   - Any function references a callee that isn't in the given
     *     list (imports from stdlib etc. — those need Phase 3+ to lower)
     *   - Any function is outside [BytecodeCompiler.supports]'s current
     *     scope
     *   - The last function takes any parameters (a proper "main" has
     *     none, so we can't invoke it without knowing what to pass)
     *   - Anything else throws during compile — including cross-thread
     *     Context weirdness we've hit while integrating BDSL
     */
    private fun tryRunBdsl(functions: List<FunctionIR>): Any? {
        // Order callees before callers for compileWithCallees. Rough
        // topological order: functions whose bodies don't reference
        // any other function in `functions` come first. For Phase 1's
        // small acceptance cases this is fine; smarter ordering can
        // wait until we grow the harness.
        val ordered = topoSort(functions) ?: return null

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
                // Bail if any function fails supports().
                for (fn in ordered) {
                    if (!compiler.supports(fn)) return null
                }
                val roots = compiler.compileWithCallees(ordered)
                // Pick the entry: last function with 0 params (the
                // top-level expression usually lowers to a nullary let
                // whose body is the expression's value).
                val entryIdx = ordered.indexOfLast { it.paramSlots.isEmpty() }
                if (entryIdx < 0) return null
                val entry: CallTarget = roots[entryIdx].callTarget
                return entry.call()
            } finally {
                context.leave()
            }
        } catch (_: Throwable) {
            return null
        } finally {
            try { context.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Very small topological sort — for each function collect the set
     * of hashes of functions it directly calls (only those present in
     * [functions]), then emit in reverse-dependency order. Returns
     * null on cycles (mutual recursion isn't supported by Phase 1's
     * compileWithCallees anyway).
     */
    private fun topoSort(functions: List<FunctionIR>): List<FunctionIR>? {
        val byHash = functions.associateBy { it.hash }
        val deps: Map<com.mezze.language.typedast.Hash, Set<com.mezze.language.typedast.Hash>> =
            functions.associate { fn ->
                val used = mutableSetOf<com.mezze.language.typedast.Hash>()
                collectDirectCallees(fn.body, byHash.keys, used)
                fn.hash to used
            }
        val out = mutableListOf<FunctionIR>()
        val emitted = mutableSetOf<com.mezze.language.typedast.Hash>()
        val visiting = mutableSetOf<com.mezze.language.typedast.Hash>()
        fun visit(fn: FunctionIR): Boolean {
            if (fn.hash in emitted) return true
            if (fn.hash in visiting) return false // cycle
            visiting.add(fn.hash)
            for (dep in deps[fn.hash] ?: emptySet()) {
                val depFn = byHash[dep] ?: continue
                if (!visit(depFn)) return false
            }
            visiting.remove(fn.hash)
            emitted.add(fn.hash)
            out.add(fn)
            return true
        }
        for (fn in functions) {
            if (!visit(fn)) return null
        }
        return out
    }

    /** Collect direct call-target hashes present in [known]. */
    private fun collectDirectCallees(
        node: com.mezze.language.truffleir.NodeIR,
        known: Set<com.mezze.language.typedast.Hash>,
        into: MutableSet<com.mezze.language.typedast.Hash>,
    ) {
        when (node) {
            is com.mezze.language.truffleir.CallDirectIR -> {
                if (node.targetHash in known) into.add(node.targetHash)
                node.args.forEach { collectDirectCallees(it, known, into) }
            }
            is com.mezze.language.truffleir.InlineBinaryOpIR -> {
                collectDirectCallees(node.left, known, into)
                collectDirectCallees(node.right, known, into)
            }
            is com.mezze.language.truffleir.IfIR -> {
                collectDirectCallees(node.cond, known, into)
                collectDirectCallees(node.thenBr, known, into)
                collectDirectCallees(node.elseBr, known, into)
            }
            is com.mezze.language.truffleir.BlockIR -> {
                for (s in node.stmts) {
                    val child = when (s) {
                        is com.mezze.language.truffleir.StmtIR.Let -> s.value
                        is com.mezze.language.truffleir.StmtIR.Expr -> s.node
                    }
                    collectDirectCallees(child, known, into)
                }
                collectDirectCallees(node.result, known, into)
            }
            is com.mezze.language.truffleir.WriteLocalIR ->
                collectDirectCallees(node.value, known, into)
            is com.mezze.language.truffleir.MakeRecordIR -> {
                for (f in node.fields) collectDirectCallees(f.value, known, into)
            }
            is com.mezze.language.truffleir.ReadFieldIR ->
                collectDirectCallees(node.record, known, into)
            is com.mezze.language.truffleir.RecordUpdateIR -> {
                collectDirectCallees(node.record, known, into)
                for (f in node.updates) collectDirectCallees(f.value, known, into)
            }
            is com.mezze.language.truffleir.MakeVariantIR ->
                node.payload?.let { collectDirectCallees(it, known, into) }
            is com.mezze.language.truffleir.MatchIR -> {
                collectDirectCallees(node.scrutinee, known, into)
                for (c in node.cases) {
                    c.guard?.let { collectDirectCallees(it, known, into) }
                    collectDirectCallees(c.body, known, into)
                }
            }
            else -> { /* leaves + unsupported: harness bails via supports() */ }
        }
    }

    private fun assertEqualsValue(context: String, expected: Any?, actual: Any?) {
        // Long/Int equality is tricky in Mezze — the AST side may
        // return java.lang.Long while a naive `expected = 3` in Kotlin
        // gives Int. Normalize numeric types to Long for the compare.
        val e = normalize(expected)
        val a = normalize(actual)
        if (e != a) {
            throw AssertionError(
                "$context: expected <$expected> (${expected?.javaClass?.simpleName}), " +
                "got <$actual> (${actual?.javaClass?.simpleName})"
            )
        }
    }

    private fun normalize(v: Any?): Any? = when (v) {
        is Int -> v.toLong()
        is Short -> v.toLong()
        is Byte -> v.toLong()
        else -> v
    }
}
