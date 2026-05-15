package com.mezze.interpreter.standalone

/**
 * Entry point for native-image-compiled Mezze apps.
 *
 * `mez build --native` produces a binary whose main class is this
 * object. The binary contains:
 *   - the Mezze Truffle runtime ([:interpreter])
 *   - the user app's FunctionIR closure (frozen into [EmbeddedApp]
 *     at native-image build time via `--initialize-at-build-time`)
 *   - the precompiled stdlib snapshot
 *
 * It does NOT contain: the parser, typechecker, formatter, LSP server,
 * CLI commands, store-sqlite, Ktor — those are toolchain concerns that
 * the user's runtime doesn't need. Keeping the user-app classpath
 * narrow is what lets Truffle JIT activate in the native binary
 * (broad classpaths trip native-image's blocklist for runtime
 * compilation; see #184).
 *
 * For JVM jar mode (`java -jar app.jar` without `--native`), see
 * [com.mezze.language.cli.standalone.StandaloneMain] in :cli — that
 * path goes through SQLite to load IR from an embedded blobs.sqlite.
 *
 * Profiling / debugging flags supported via args:
 *   --profile        CPU sampler → ./mezze-profile.txt
 *   --coverage       Line coverage → ./mezze-coverage.txt
 *   --allocations    Allocation histogram → ./mezze-allocations.txt
 *   --insight=<f>    Attach a GraalVM Insight JS probe
 *   --inspect        Attach Chrome DevTools / VS Code debugger
 *   --inspect-brk    Same as --inspect, but break before main
 *   --debug          Print full stack trace on error
 *   --help           Show help
 *
 * Environment:
 *   MEZZE_TIMING=1               step-by-step boot timing on stderr
 *   MEZZE_DIAG=1                 print the active Truffle runtime
 *   MEZZE_JIT=0                  disable JIT (interpreter-only)
 *   MEZZE_TRACE_COMPILATION=1    print Truffle compilation events
 *   MEZZE_OUTPUT_DIR=<dir>       redirect profile/coverage outputs
 */
object NativeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.contains("--help") || args.contains("-h")) {
            printHelp()
            return
        }

        val timing = System.getenv("MEZZE_TIMING") != null
        val t0 = System.nanoTime()
        fun mark(label: String) {
            if (timing) System.err.println("[${(System.nanoTime() - t0) / 1_000_000}ms] $label")
        }
        mark("main() entered")

        // Force EmbeddedAppLoader's class-init at boot. Under
        // --initialize-at-build-time the init has already run in the
        // build JVM and EmbeddedApp's static fields are baked into the
        // image heap; this call is a no-op at runtime but keeps the
        // reachability analysis honest (without it, the class could
        // be dead-stripped).
        com.mezze.interpreter.standalone.embedded.EmbeddedAppLoader.touch()

        try {
            if (!com.mezze.interpreter.standalone.embedded.EmbeddedApp.present) {
                System.err.println(
                    "Error: this native binary has no embedded Mezze app. " +
                    "It was built without `mez build --native`, or the AOT " +
                    "snapshot failed to populate at image-build time."
                )
                kotlin.system.exitProcess(1)
            }
            mark(
                "embedded app detected (" +
                "${com.mezze.interpreter.standalone.embedded.EmbeddedApp.functions.size} fns)"
            )
            EmbeddedExec.run(args.toList(), timing, t0)
            mark("execute returned")
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (args.contains("--debug")) e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }

    private fun printHelp() {
        println(
            """
            Usage: <app> [OPTIONS]

            PROFILING:
              --profile           CPU sampler → ./mezze-profile.txt
              --coverage          Line coverage → ./mezze-coverage.txt
              --allocations       Allocation histogram → ./mezze-allocations.txt
              --insight=<file>    Attach a GraalVM Insight JS probe

            DEBUGGING:
              --inspect           Attach Chrome DevTools / VS Code debugger
              --inspect-brk       Same as --inspect, but break before main

            OTHER:
              --debug             Print full stack trace on error
              -h, --help          Show this help message

            ENVIRONMENT:
              MEZZE_OUTPUT_DIR    Directory for profile/coverage/allocation output.
              MEZZE_DIAG=1        Print Truffle runtime class on boot.
              MEZZE_JIT=0         Disable Truffle JIT (interpreter-only mode).
              MEZZE_TRACE_COMPILATION=1   Trace JIT compilation events.
              MEZZE_TIMING=1      Step-by-step boot timing.
            """.trimIndent()
        )
    }
}
