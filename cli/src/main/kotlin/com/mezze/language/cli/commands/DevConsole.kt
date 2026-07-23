package com.mezze.language.cli.commands

/**
 * Formats the tooling chrome for `mez dev` watch mode.
 *
 * Design (locked with the user):
 *  - A rule-with-status line is a SEPARATOR that PRECEDES each run's output —
 *    it announces "here comes the next run". Program logs then flow freely
 *    below it with generous blank-line spacing and NO trailing rule, so a
 *    server that logs continuously never gets its stream interrupted.
 *  - The exit status of a run is only known after it finishes, so it's folded
 *    into the NEXT separator (`… reloaded app.mz · prev exited 0 · 0.09s`)
 *    rather than printed after the logs.
 *
 * This class is a PURE formatter: every method returns the string to emit, so
 * the layout is unit-testable without a live subprocess or a TTY. The caller
 * ([WireRunner]) writes the strings to stderr. Colour is applied only when
 * [color] is true (i.e. stderr is a TTY); tests construct it with color=false
 * to assert on plain text.
 */
class DevConsole(
    private val appName: String,
    /** True when the output stream is an interactive terminal (enables ANSI). */
    private val color: Boolean,
    /** Rule width in columns. */
    private val width: Int = 44,
) {
    private val ESC = ""
    private fun dim(s: String) = if (color) "$ESC[2m$s$ESC[0m" else s
    private fun bold(s: String) = if (color) "$ESC[1m$s$ESC[0m" else s
    private fun red(s: String) = if (color) "$ESC[31m$s$ESC[0m" else s

    private val indent = "  "

    /** The opening banner, shown once at startup before the first run. */
    fun banner(watchedDir: String, compileSeconds: Double): List<String> = listOf(
        "$indent${bold("▸ mez dev")} ${dim("·")} $appName",
        "$indent${dim("compiled in ${fmtSeconds(compileSeconds)} · watching $watchedDir")}",
    )

    /**
     * A separator introducing a run. [label] is the human status for THIS run
     * ("running · ^C to stop" for the first run, "reloaded app.mz · …" on a
     * save). [priorStatus] (if any) folds the PREVIOUS run's exit into the
     * same line. Returned WITHOUT surrounding blank lines — the caller adds
     * the spacing via [blank] so the policy lives in one place.
     */
    fun separator(label: String, priorStatus: String? = null): String {
        val text = if (priorStatus != null) "$priorStatus · $label" else label
        // Rule fills the remaining width to the left of the status text.
        val ruleLen = (width - text.length - 1).coerceAtLeast(3)
        val rule = "─".repeat(ruleLen)
        return "$indent${dim(rule)} ${dim(text)}"
    }

    /** Status fragment describing how the previous run ended. */
    fun priorStatus(outcome: RunResult): String = when (outcome) {
        is RunResult.Exited -> "prev exited ${outcome.code}"
        RunResult.Reloading -> "prev still running"
        RunResult.Crashed -> red("prev crashed")
    }

    /** A blank line — the spacing the caller inserts around program output. */
    fun blank(): String = ""

    /**
     * Clear the visible screen for a fresh run, WITHOUT wiping scrollback —
     * the previous run's output is still reachable by scrolling up (Vite/Next
     * convention). `ESC[H` homes the cursor, `ESC[2J` clears the screen.
     * Returns "" when not a TTY (piped output / CI) so logs aren't corrupted
     * with escape sequences. The caller decides WHEN to clear (on reload).
     */
    fun clearScreen(): String = if (color) "$ESC[H$ESC[2J" else ""

    private fun fmtSeconds(s: Double): String =
        if (s < 1.0) "${(s * 1000).toInt()}ms" else "${"%.2f".format(s)}s"

    /** Outcome of a run, kept minimal for status rendering. */
    sealed interface RunResult {
        data class Exited(val code: Int) : RunResult
        data object Reloading : RunResult
        data object Crashed : RunResult
    }
}
