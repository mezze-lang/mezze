package com.mezze.language.comptime

import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.CallDirectIR
import com.mezze.language.truffleir.PrintType
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.typedast.Hash

/**
 * Replaces every `comptime` call with the value it computes.
 *
 * ## Why this is not part of type checking
 *
 * Type checking needs a comptime block's TYPE, never its VALUE — `'a` is
 * inferred from the thunk's body like any other call, and `Embeddable` is a
 * type-level obligation. The value never feeds back into typing, which is
 * exactly why comptime is barred from type position.
 *
 * So evaluation belongs to code generation:
 *
 *  - `mez check` and the LSP type-check and stop. They never execute user code,
 *    so an editor cannot be hung by a runaway comptime, cannot be made to write
 *    files, and does not run a dependency's code while you type.
 *  - `mez build` / `dev` / `exec` reduce.
 *
 * The price, worth writing down: `check` is strictly weaker than `build`. A
 * comptime that crashes or never terminates passes the first and fails the
 * second. `Embeddable` is checked by both, so the gap is exactly "crash" and
 * "does not finish".
 *
 * ## No fixpoint
 *
 * Comptime alone needs neither re-type-checking nor a driver loop: the value
 * spliced in is a literal whose type the solver already assigned to the call, so
 * nothing new enters the program. (Macros will need both. This does not.)
 * Nested comptime reduces innermost-first simply by rewriting each function's
 * body bottom-up.
 *
 * ## Degrading rather than failing
 *
 * A site the evaluator declines is LEFT ALONE, and an untouched `comptime` call
 * still computes the right answer at run time — that is what the declaration's
 * `eval {}` body is for. So a gap in reification costs performance, never
 * correctness.
 */
object ComptimeReduction {

    /** Why a site could not be reduced. Reported, never swallowed. */
    data class Failure(val where: SourceInfo?, val message: String, val guestFrames: List<String>)

    data class Result(val functions: List<FunctionIR>, val failures: List<Failure>)

    /**
     * Reduce, or explain why not — the form every caller actually wants.
     *
     * Returns [functions] unchanged when this process has no evaluator (the LSP,
     * `mez check`: a type-check-only tool must never run user code) and when a
     * thunk's own evaluation is what is running (see [Comptime.isEvaluating]).
     * A site the evaluator declines is left alone and still computes correctly
     * at run time. A site that FAILED is a real error, and is thrown.
     *
     * Single-sourced because it has to happen in two places that share no code:
     * the CLI, so a built jar or native image ships already-folded IR, and the
     * runtime, which is where `mez dev` and `mez exec` see the program at all.
     */
    fun reduceOrThrow(
        functions: List<FunctionIR>,
        performers: List<Array<String>>,
    ): List<FunctionIR> {
        if (Comptime.isEvaluating()) return functions
        val evaluator = Comptime.get() ?: return functions

        val result = reduce(functions, performers, evaluator)
        val first = result.failures.firstOrNull() ?: return result.functions

        val message = buildString {
            append("comptime evaluation failed: ").append(first.message)
            for (frame in first.guestFrames) append("\n    at ").append(frame)
        }
        throw ComptimeFailure(message)
    }

    /** A comptime block that raised, or called `compile_error`. */
    class ComptimeFailure(message: String) : RuntimeException(message)

    /**
     * Reduce every comptime call in [functions].
     *
     * A site identifies itself: a call carrying a `comptimeShape` IS a comptime
     * call. No span table and no hash are needed, which is what lets this run in
     * a runtime that never saw the type checker — `mez dev`, `mez exec`, a
     * native binary, the WASM build.
     */
    fun reduce(
        functions: List<FunctionIR>,
        performers: List<Array<String>>,
        evaluator: ComptimeEvaluator,
    ): Result {
        val ir = ComptimeIr()

        // A program with no comptime must not pay for this pass, and must come
        // out byte-identical.
        if (functions.none { ir.hasSite(it.body) }) return Result(functions, emptyList())

        val failures = mutableListOf<Failure>()
        val cache = mutableMapOf<Hash, NodeIR>()
        val reduced = functions.map { fn ->
            val body = ir.rewrite(fn.body) { call ->
                reduceOne(ir, call, functions, performers, evaluator, failures, cache, fn.sourceFile)
            }
            if (body === fn.body) fn else fn.copy(body = body)
        }

        // Every site must be gone. Folding at build IS the feature, so a site
        // that survives is a compiler failure, and it belongs HERE — at build,
        // with a location — not as a program that crashes when someone runs it.
        //
        // This is the check that should have existed from the start. Its absence
        // is why a silent non-fold was indistinguishable from success: the value
        // still came out right, because a site left alone was simply computed at
        // run time instead.
        for (fn in reduced) {
            for (leftover in ir.remainingSites(fn.body)) {
                failures += Failure(
                    leftover,
                    "a comptime block was not evaluated during the build, at " +
                        "${fn.sourceFile}:${leftover?.startLine}:${leftover?.startCol}. " +
                        "Folding at build time is what `comptime` means, so this is a " +
                        "compiler failure rather than an error in your program — please " +
                        "report it.",
                    emptyList(),
                )
            }
        }
        return Result(reduced, failures)
    }

    private fun reduceOne(
        ir: ComptimeIr,
        call: NodeIR,
        functions: List<FunctionIR>,
        performers: List<Array<String>>,
        evaluator: ComptimeEvaluator,
        failures: MutableList<Failure>,
        cache: MutableMap<Hash, NodeIR>,
        sourceFile: String,
    ): NodeIR? {
        val thunk = ir.thunkHashOf(call) ?: return null
        // No shape is not a reason to skip: evaluate anyway. A thunk that raises
        // never reaches reification, and that raise is the author's build error.
        // If a VALUE does come back with no shape to write it against, the
        // reifier says so and the build fails — loudly, and at build.
        val shape = ir.shapeOf(call) ?: PrintType.Dynamic

        // Two comptime blocks with identical bodies are the SAME thunk — the hash
        // is over content — so one written twice is evaluated once.
        //
        // Sound because the key is the thunk's content and the value is the IR it
        // produced. An EFFECTFUL thunk is a different matter ACROSS builds: its
        // hash covers the code, not the world it read. Within one compilation
        // nothing can change underneath it, which is why this memo is scoped here
        // and not persisted.
        cache[thunk]?.let { return it }

        val origin = "$sourceFile:${call.src.startLine}:${call.src.startCol}"
        return when (val outcome = evaluator.evaluate(functions, thunk, shape, performers, origin)) {
            is ComptimeOutcome.Reified -> outcome.node.also { cache[thunk] = it }
            is ComptimeOutcome.Failed -> {
                failures += Failure(call.src, outcome.message, outcome.guestFrames)
                null
            }
        }
    }
}
