package com.mezze.language.cli.standalone

/**
 * Main class for standalone JAR execution.
 * Loads embedded blobs.sqlite and executes via StandaloneHelper.
 *
 * Accepts the same profiling flags as `mez dev`:
 *   --profile        CPU sampler → ./mezze-profile.txt
 *   --coverage       Line coverage → ./mezze-coverage.txt
 *   --allocations    Allocation histogram → ./mezze-allocations.txt
 *   --insight=<f>    Attach a GraalVM Insight JS probe
 *   --inspect        Attach Chrome DevTools / VS Code debugger
 *   --inspect-brk    Same as --inspect, but break before main
 *   --debug          Print full stack trace on error
 *   --help           Show this help message
 *
 * Output filenames are prefixed with `mezze-` so a user app's data
 * directory doesn't collide with profile dumps. Override the directory
 * with MEZZE_OUTPUT_DIR env var.
 */
object StandaloneMain {
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

        // Force EmbeddedAppLoader's class init so its `init {}` runs at
        // build time when --initialize-at-build-time is set. Without
        // this reference, native-image's reachability analysis may
        // consider the class dead. The touch() call is a no-op at
        // runtime — the init has already run by the time we get here.
        com.mezze.language.cli.standalone.embedded.EmbeddedAppLoader.touch()

        try {
            if (com.mezze.language.cli.standalone.embedded.EmbeddedApp.present) {
                // AOT-embedded path (mez build --native).
                // FunctionIRs are already deserialized into the image
                // heap — no SQLite, no JDBC, no resource decode.
                mark("embedded app detected (${com.mezze.language.cli.standalone.embedded.EmbeddedApp.functions.size} fns)")
                StandaloneHelper.executeFromEmbedded(args.toList(), timing, t0)
                mark("execute returned")
            } else {
                // JVM jar path: read embedded blobs.sqlite resource and
                // run through the SQLite-backed pipeline. Register the
                // sqlite-backed MezzeStoreOpener so PrecompiledStdlib
                // can hydrate from stdlib.sqlite via the SPI (#168).
                com.mezze.language.store.MezzeStoreOpener.register { file ->
                    com.mezze.language.codebase.SQLiteMezzeStore.readOnly(file)
                }

                val stream = StandaloneMain::class.java.getResourceAsStream("/com/mezze/standalone/blobs.sqlite")
                    ?: throw RuntimeException("blobs.sqlite not found in JAR")

                val bytes = stream.readBytes()
                stream.close()
                mark("blobs.sqlite resource read (${bytes.size / 1024}KB)")

                StandaloneHelper.executeFromBlobsSqlite(bytes, args.toList(), timing, t0)
                mark("execute returned")
            }
        } catch (e: Exception) {
            System.err.println("Error: ${e.message}")
            if (args.contains("--debug")) e.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }

    private fun printHelp() {
        println("""
            Usage: java -jar <app>.jar [OPTIONS]

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
              MEZZE_OUTPUT_DIR    Directory for profile/coverage/allocation
                                  output files. Defaults to current directory.
        """.trimIndent())
    }
}
