package com.mezze.language.cli.commands

import java.io.File

/**
 * Shared, TTY-aware rendering for the project-setup errors that `mez dev`,
 * `mez build`, and `mez exec` all hit (missing/malformed Mezze.toml, no
 * `[[app]]`, app/file not found). These used to be copy-pasted bare
 * `println("Error: …")` calls in each command with no colour, no consistency,
 * and no actionable hint.
 *
 * PURE formatter: each method returns the string to print, so the wording is
 * unit-testable without touching stdout or a TTY. Colour is applied only when
 * [color] is true (stderr is a terminal); tests use color=false to assert on
 * plain text. Callers print the result and decide the exit code.
 */
class CliMessages(private val color: Boolean = System.console() != null) {

    private val ESC = ""
    private fun red(s: String) = if (color) "$ESC[31m$s$ESC[0m" else s
    private fun bold(s: String) = if (color) "$ESC[1m$s$ESC[0m" else s
    private fun dim(s: String) = if (color) "$ESC[2m$s$ESC[0m" else s

    /** A red `✗ <headline>` line plus dimmed, indented hint lines. */
    private fun error(headline: String, vararg hints: String): String {
        val sb = StringBuilder(red("${Glyphs.CROSS} ") + bold(headline))
        for (h in hints) sb.append("\n    ").append(dim(h))
        return sb.toString()
    }

    /**
     * The general error renderer used by the top-level boundary: a red
     * `✗ <message>` line, an optional dimmed `<hint>` line, and — only when
     * [showTraceHint] (i.e. NOT already in verbose mode) — a dimmed reminder
     * that `--verbose` reveals the full trace. The trace itself is printed
     * separately by the caller under `--verbose`; this method never emits it.
     */
    /** A yellow `⚠ <headline>` line plus dimmed, indented hint lines. */
    fun warning(headline: String, vararg hints: String): String {
        val sb = StringBuilder(yellow("${Glyphs.WARN} ") + bold(headline))
        for (h in hints) sb.append("\n    ").append(dim(h))
        return sb.toString()
    }

    private fun yellow(s: String) = if (color) "$ESC[33m$s$ESC[0m" else s

    fun generalError(message: String, hint: String? = null, showTraceHint: Boolean = true): String {
        val sb = StringBuilder(red("${Glyphs.CROSS} ") + bold(message))
        if (hint != null) sb.append("\n    ").append(dim(hint))
        if (showTraceHint) sb.append("\n    ").append(dim("re-run with --verbose for the full stack trace"))
        return sb.toString()
    }

    fun noManifest(): String = error(
        "no Mezze.toml in this directory",
        "run `mez init` to start a project,",
        "or `mez exec <file>` for a single file",
    )

    /** Malformed Mezze.toml. [detail] is the underlying parser message. */
    fun badManifest(detail: String?): String = error(
        "couldn't parse Mezze.toml",
        detail ?: "the file isn't valid TOML",
        "check for an unmatched bracket or a missing `=`",
    )

    fun noApp(packageName: String): String = error(
        "no [[app]] entry in Mezze.toml",
        "add one:",
        "  [[app]]",
        "  name = \"$packageName\"",
        "  path = \"src/app.mz\"",
    )

    fun appNotFound(name: String, available: List<String>): String = error(
        "app '$name' not found",
        "available: ${available.joinToString(", ")}",
    )

    fun appFileNotFound(file: File): String = error(
        "app file not found: ${file.path}",
        "check the `path` in your [[app]] entry",
    )

    // ── mez build summary ──────────────────────────────────────────────

    private val esc27 = 27.toChar().toString()  // byte-safe ANSI escape
    private fun green2(s: String) = if (color) "$esc27[32m$s$esc27[0m" else s
    private fun yellow2(s: String) = if (color) "$esc27[33m$s$esc27[0m" else s
    private fun bold2(s: String) = if (color) "$esc27[1m$s$esc27[0m" else s
    private fun dim2(s: String) = if (color) "$esc27[2m$s$esc27[0m" else s

    /** A red `✗ build failed` line with the underlying detail. */
    fun buildFailed(detail: String?): String {
        val head = if (color) "$esc27[31m${Glyphs.CROSS} build failed$esc27[0m" else "${Glyphs.CROSS} build failed"
        return if (detail != null) "$head\n    ${dim2(detail)}" else head
    }

    /** Human-readable byte size: `842 B`, `3.2 KB`, `18 MB`. */
    fun humanBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    /**
     * The `mez build` success summary. One artifact line per output (JAR +
     * optional native), each with its human size; a green `✓ built in <t>`
     * status; and a `Run` block with the exact commands. Optionally reports
     * warnings (yellow) that the build succeeded with.
     *
     * [artifacts] is (label, path-to-run, sizeBytes) triples, in display order.
     */
    fun buildSummary(
        appName: String,
        durationLabel: String,
        artifacts: List<Triple<String, String, Long>>,
        warningCount: Int = 0,
    ): String {
        val sb = StringBuilder()
        sb.append("  ").append(bold2("▸ build")).append(dim2(" · ")).append(appName).append('\n')
        for ((label, _, size) in artifacts) {
            sb.append("    ").append(dim2("$label  ${humanBytes(size)}")).append('\n')
        }
        if (warningCount > 0) {
            val w = if (warningCount == 1) "1 warning" else "$warningCount warnings"
            sb.append("    ").append(yellow2("${Glyphs.WARN} built with $w")).append('\n')
        }
        sb.append("  ").append(green2("${Glyphs.CHECK} built in $durationLabel")).append("\n\n")
        sb.append("  ").append(bold2("Run")).append('\n')
        for ((label, runPath, _) in artifacts) {
            sb.append("    ").append(runPath)
            if (label.startsWith("native")) sb.append(dim2("    (native, no JVM)"))
            sb.append('\n')
        }
        return sb.toString().trimEnd('\n')
    }
}
