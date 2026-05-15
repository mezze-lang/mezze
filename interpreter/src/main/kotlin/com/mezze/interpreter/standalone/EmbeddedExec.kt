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
 * [embedded.EmbeddedApp].
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

        val embedded = com.mezze.interpreter.standalone.embedded.EmbeddedApp
        check(embedded.present) { "EmbeddedExec.run called but EmbeddedApp.present is false" }

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
        val jitDisabled = System.getenv("MEZZE_JIT")?.let {
            it == "0" || it.equals("false", ignoreCase = true)
        } ?: false
        // MEZZE_TRACE_COMPILATION=1 surfaces Truffle's "function X
        // compiled" log lines — the cleanest yes/no for whether JIT
        // is actually running in this binary.
        val traceCompilation = System.getenv("MEZZE_TRACE_COMPILATION")?.let {
            it == "1" || it.equals("true", ignoreCase = true)
        } ?: false
        val engineBuilder = Engine.newBuilder()
            .allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false")
            .apply { if (jitDisabled) option("engine.Compilation", "false") }
            .apply { if (traceCompilation) option("engine.TraceCompilation", "true") }

        val profile = args.contains("--profile")
        val coverage = args.contains("--coverage")
        val allocations = args.contains("--allocations")
        val inspect = args.contains("--inspect")
        val inspectBrk = args.contains("--inspect-brk")
        val insightFile = args.firstNotNullOfOrNull { a ->
            if (a.startsWith("--insight=")) a.substringAfter("=") else null
        }

        if (profile) {
            val out = File(outputDir(), "mezze-profile.txt")
            out.parentFile?.mkdirs()
            engineBuilder.option("cpusampler", "true")
                .option("cpusampler.OutputFile", out.absolutePath)
            System.err.println("[mezze] profile → ${out.absolutePath}")
        }
        if (coverage) {
            val out = File(outputDir(), "mezze-coverage.txt")
            out.parentFile?.mkdirs()
            engineBuilder.option("coverage", "true")
                .option("coverage.OutputFile", out.absolutePath)
            System.err.println("[mezze] coverage → ${out.absolutePath}")
        }
        if (allocations) {
            val out = File(outputDir(), "mezze-allocations.txt")
            out.parentFile?.mkdirs()
            engineBuilder.option("mezze-allocations", "true")
                .out(PrintStream(out.outputStream(), true))
            System.err.println("[mezze] allocations → ${out.absolutePath}")
        }
        if (insightFile != null) {
            val f = File(insightFile)
            if (!f.exists()) {
                System.err.println("Error: --insight=$insightFile: file not found")
                kotlin.system.exitProcess(1)
            }
            engineBuilder.option("insight", f.absolutePath)
        }
        if (inspect || inspectBrk) {
            engineBuilder.option("inspect", "4242")
            if (inspectBrk) engineBuilder.option("inspect.Suspend", "true")
            System.err.println("[mezze] devtools listening on chrome://inspect — port 4242")
        }

        val engine = engineBuilder.build()
        mark("Engine built (JIT ${if (jitDisabled) "OFF" else "ON"})")
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

            context.eval(source)
            mark("context.eval() returned")
        } finally {
            context.close(true)
            mark("context closed")
        }
    }
}
