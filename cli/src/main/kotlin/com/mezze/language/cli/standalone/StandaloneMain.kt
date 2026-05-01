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

        try {
            val stream = StandaloneMain::class.java.getResourceAsStream("/com/mezze/standalone/blobs.sqlite")
                ?: throw RuntimeException("blobs.sqlite not found in JAR")

            val bytes = stream.readBytes()
            stream.close()

            StandaloneHelper.executeFromBlobsSqlite(bytes, args.toList())
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
