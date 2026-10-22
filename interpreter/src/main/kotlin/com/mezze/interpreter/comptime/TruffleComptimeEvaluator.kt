package com.mezze.interpreter.comptime

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.comptime.CodebaseHost
import com.mezze.language.comptime.Comptime
import com.mezze.language.comptime.ComptimeEvaluator
import com.mezze.language.comptime.ComptimeOutcome
import com.mezze.language.comptime.HostValue
import com.mezze.language.comptime.InvokeOutcome
import com.mezze.language.comptime.SyntaxHost
import com.mezze.language.truffleir.CallDirectIR
import com.mezze.language.truffleir.CallNativeIR
import com.mezze.language.truffleir.FieldIR
import com.mezze.language.truffleir.MakeRecordIR
import com.mezze.language.truffleir.StringLitIR
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.truffleir.PrintType
import com.mezze.language.truffleir.SourceInfo
import com.mezze.language.typedast.Hash
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import java.nio.file.Paths

/**
 * Runs comptime thunks on the same Truffle interpreter that runs the program.
 *
 * That is the point: a handler at compile time is just a handler, so there is
 * no separate compile-time effect runtime to build or keep in step. It also
 * means a comptime block can do anything the language can do, and the only
 * question left is whether its RESULT can be written down — which `Embeddable`
 * answers, statically, before anything runs.
 *
 * ## A fresh engine per evaluation, for now
 *
 * Sharing one engine across evaluations is the obvious optimisation and it is
 * MEASURABLY WRONG today: a shared engine means a shared `MezzeLanguage`, whose
 * CallTarget caches outlive any one Context, so a caller compiled while
 * evaluating one thunk can be reused against another thunk's IR registry. The
 * symptom is not a crash but a wrong VALUE — folding several comptime blocks in
 * one module produced the same answer for all of them.
 *
 * That is the cross-Context staleness `MezzeLanguage.TEST_ISOLATION` exists for,
 * and the fix there is the same one available here: drop the Language's
 * CallTarget caches between evaluations. Until that is exposed, each evaluation
 * gets its own engine and closes it — an engine also retains every CallTarget it
 * creates, so not closing it leaks. Comptime blocks are few; a wrong answer is
 * not a trade worth making for this.
 *
 * ## Single-threaded, deliberately
 *
 * Evaluation installs effect handlers on the running thread, and three of the
 * four assumption-guarded handler caches still cache thread-local chain state on
 * a shared node (task #1). Until that is settled, evaluating two comptime blocks
 * concurrently could have one read the other's handler. So: one at a time.
 */
class TruffleComptimeEvaluator : ComptimeEvaluator {

    override fun evaluate(
        functions: List<FunctionIR>,
        entry: Hash,
        resultShape: PrintType,
        performers: List<Array<String>>,
        origin: String,
        codebase: CodebaseHost?,
    ): ComptimeOutcome = synchronized(LOCK) {
        withOwnClassLoader { Comptime.whileEvaluating { evaluateLocked(functions, entry, resultShape, performers, origin, codebase) } }
    }

    /** The body of [evaluate]; split out only so the re-entrancy guard wraps it
     *  as a single expression. */
    private fun evaluateLocked(
        functions: List<FunctionIR>,
        entry: Hash,
        resultShape: PrintType,
        performers: List<Array<String>>,
        origin: String,
        codebase: CodebaseHost?,
    ): ComptimeOutcome {
        evaluations.incrementAndGet()
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val engine = newEngine()
        val context = Context.newBuilder(MezzeLanguage.ID)
            .engine(engine)
            .allowAllAccess(true)
            .allowCreateThread(true)
            .allowExperimentalOptions(true)
            .build()
        // The codebase this evaluation may ask about, handed to THIS context only:
        // `std::codebase`'s natives look it up here, so two evaluations never
        // share one, and nothing global holds it.
        if (codebase != null) context.polyglotBindings.putMember(CodebaseNatives.BINDING, codebase)

        // The result channel is a STATIC on MezzeLanguage, and this evaluation is
        // NESTED inside the compilation of the program that will use it. Leaving
        // the store clobbered hands the enclosing run a value computed by a
        // comptime block — or, across a shared engine, by a different program
        // entirely. Save and restore rather than clear.
        val enclosingResult = MezzeLanguage.getLastExecutionResult()
        return try {
            MezzeLanguage.clearLastResult()
            // Make the THUNK the entry, and drop the enclosing program's
            // `__entry__`.
            //
            // Handing the runtime the program's own entry plus a thunk hash does
            // not work, and fails quietly: that path runs `__entry__` for its
            // global-binding side effect and THEN looks the entry up in globals —
            // but a thunk is a lambda, never registered as one. The lookup finds
            // something else, and every comptime in the module folds to the same
            // wrong value. It also means each evaluation re-runs the enclosing
            // module's entire top level, side effects included.
            //
            // Renaming the thunk to `__entry__` instead compiles and runs exactly
            // it. Top-level functions and values the thunk calls are unaffected —
            // they resolve by hash, not through `__entry__`.
            val entryFn = functions.firstOrNull { it.hash == entry }
                ?: return ComptimeOutcome.Failed(
                    "comptime thunk ${entry.hex.take(8)} is not among the compiled functions",
                )
            val forEval = functions.filterNot { it.name == ENTRY_FN } +
                entryFn.copy(name = ENTRY_FN)
            MezzeLanguage.setFunctionsForDirectExecution(
                forEval, performers, projectRoot, null,
            )
            // Bracketed as tightly as the block itself: not engine construction,
            // not reification, and certainly not the parse/typecheck around it —
            // only the guest code the author wrote, actually running.
            val ranFrom = System.nanoTime()
            val watch = startSlowWatch(origin)
            try {
                context.eval(Source.newBuilder(MezzeLanguage.ID, "", "<comptime>").buildLiteral())
            } finally {
                watch.stopAndReport(System.nanoTime() - ranFrom, origin)
            }

            val value = MezzeLanguage.getLastExecutionResult()
            // Suspended on an effect nothing handled: the block — or a question to
            // the compiler — did run-time work, which cannot happen during a build.
            if (value is com.mezze.interpreter.values.MezzeSuspend) {
                return ComptimeOutcome.Failed(
                    "this compile-time evaluation performs an effect that nothing handles while " +
                        "compiling, so it would have to happen at run time instead of during the build. " +
                        "Handle it inside the block, or ask the question on its own and use the answer outside it."
                )
            }
            ComptimeOutcome.Reified(ComptimeReifier.reify(value, resultShape, SYNTHETIC))
        } catch (e: ComptimeReifier.NotEmbeddable) {
            // `Embeddable` admitted a shape reification cannot build. Reported as
            // what it is — a gap between the two — rather than blamed on the user.
            ComptimeOutcome.Failed(e.message ?: "comptime result is not embeddable")
        } catch (e: Throwable) {
            ComptimeOutcome.Failed(describe(e), guestFramesOf(e))
        } finally {
            try { context.close(true) } catch (ignored: Exception) { }
            try { engine.close() } catch (ignored: Exception) { }
            MezzeLanguage.setLastExecutionResultForNestedEval(enclosingResult)
        }
    }

    override fun invoke(
        functions: List<FunctionIR>,
        entry: Hash,
        argument: HostValue.Record,
        performers: List<Array<String>>,
        origin: String,
        syntax: SyntaxHost?,
    ): InvokeOutcome = synchronized(LOCK) {
        withOwnClassLoader { Comptime.whileEvaluating { invokeLocked(functions, entry, argument, performers, origin, syntax) } }
    }

    /**
     * Run [body] with this evaluator's own class loader as the thread's context
     * loader. Truffle finds languages through the CONTEXT loader, and a caller —
     * the CLI, which loads this class from the runtime jar through a child loader
     * — usually has a different one, which brings the engine up with no
     * languages at all ("a language with id 'mezze' is not available"). Doing it
     * here means no caller has to remember to.
     */
    private inline fun <T> withOwnClassLoader(body: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = TruffleComptimeEvaluator::class.java.classLoader
        try {
            return body()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    /**
     * The body of [invoke]. The call is the program's whole entry: a synthetic
     * `__entry__` that calls [entry] with a record whose every field is read,
     * through a native, from [argument] — so the argument crosses as data, with
     * no IR built for it however large it is.
     */
    private fun invokeLocked(
        functions: List<FunctionIR>,
        entry: Hash,
        argument: HostValue.Record,
        performers: List<Array<String>>,
        origin: String,
        syntax: SyntaxHost?,
    ): InvokeOutcome {
        invocations.incrementAndGet()
        if (functions.none { it.hash == entry }) {
            return InvokeOutcome.Failed("function ${entry.hex.take(8)} is not among the compiled functions")
        }
        val call = CallDirectIR(
            targetHash = entry,
            args = listOf(MakeRecordIR(
                argument.fields.map { (name, _) ->
                    FieldIR(name, CallNativeIR(
                        CodebaseNatives.ARGUMENT_NATIVE,
                        MakeRecordIR(listOf(FieldIR("field", StringLitIR(name, SYNTHETIC))), SYNTHETIC),
                        SYNTHETIC,
                    ))
                },
                SYNTHETIC,
            )),
            isTailCall = false,
            src = SYNTHETIC,
        )
        val entryFn = FunctionIR(
            // Its own identity, derived from what it calls, so it can never be
            // mistaken for a function of the program.
            hash = Hash(java.security.MessageDigest.getInstance("SHA-256")
                .digest("invoke:${entry.hex}".toByteArray())
                .joinToString("") { "%02x".format(it) }),
            name = ENTRY_FN, qualifiedName = ENTRY_FN,
            paramSlots = emptyList(), paramNames = emptyList(),
            localSlots = emptyList(), localNames = emptyList(),
            captureSlots = emptyList(), captureNames = emptyList(),
            body = call, sourceFile = origin, sourceSection = SYNTHETIC,
        )
        val projectRoot = Paths.get(System.getProperty("mezze.project.root", "."))
        val engine = newEngine()
        val context = Context.newBuilder(MezzeLanguage.ID)
            .engine(engine)
            .allowAllAccess(true)
            .allowCreateThread(true)
            .allowExperimentalOptions(true)
            .build()
        context.polyglotBindings.putMember(CodebaseNatives.ARGUMENT_BINDING, argument)
        if (syntax != null) context.polyglotBindings.putMember(CodebaseNatives.SYNTAX_BINDING, syntax)
        val enclosingResult = MezzeLanguage.getLastExecutionResult()
        return try {
            MezzeLanguage.clearLastResult()
            MezzeLanguage.setFunctionsForDirectExecution(
                functions.filterNot { it.name == ENTRY_FN } + entryFn, performers, projectRoot, null,
            )
            context.eval(Source.newBuilder(MezzeLanguage.ID, "", "<macro>").buildLiteral())
            val value = MezzeLanguage.getLastExecutionResult()
            if (value is com.mezze.interpreter.values.MezzeSuspend) {
                InvokeOutcome.Failed(
                    "this performs an effect that nothing handles while compiling. A macro runs " +
                        "while its caller is compiled — in the editor too — so it must be pure: " +
                        "handle the effect inside it, or compute the value with `comptime` instead."
                )
            } else {
                InvokeOutcome.Returned(CodebaseNatives.plain(value))
            }
        } catch (e: Throwable) {
            InvokeOutcome.Failed(describe(e), guestFramesOf(e))
        } finally {
            try { context.close(true) } catch (ignored: Exception) { }
            try { engine.close() } catch (ignored: Exception) { }
            MezzeLanguage.setLastExecutionResultForNestedEval(enclosingResult)
        }
    }

    /**
     * Report a long-running block WHILE it runs — at 30s, then every 30s.
     *
     * A warning printed after the fact is useless for the case that matters
     * most: a block that loops forever never finishes, so a post-hoc message
     * never prints, and the build simply appears to hang. Reporting as it goes
     * is the whole point — a stalled build otherwise looks identical to a slow
     * one, and the person waiting has no way to tell which block is responsible.
     *
     * Still never a kill. An infinite comptime hangs the build the way any
     * infinite loop does, and that is the author's code doing what it says; not
     * ours to cut short. What we owe them is to stop being silent about it.
     *
     * Daemon thread, so it can never hold the JVM open, and interrupted the
     * moment the guest run returns.
     */
    private fun startSlowWatch(origin: String): SlowWatch {
        val reports = java.util.concurrent.atomic.AtomicInteger(0)
        val thread = Thread({
            try {
                while (true) {
                    Thread.sleep(SLOW_EVALUATION_MS)
                    val elapsed = reports.incrementAndGet() * SLOW_EVALUATION_MS / 1000
                    System.err.println(
                        "  warning: the comptime block at $origin is still evaluating " +
                            "after ${elapsed}s. It runs on every build, and on every " +
                            "machine that builds this package.",
                    )
                }
            } catch (stopped: InterruptedException) {
                // The block finished. SlowWatch.stopAndReport says the rest.
            }
        }, "mezze-comptime-watch")
        thread.isDaemon = true
        thread.start()
        return SlowWatch(thread, reports)
    }

    private class SlowWatch(
        private val thread: Thread,
        private val reports: java.util.concurrent.atomic.AtomicInteger,
    ) {
        /** Stop watching, and give a closing total — but only for a block we
         *  already complained about, so a fast one stays completely silent. */
        fun stopAndReport(elapsedNanos: Long, origin: String) {
            thread.interrupt()
            if (reports.get() == 0) return
            System.err.println(
                java.lang.String.format(
                    java.util.Locale.ROOT,
                    "  warning: the comptime block at %s finished after %.1fs.",
                    origin, elapsedNanos / 1_000_000_000.0,
                )
            )
        }
    }

    /** The user-facing half of a failure: the guest message, not the host's
     *  wrapper chain. */
    private fun describe(e: Throwable): String {
        var cur: Throwable? = e
        var best = e.message ?: e.javaClass.simpleName
        while (cur != null) {
            val m = cur.message
            if (m != null && cur !is RuntimeException) best = m
            if (cur.javaClass.name.startsWith("com.mezze.interpreter.values.MezzeGuestException")) {
                return cur.message ?: best
            }
            cur = cur.cause
        }
        return best
    }

    /** Mezze frames, innermost first. Host frames are deliberately dropped —
     *  they belong behind `--verbose` and help nobody reading their own
     *  comptime block. */
    private fun guestFramesOf(e: Throwable): List<String> {
        val polyglot = e as? org.graalvm.polyglot.PolyglotException ?: return emptyList()
        return polyglot.polyglotStackTrace
            .filter { it.isGuestFrame }
            .map { frame ->
                val where = frame.sourceLocation
                if (where == null) frame.rootName ?: "<unknown>"
                else "${frame.rootName ?: "<anonymous>"} at ${where.source.name}:${where.startLine}"
            }
            .toList()
    }

    companion object {
        private val LOCK = Any()

        /**
         * How many thunks this process has evaluated.
         *
         * The only thing that distinguishes real folding from the declaration's
         * run-time fallback — both produce the same answer, which is exactly why
         * the fallback is safe and exactly why a test asserting the answer cannot
         * tell them apart. It is also how the "type checking never executes user
         * code" guarantee is held: a `mez check` must leave this untouched.
         */
        @JvmStatic
        val evaluations = java.util.concurrent.atomic.AtomicLong()

        /** How many functions this process has [invoke]d: macro expansions. Kept apart
         *  from [evaluations], which counts `comptime` alone. */
        @JvmStatic
        val invocations = java.util.concurrent.atomic.AtomicLong()

        /**
         * An engine tuned for a handful of short runs — and one that still
         * builds where those knobs do not exist.
         *
         * `engine.Compilation` is an OPTIMIZING-runtime option: on the fallback
         * runtime it is not merely ignored, it makes `build()` throw. That
         * runtime is what the CLI has (it keeps Truffle's compiler off its own
         * class path deliberately), and the CLI is where a build folds its
         * comptime blocks — so the untuned engine is a real configuration, not
         * a defensive branch. `WarnInterpreterOnly` is silenced through a system
         * property as well, because the warning is emitted before any option on
         * the builder can take effect.
         */
        private fun newEngine(): Engine {
            if (System.getProperty(WARN_INTERPRETER_ONLY) == null) {
                System.setProperty(WARN_INTERPRETER_ONLY, "false")
            }
            return try {
                Engine.newBuilder()
                    .allowExperimentalOptions(true)
                    .option("engine.WarnInterpreterOnly", "false")
                    // A comptime block runs a handful of times at most, so
                    // paying for compilation would cost more than it saves.
                    .option("engine.Compilation", "false")
                    .build()
            } catch (e: IllegalArgumentException) {
                Engine.newBuilder().allowExperimentalOptions(true).build()
            }
        }

        /** First report at this mark, then one every interval after.
         *  High on purpose: comptime is meant to do real work at build time, so
         *  the bar is "you would notice this waiting", not "this was measurable". */
        private const val SLOW_EVALUATION_MS = 30_000L

        private const val WARN_INTERPRETER_ONLY = "polyglot.engine.WarnInterpreterOnly"

        /** Reified nodes are compiler-synthesised and have no source text of
         *  their own; the diagnostic location comes from the call site. */
        private val SYNTHETIC = SourceInfo(0, 0, 0, 0, 0)

        /** The runtime's entry-function name, single-sourced. */
        private val ENTRY_FN = com.mezze.language.ast.MagicParams.ENTRY_FN

        /** Installs this evaluator. Called from [MezzeLanguage]'s static
         *  initialiser, the same way `NativeBootstrap` installs itself into
         *  `NativeSignatures` — so simply having `:interpreter` on the class
         *  path is what makes compile-time evaluation available. */
        @JvmStatic
        fun install() {
            Comptime.install(TruffleComptimeEvaluator())
        }
    }
}
