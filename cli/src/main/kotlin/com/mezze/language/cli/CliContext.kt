package com.mezze.language.cli

/**
 * Process-global CLI state that every command reads but no command owns —
 * currently just the verbosity level. Set once in `main` from the parsed args
 * (and the legacy `MEZZE_*` env vars), read everywhere the output detail or a
 * stack-trace-vs-graceful-message decision depends on it.
 *
 * Verbosity is deliberately a global rather than a threaded parameter: it is
 * cross-cutting (touches error rendering, child-process output, timing lines)
 * and set exactly once at startup, so threading it through every command
 * signature would be noise. This mirrors how `mezze.project.root` is a sysprop.
 */
object CliContext {
    /**
     * True when the user asked for full diagnostic output: raw JVM/Truffle
     * stack traces, child-process stderr, timing lines. Default false — normal
     * runs show only polished, actionable messages. Set from a global
     * `--verbose`/`-V` flag or the back-compat `MEZZE_DEBUG`/`MEZZE_VERBOSE`
     * env vars.
     */
    @Volatile
    var verbose: Boolean = false

    /** Parse global `--verbose`/`-V`, honour legacy env vars, set [verbose]. */
    fun initFrom(args: Array<String>) {
        val flag = args.any { it == "--verbose" || it == "-V" }
        val env = System.getenv("MEZZE_VERBOSE")?.let { it == "1" || it.equals("true", true) } == true ||
            System.getenv("MEZZE_DEBUG")?.let { it == "1" || it.equals("true", true) } == true
        verbose = flag || env
    }
}

/**
 * A user-facing CLI error: a polished [message] (what went wrong) and an
 * optional [hint] (what to do next), with the underlying [cause] kept for
 * `--verbose`. The top-level boundary in `main` renders message + hint by
 * default and prints the cause's stack trace only when [CliContext.verbose] —
 * so a raw JVM/Truffle trace never reaches the user on a normal command.
 *
 * Throw this from a command instead of printing a bare `Error:` line + letting
 * the exception escape: it guarantees the graceful-by-default, trace-behind-
 * verbose contract without each command re-implementing it.
 */
class MezzeCliError(
    override val message: String,
    val hint: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
