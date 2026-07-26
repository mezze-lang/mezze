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

        // Strict-mode stdlib check (#202). In a user binary the snapshot
        // is the only correct fast-path: SQLite isn't shipped, source
        // recompile would be O(seconds) and depends on stdlib sources
        // existing on disk. Fail loud now rather than letting the user
        // app drift into the slow path silently.
        //
        // Override with MEZZE_STDLIB_NONSTRICT=1 for debugging.
        val nonStrict = System.getenv("MEZZE_STDLIB_NONSTRICT")?.let {
            it == "1" || it.equals("true", ignoreCase = true)
        } ?: false
        if (!nonStrict &&
            !com.mezze.language.stdlib.StdlibRuntimeData.present) {
            System.err.println(
                "Error: built-in stdlib snapshot is missing or failed to decode.\n" +
                "  This binary was built without a working stdlib-snapshot.cbor,\n" +
                "  or the resource was corrupted during packaging.\n" +
                "  Rebuild with `mez build --native` against a fresh stdlib jar.\n" +
                "  To bypass and use the slow source-compile path, set\n" +
                "  MEZZE_STDLIB_NONSTRICT=1 (intended only for diagnostics)."
            )
            kotlin.system.exitProcess(70) // EX_SOFTWARE
        }

        executeWithTruffle(
            functions = embedded.functions,
            performers = embedded.performerRecords,
            entryName = embedded.entryName,
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
        entryName: String? = null,
        args: List<String> = emptyList(),
        timing: Boolean = false,
        t0: Long = System.nanoTime(),
    ) {
        fun mark(label: String) {
            if (timing) System.err.println("[${(System.nanoTime() - t0) / 1_000_000}ms] $label")
        }
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
        System.setProperty("polyglot.log.level", "OFF")

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
        val engineBuilder = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .apply { if (jitDisabled) option("engine.Compilation", "false") }
            .apply { if (traceCompilation && jitCapable) option("engine.TraceCompilation", "true") }
            .apply { if (traceInlining && jitCapable) option("engine.TraceCompilationDetails", "true") }
            .apply { if (osrThreshold != null && jitCapable) option("engine.OSRCompilationThreshold", osrThreshold.toString()) }
            // Polyglot routes engine.Trace* output through its own logger. Pin
            // it to stderr so users see the trace events without needing
            // --log.file=...; safe always since options are off by default.
            .apply { if ((traceCompilation || traceInlining) && jitCapable) err(System.err) }

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
        // exist at runtime, and trying to set them crashes with
        // "Could not find option with name cpusampler" before user code
        // runs. Same root cause as #195 (MEZZE_JIT=0 on DefaultTruffleRuntime).
        //
        // Treat the dev-instrumentation flags as no-ops with a one-line
        // warning when the runtime can't honor them. Users who want
        // profiling / coverage / inspect should run via `mez dev` or
        // `mez exec` which spawn a JVM child with the tools on its
        // classpath. (#236)
        if (profile && !jitCapable) {
            System.err.println(
                "[mezze] --profile ignored — this binary doesn't ship the cpusampler tool. " +
                "Run via `mez exec` / `mez dev` for profiling."
            )
        }
        if (coverage && !jitCapable) {
            System.err.println(
                "[mezze] --coverage ignored — this binary doesn't ship the coverage tool. " +
                "Run via `mez exec` / `mez dev` for coverage."
            )
        }
        if (allocations && !jitCapable) {
            System.err.println(
                "[mezze] --allocations ignored — this binary doesn't ship the allocations tool. " +
                "Run via `mez exec` / `mez dev` for allocation tracking."
            )
        }
        if (insightFile != null && !jitCapable) {
            System.err.println(
                "[mezze] --insight=... ignored — this binary doesn't ship the GraalVM Insight tool. " +
                "Run via `mez exec` / `mez dev` for Insight."
            )
        }
        if ((inspect || inspectBrk) && !jitCapable) {
            System.err.println(
                "[mezze] --inspect ignored — this binary doesn't ship the Chrome DevTools inspector. " +
                "Run via `mez exec` / `mez dev` for the debugger."
            )
        }

        if (profile && jitCapable) {
            val out = File(outputDir(), "mezze-profile.txt")
            out.parentFile?.mkdirs()
            engineBuilder.option("cpusampler", "true")
                .option("cpusampler.OutputFile", out.absolutePath)
            System.err.println("[mezze] profile → ${out.absolutePath}")
        }
        if (coverage && jitCapable) {
            val out = File(outputDir(), "mezze-coverage.txt")
            out.parentFile?.mkdirs()
            engineBuilder.option("coverage", "true")
                .option("coverage.OutputFile", out.absolutePath)
            System.err.println("[mezze] coverage → ${out.absolutePath}")
        }
        if (allocations && jitCapable) {
            val out = File(outputDir(), "mezze-allocations.txt")
            out.parentFile?.mkdirs()
            engineBuilder.option("mezze-allocations", "true")
                .out(PrintStream(out.outputStream(), true))
            System.err.println("[mezze] allocations → ${out.absolutePath}")
        }
        if (insightFile != null && jitCapable) {
            val f = File(insightFile)
            if (!f.exists()) {
                System.err.println("Error: --insight=$insightFile: file not found")
                kotlin.system.exitProcess(1)
            }
            engineBuilder.option("insight", f.absolutePath)
        }
        if ((inspect || inspectBrk) && jitCapable) {
            engineBuilder.option("inspect", "4242")
            if (inspectBrk) engineBuilder.option("inspect.Suspend", "true")
            // #356 — BDSL bytecode has no debugger instrumentation; force off
            // so scopes/breakpoints/stepping keep working on the AST path.
            com.mezze.interpreter.runtime.MezzeLanguage.disableBdslForDebugger()
            System.err.println("[mezze] devtools listening on chrome://inspect — port 4242 (BDSL disabled)")
        }
        if (dap && !jitCapable) {
            System.err.println(
                "[mezze] --dap ignored — this binary doesn't ship the debug adapter. " +
                "Run via `mez exec` / `mez dev` (JIT mode) for the debugger."
            )
        }
        if (dap && jitCapable) {
            // GraalVM's built-in DAP server (dap-tool). Statement/root source
            // sections + StatementTag on the Truffle nodes make Mezze line
            // breakpoints + stepping work through it.
            engineBuilder.option("dap", dapPort.toString())
            if (dapBrk) engineBuilder.option("dap.Suspend", "true")
            else engineBuilder.option("dap.Suspend", "false")
            // #356 — BDSL bytecode has no debugger instrumentation; force off
            // so scopes/breakpoints/stepping keep working on the AST path.
            com.mezze.interpreter.runtime.MezzeLanguage.disableBdslForDebugger()
            System.err.println("[mezze] DAP server listening on port $dapPort — attach a DAP client (VS Code) (BDSL disabled)")
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

            if (entryName != null) {
                MezzeLanguage.setFunctionsForDirectExecution(functions, performers, projectRoot, entryName)
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
                mark("CDS_DRYRUN — skipping context.eval()")
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
