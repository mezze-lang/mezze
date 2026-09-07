package com.mezze.interpreter.standalone

import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.truffleir.FunctionIR
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import java.io.File
import java.io.PrintStream

/**
 * Execute an AOT-embedded Mezze app — the FunctionIR closure and
 * performer records are already live in the image heap via
 * [EmbeddedApp].
 *
 * Cold-start path: read static fields → build Truffle engine →
 * register functions with [MezzeLanguage] → eval. No SQLite, no
 * JDBC, no resource decode.
 */
object EmbeddedExec {

    @JvmStatic
    fun run(args: List<String>, timing: Boolean, t0: Long) {
        fun mark(label: String) {
            if (timing) System.err.println("[${(System.nanoTime() - t0) / 1_000_000}ms] $label")
        }

        val embedded = com.mezze.interpreter.standalone.EmbeddedApp
        check(embedded.present) { "EmbeddedExec.run called but EmbeddedApp.present is false" }

        // Stdlib snapshot check (#202, relaxed in #native-image).
        //
        // The embedded app's FunctionIR closure is self-contained:
        // NativeBuildPath.computeReachableClosure includes every stdlib
        // function the app transitively references. So the runtime
        // doesn't need StdlibRuntimeData to be present — all needed
        // functions are already in `embedded.functions`.
        //
        // On JVM, the stdlib snapshot IS available in the jar (bundled
        // from mezze-runtime.jar). On native, it may be absent because
        // native-classpath.zip's per-library jars don't carry the
        // generated CBOR. Either way the app runs correctly.
        //
        // Log a diagnostic when missing (helps debug unexpected misses)
        // but don't abort — the closure covers everything needed.
        if (!com.mezze.language.stdlib.StdlibRuntimeData.present) {
            val nonStrict = System.getenv("MEZZE_STDLIB_NONSTRICT")?.let {
                it == "1" || it.equals("true", ignoreCase = true)
            } ?: false
            if (!nonStrict && System.getenv("MEZZE_DEBUG") != null) {
                System.err.println(
                    "[mezze] stdlib snapshot not present — using embedded closure only. " +
                    "This is expected for native binaries."
                )
            }
        }

        executeWithTruffle(
            functions = embedded.functions,
            performers = embedded.performerRecords,
            entryHash = embedded.entryHash,
            args = args,
            timing = timing,
            t0 = t0,
        )
    }

    /**
     * Where profile / coverage / allocation outputs go. Defaults to
     * the current directory; override via `MEZZE_OUTPUT_DIR`.
     */
    private fun outputDir(): File {
        val override = System.getenv("MEZZE_OUTPUT_DIR")
        return if (override != null && override.isNotBlank()) File(override) else File(".")
    }

    private fun executeWithTruffle(
        functions: List<FunctionIR>,
        performers: List<Array<String>> = emptyList(),
        // Entry-point identity: entry function content-hash hex (never a name).
        entryHash: String? = null,
        args: List<String> = emptyList(),
        timing: Boolean = false,
        t0: Long = System.nanoTime(),
    ) {
        fun mark(label: String) {
            if (timing) System.err.println("[${(System.nanoTime() - t0) / 1_000_000}ms] $label")
        }
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
        // Suppress chatty Truffle log output UNLESS the user asked for
        // trace compilation — compilation trace events route through the
        // polyglot logger and would be eaten by level=OFF. (#515)
        val traceRequested = System.getenv("MEZZE_TRACE_COMPILATION")?.let {
            it == "1" || it.equals("true", ignoreCase = true)
        } ?: false
        if (!traceRequested) {
            System.setProperty("polyglot.log.level", "OFF")
        }

        // Truffle JIT is ON by default for standalone apps (truffle-runtime
        // is on the classpath; engine.Compilation defaults to true). Users
        // can disable with MEZZE_JIT=0 if a startup-sensitive workload
        // prefers interpreter-only.
        val jitRequestedOff = System.getenv("MEZZE_JIT")?.let {
            it == "0" || it.equals("false", ignoreCase = true)
        } ?: false
        // Detect whether we're running on the JIT-capable Truffle runtime
        // (HotSpotTruffleRuntime) or the interpreter-only fallback
        // (DefaultTruffleRuntime — used in native-image builds where Graal
        // partial evaluation isn't available). On the latter,
        // `engine.Compilation` is not a recognized option and trying to
        // set it throws "Could not find option with name engine.Compilation"
        // before we even start running user code. Treat MEZZE_JIT=0 as a
        // no-op there with a warning: JIT was never available anyway, so
        // there's nothing to switch off. (#195)
        val jitCapable = try {
            val rt = com.oracle.truffle.api.Truffle.getRuntime()
            // DefaultTruffleRuntime is the pure-interpreter fallback;
            // anything else (HotSpotTruffleRuntime in JVM mode) is JIT-capable.
            !rt.javaClass.name.endsWith("DefaultTruffleRuntime")
        } catch (_: Throwable) { true }
        val jitDisabled = jitRequestedOff && jitCapable
        if (jitRequestedOff && !jitCapable) {
            System.err.println(
                "[mezze] MEZZE_JIT=0 ignored — this binary is interpreter-only " +
                "(no Truffle JIT available). No-op."
            )
        }
        // MEZZE_TRACE_COMPILATION=1 surfaces Truffle's "function X
        // compiled" log lines — the cleanest yes/no for whether JIT
        // is actually running in this binary.
        val traceCompilation = System.getenv("MEZZE_TRACE_COMPILATION")?.let {
            it == "1" || it.equals("true", ignoreCase = true)
        } ?: false
        val traceRequestedOnButNotCapable = traceCompilation && !jitCapable
        if (traceRequestedOnButNotCapable) {
            System.err.println(
                "[mezze] MEZZE_TRACE_COMPILATION=1 ignored — this binary is " +
                "interpreter-only (no compilation events to trace). No-op."
            )
        }
        // MEZZE_TRACE_INLINING=1 → engine.TraceCompilationDetails (per-call-site
        // inlining decisions). Useful for #256/#257 hot-loop PE debugging.
        val traceInlining = System.getenv("MEZZE_TRACE_INLINING")?.let {
            it == "1" || it.equals("true", ignoreCase = true)
        } ?: false
        // MEZZE_OSR_THRESHOLD=<int> → override engine.OSRCompilationThreshold.
        // Default is 100,000. Lower to force OSR to fire earlier on
        // tight loops (useful for benching small-N programs where
        // 100k iterations would dwarf the loop body's natural size).
        val osrThreshold = System.getenv("MEZZE_OSR_THRESHOLD")?.toIntOrNull()
        // MEZZE_TRACE_TTI=1 → engine.TraceTransferToInterpreter: prints
        // stack trace at every transferToInterpreter call (deopt diagnostic).
        val traceTTI = System.getenv("MEZZE_TRACE_TTI")?.let {
            it == "1" || it.equals("true", ignoreCase = true)
        } ?: false
        val engineBuilder = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .apply { if (jitDisabled) option("engine.Compilation", "false") }
            .apply { if (traceCompilation && jitCapable) option("engine.TraceCompilation", "true") }
            .apply { if (traceInlining && jitCapable) option("engine.TraceCompilationDetails", "true") }
            .apply { if (traceTTI && jitCapable) option("engine.TraceTransferToInterpreter", "true") }
            // Aggressive compilation thresholds. Mezze's static type system
            // means call-site types stabilize at invocation #1, so the JIT's
            // profiling data is good enough at low iteration counts. Defaults
            // (~64 Tier 1, ~10000 Tier 2) waste hundreds of milliseconds in
            // unoptimized code. Measured on fib(35)+fib(38): ~8% wall-time
            // reduction with these settings.
            // NOTE: boxingEliminationTypes = {} (disabled) — with boxing
            // elimination ON, these low thresholds cause a deopt storm.
            // If boxing elimination is re-enabled, raise to 200/1000/500.
            .apply {
                if (jitCapable && !jitDisabled) {
                    option("engine.FirstTierCompilationThreshold", "16")
                    option("engine.LastTierCompilationThreshold", "100")
                    option("engine.SingleTierCompilationThreshold", "32")
                    // OSR (on-stack replacement) threshold: how many loop
                    // iterations before the JIT compiles a running loop.
                    // Default is 100K — too high for FP-style one-shot loops
                    // (e.g. range(0, 10M).fold). 10K gives 10× faster warmup
                    // for loops entered once. MEZZE_OSR_THRESHOLD overrides.
                    option("engine.OSRCompilationThreshold",
                        (osrThreshold ?: 10_000).toString())
                    // Increase recursion inlining depth from the default (2)
                    // to 4. Measured on fib(40) native: 680ms → 530ms (~22%).
                    // The extra depth lets PE inline 2 levels of recursive
                    // calls, enabling escape analysis to scalar-replace the
                    // Long boxing + Object[] varargs at DirectCallNode.call().
                    // No measurable impact on startup or non-recursive
                    // benchmarks (seq fusion, noop).
                    option("compiler.InliningRecursionDepth", "4")
                }
            }
            // MEZZE_ENGINE_OPTS="key1=val1,key2=val2" → pass arbitrary engine
            // options through to Truffle. For perf experimentation without
            // recompiling (e.g. "engine.Inlining=false,engine.SingleTierCompilationThreshold=50000").
            // Options set here OVERRIDE the defaults above.
            .apply {
                System.getenv("MEZZE_ENGINE_OPTS")?.takeIf { it.isNotBlank() }?.let { raw ->
                    for (pair in raw.split(",")) {
                        val eq = pair.indexOf('=')
                        if (eq > 0) {
                            val k = pair.substring(0, eq).trim()
                            val v = pair.substring(eq + 1).trim()
                            try { option(k, v) } catch (e: Exception) {
                                System.err.println("[mezze] MEZZE_ENGINE_OPTS: ignoring '$k=$v' — ${e.message}")
                            }
                        }
                    }
                }
            }
            // Polyglot routes engine.Trace* output through its own logger. Pin
            // it to stderr so users see the trace events without needing
            // --log.file=...; safe always since options are off by default.
            .apply { if ((traceCompilation || traceInlining || traceTTI) && jitCapable) err(System.err) }

        val profile = args.contains("--profile")
        val coverage = args.contains("--coverage")
        val allocations = args.contains("--allocations")
        val inspect = args.contains("--inspect")
        val inspectBrk = args.contains("--inspect-brk")
        // `--dap` / `--dap=<port>`: start GraalVM's Debug Adapter Protocol server
        // so VS Code (or any DAP client) can attach for breakpoints/stepping.
        // Defaults to port 4711 (the DAP tool's default). `--dap-brk` suspends on
        // entry so the client can set breakpoints before anything runs.
        val dap = args.contains("--dap") || args.any { it.startsWith("--dap=") } || args.contains("--dap-brk")
        val dapBrk = args.contains("--dap-brk")
        val dapPort = args.firstNotNullOfOrNull { a ->
            if (a.startsWith("--dap=")) a.substringAfter("=").toIntOrNull() else null
        } ?: 4711
        val insightFile = args.firstNotNullOfOrNull { a ->
            if (a.startsWith("--insight=")) a.substringAfter("=") else null
        }

        // User-app native binaries don't ship the Truffle instrumentation
        // tool jars (cpusampler, coverage, chromeinspector, insight) — they
        // were dropped from the classpath in NativeBuildPath.extractRuntimeClasspath
        // to keep the binary slim. The options they expose simply don't
        // exist at runtime, and enabling one causes engineBuilder.build()
        // to throw "Could not find option with name cpusampler" before user
        // code runs. Same root cause as #195 (MEZZE_JIT=0 on DefaultTruffleRuntime).
        //
        // Historical note: earlier attempts (#236) gated on `!jitCapable`
        // to demote these flags. That was correct while user native binaries
        // used the interpreter-only DefaultTruffleRuntime. Once #402 turned
        // on --macro:truffle-svm, native binaries became jitCapable=true —
        // so the guard let the option calls through and the crash returned.
        //
        // Fix: probe the option registry ONCE against a throwaway engine
        // (Engine.getOptions() enumerates every option every registered tool
        // exposes). Each flag checks if its option key is present before
        // touching engineBuilder — if the tool jar isn't shipped, the option
        // isn't registered, so we skip setting it and print a warning.
        // Building the probe engine is unavoidable overhead but only pays
        // when the user asked for instrumentation, and it's cheap (<50ms).
        // Only build the probe engine when the user actually requested
        // instrumentation — the probe engine's creation initializes
        // process-global Truffle options to their defaults, which steals
        // options like engine.TraceTransferToInterpreter and prevents the
        // real engine from setting them. (#515, deopt-trace fix)
        val instrumentationRequested = profile || coverage || allocations ||
            inspect || inspectBrk || dap || insightFile != null
        val availableOptions: Set<String> = if (!instrumentationRequested) {
            emptySet()
        } else try {
            Engine.newBuilder().option("engine.WarnInterpreterOnly", "false").build().use { probe ->
                probe.options.mapNotNull { it.name }.toSet()
            }
        } catch (_: Throwable) {
            emptySet()
        }

        fun trySetOption(flagName: String, primaryOption: String, action: () -> Unit): Boolean {
            if (primaryOption !in availableOptions) {
                System.err.println(
                    "[mezze] $flagName ignored — this binary doesn't ship the required Truffle tool " +
                    "(option '$primaryOption' not registered). " +
                    "Run via `mez exec` / `mez dev` for full tooling."
                )
                return false
            }
            return try {
                action()
                true
            } catch (e: IllegalArgumentException) {
                System.err.println("[mezze] $flagName ignored — ${e.message?.trim() ?: "option not registered"}")
                false
            }
        }

        if (profile) {
            val out = File(outputDir(), "mezze-profile.txt")
            out.parentFile?.mkdirs()
            val ok = trySetOption("--profile", "cpusampler") {
                engineBuilder.option("cpusampler", "true")
                    .option("cpusampler.OutputFile", out.absolutePath)
            }
            if (ok) System.err.println("[mezze] profile → ${out.absolutePath}")
        }
        if (coverage) {
            val out = File(outputDir(), "mezze-coverage.txt")
            out.parentFile?.mkdirs()
            val ok = trySetOption("--coverage", "coverage") {
                engineBuilder.option("coverage", "true")
                    .option("coverage.OutputFile", out.absolutePath)
            }
            if (ok) System.err.println("[mezze] coverage → ${out.absolutePath}")
        }
        if (allocations) {
            val out = File(outputDir(), "mezze-allocations.txt")
            out.parentFile?.mkdirs()
            val ok = trySetOption("--allocations", "mezze-allocations") {
                engineBuilder.option("mezze-allocations", "true")
                    .out(PrintStream(out.outputStream(), true))
            }
            if (ok) System.err.println("[mezze] allocations → ${out.absolutePath}")
        }
        if (insightFile != null) {
            val f = File(insightFile)
            if (!f.exists()) {
                System.err.println("Error: --insight=$insightFile: file not found")
                kotlin.system.exitProcess(1)
            }
            trySetOption("--insight", "insight") { engineBuilder.option("insight", f.absolutePath) }
        }
        if (inspect || inspectBrk) {
            val ok = trySetOption("--inspect", "inspect") {
                engineBuilder.option("inspect", "4242")
                if (inspectBrk) engineBuilder.option("inspect.Suspend", "true")
                // #356 — BDSL bytecode has no debugger instrumentation; force off
                // so scopes/breakpoints/stepping keep working on the AST path.
                com.mezze.interpreter.runtime.MezzeLanguage.enableBdslDebugInstrumentation()
            }
            if (ok) System.err.println("[mezze] devtools listening on chrome://inspect — port 4242")
        }
        if (dap) {
            val ok = trySetOption("--dap", "dap") {
                // GraalVM's built-in DAP server (dap-tool). Statement/root source
                // sections + StatementTag on the Truffle nodes make Mezze line
                // breakpoints + stepping work through it.
                engineBuilder.option("dap", dapPort.toString())
                if (dapBrk) engineBuilder.option("dap.Suspend", "true")
                else engineBuilder.option("dap.Suspend", "false")
                // #356 — BDSL bytecode has no debugger instrumentation; force off
                // so scopes/breakpoints/stepping keep working on the AST path.
                com.mezze.interpreter.runtime.MezzeLanguage.enableBdslDebugInstrumentation()
            }
            if (ok) System.err.println("[mezze] DAP server listening on port $dapPort — attach a DAP client (VS Code)")
        }

        val engine = engineBuilder.build()
        // Three states: JIT capable + requested on, JIT capable + requested off,
        // JIT unavailable (native).
        val jitState = when {
            !jitCapable -> "N/A (interpreter-only runtime)"
            jitDisabled -> "OFF (MEZZE_JIT=0)"
            else -> "ON"
        }
        mark("Engine built (JIT $jitState)")
        // Ground truth for "does this binary JIT?".
        //   HotSpotTruffleRuntime → JIT via Graal partial evaluation
        //   DefaultTruffleRuntime → pure interpreter
        if (System.getenv("MEZZE_DIAG") == "1") {
            val runtime = com.oracle.truffle.api.Truffle.getRuntime()
            System.err.println("[mezze] Truffle runtime: ${runtime.javaClass.name} (${runtime.name})")
        }

        val context = Context.newBuilder(MezzeLanguage.ID)
            .engine(engine)
            .allowAllAccess(true)
            .allowCreateThread(true)
            .build()
        mark("Context built")

        try {
            val projectRoot = java.nio.file.Paths.get(".")
            // process_args returns what the user passed to the binary, not
            // what the JVM/native launcher saw. Strip our instrumentation
            // flags first — they're mez surface, not user-program input.
            val mezzeFlags = setOf("--profile", "--coverage", "--allocations",
                "--inspect", "--inspect-brk", "--debug", "--help", "-h")
            val userArgv = args.filter { a ->
                a !in mezzeFlags && !a.startsWith("--insight=")
            }.toTypedArray()
            com.mezze.interpreter.nodes.builtin.effects.EnvironmentNatives
                .setWireArgv(userArgv)

            if (entryHash != null) {
                MezzeLanguage.setFunctionsForDirectExecution(functions, performers, projectRoot, entryHash)
            } else {
                MezzeLanguage.setFunctionsForDirectExecution(functions, performers, projectRoot)
            }
            mark("functions registered with MezzeLanguage")

            val source = Source.newBuilder(
                MezzeLanguage.ID,
                "# Standalone execution",
                "__standalone__.mz"
            ).buildLiteral()
            mark("source built — calling context.eval()")

            // AppCDS training mode: by this point Engine + Context + language
            // are loaded and all Truffle/Polyglot infrastructure classes have
            // been touched. Skip the actual user-code eval so we can capture
            // a class-data-sharing archive without executing main(). The
            // build pipeline runs the jar once with this flag set, captures
            // the .jsa, then the launcher uses it on real runs to shave
            // ~50% off cold start. (#215)
            if (System.getenv("MEZZE_CDS_DRYRUN") == "1") {
                // Force-load runtime classes so they land in the CDS
                // archive. Without this, first use pays ~50ms of class
                // loading (BytecodeCompiler ~20ms + MezzeRecord/Function
                // ~30ms). CDS pre-verification drops those to ~5ms.
                // We DON'T run context.eval() — that would execute user
                // code with potential side effects (I/O, network, etc.).
                try {
                    // BDSL bytecode compiler and generated root node
                    Class.forName("com.mezze.interpreter.bytecode.BytecodeCompiler")
                    Class.forName("com.mezze.interpreter.bytecode.MezzeBytecodeRootNodeGen")
                    // Core value types used by performer record construction
                    Class.forName("com.mezze.interpreter.values.MezzeRecord")
                    Class.forName("com.mezze.interpreter.values.MezzeRecord\$Shape")
                    Class.forName("com.mezze.interpreter.values.MezzeRecord\$FieldLocation")
                    Class.forName("com.mezze.interpreter.values.MezzeFunction")
                    Class.forName("com.mezze.interpreter.runtime.MezzeContext")
                    // Stdlib runtime data (CBOR snapshot of pre-lowered functions)
                    Class.forName("com.mezze.language.stdlib.StdlibRuntimeData")
                } catch (_: Throwable) {}
                mark("CDS_DRYRUN — runtime classes warmed for archive")
                return
            }

            context.eval(source)
            mark("context.eval() returned")
        } finally {
            context.close(true)
            mark("context closed")
        }
    }
}
