package com.mezze.language.comptime

import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.NodeIR
import com.mezze.language.truffleir.PrintType
import com.mezze.language.typedast.Hash

/**
 * Runs a `comptime` thunk while the program is being compiled.
 *
 * ## Why this is an interface in `:ast`
 *
 * The compiler needs the interpreter, and the interpreter must not become a
 * dependency of the compiler: `:typechecker` is on the CLI and LSP class path,
 * and `:interpreter` brings Truffle, an HTTP client, a JSON library and five
 * profiler tools with it. So the two meet here, in a module both already
 * depend on, and neither names the other.
 *
 * `:interpreter` installs an implementation at class-load, the way
 * `NativeBootstrap` installs itself into `NativeSignatures`. When nothing is
 * installed — the LSP, `mez check`, a slim runtime jar — [Comptime.get] is
 * null and the caller reports that cleanly. In practice that path is never hit,
 * because evaluation belongs to code generation and those tools stop before it.
 *
 * ## Why the boundary is IR
 *
 * IR in, IR out. `FunctionIR` and `NodeIR` are `@Serializable`, which is what
 * makes the same interface serve an in-process evaluator, a subprocess over the
 * wire protocol, or a cached result read back from the store — without the
 * compiler ever handling a runtime value, and without foreclosing an
 * out-of-process evaluator if sandboxing ever calls for one.
 */
interface ComptimeEvaluator {

    /**
     * Evaluate the thunk [entry] and hand back IR that rebuilds its result.
     *
     * [functions] must contain [entry] and everything reachable from it, and
     * [performers] every performer it might install.
     * [resultShape] is the thunk's static result shape, computed from the
     * solved, pre-erasure type: the runtime value alone cannot say whether a
     * `Long` is an `Int` or a `Nat`, or which newtype it was wrapped in, so the
     * value is walked against this in lockstep.
     */
    fun evaluate(
        functions: List<FunctionIR>,
        entry: Hash,
        resultShape: PrintType,
        /**
         * The program's effect-performer records.
         *
         * Required, not optional: a handler record is BUILT from these, so
         * without them a thunk that performs a user-defined effect fails with
         * "Handler not found" — the stdlib's own performers included.
         */
        performers: List<Array<String>>,
        /**
         * Where this block was written, as `file:line:col`, for diagnostics.
         *
         * The evaluator cannot derive it: it is handed a thunk hash, and a hash
         * says nothing about where the call sits. The reduction pass knows both
         * the enclosing function's source file and the call node's span, so it
         * passes them down rather than making this guess.
         */
        origin: String,
    ): ComptimeOutcome
}

/** What [ComptimeEvaluator.evaluate] produces. */
sealed interface ComptimeOutcome {

    /** IR that reconstructs the computed value. Usually a literal; for shapes
     *  with no literal form (`Map`, `Set`) a constructor call. */
    data class Reified(val node: NodeIR) : ComptimeOutcome

    /**
     * Evaluation failed: the body raised, or it called `compile_error`.
     *
     * [guestFrames] are Mezze frames, innermost first, for the diagnostic.
     * Host frames are deliberately absent — they belong behind `--verbose` and
     * are of no use to someone reading their own comptime block.
     */
    data class Failed(
        val message: String,
        val guestFrames: List<String> = emptyList(),
    ) : ComptimeOutcome
}

/**
 * The installation point. Holds at most one evaluator for the process.
 */
object Comptime {

    @Volatile
    private var installed: ComptimeEvaluator? = null

    /** Called by `:interpreter` at class-load. Idempotent; last one wins. */
    fun install(evaluator: ComptimeEvaluator) {
        installed = evaluator
    }

    /**
     * The evaluator, or null when this process cannot evaluate.
     *
     * Null is a real, common state, not a failure: the LSP and `mez check` stop
     * before code generation and must never run user code. The runtime installs
     * one when `MezzeLanguage` loads; the CLI installs one explicitly when a
     * build needs to fold.
     */
    fun get(): ComptimeEvaluator? = installed

    /** Test hook: forget the installed evaluator. */
    fun uninstallForTest() {
        installed = null
    }

    private val evaluating = ThreadLocal.withInitial { false }

    /**
     * Whether this thread is INSIDE a comptime evaluation.
     *
     * Evaluating a thunk means running the program again, and the program it
     * runs still contains the comptime call being evaluated — so a reduction
     * pass that fires unconditionally evaluates that call again, forever. The
     * observed symptom is a stack overflow wrapped in two hundred nested
     * "comptime evaluation failed" messages.
     *
     * Skipping reduction in the nested run costs nothing: that run is
     * throwaway, and any comptime inside the thunk still computes the right
     * value through the declaration's own `eval {}` body.
     */
    @JvmStatic
    fun isEvaluating(): Boolean = evaluating.get()

    /** Run [body] with [isEvaluating] true on this thread. */
    fun <T> whileEvaluating(body: () -> T): T {
        val outer = evaluating.get()
        evaluating.set(true)
        try {
            return body()
        } finally {
            evaluating.set(outer)
        }
    }
}
