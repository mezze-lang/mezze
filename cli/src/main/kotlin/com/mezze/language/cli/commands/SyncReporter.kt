package com.mezze.language.cli.commands

import kotlin.math.roundToInt

/**
 * Live progress for `mez sync`. Downloads run in parallel and precompiles run
 * in sequence, so a single reporter aggregates events from many threads.
 *
 * Default (a TTY): ONE sticky line at the bottom — a progress bar plus what's
 * happening right now (which package is downloading, with bytes, or compiling).
 * No per-package spam; only the final summary and any FAILURES scroll. Pass
 * [verbose] (`mez sync --verbose`) to also print a line per resolved package.
 *
 * Piped or captured (CI, the e2e scripts): no ANSI. Quiet by default (failures +
 * the caller's summary only); [verbose] emits a discrete line per event.
 *
 * MEZZE_NO_PROGRESS=1 forces the live line off; MEZZE_FORCE_PROGRESS=1 forces it
 * on (e.g. to demo it through a pipe).
 */
class SyncReporter(
    private val verbose: Boolean = false,
    private val live: Boolean = defaultLive(),
) {
    private val lock = Any()
    private class DL(var received: Long, var total: Long)
    private val active = LinkedHashMap<String, DL>()   // in-flight downloads
    private var totalDeps = 0
    private var resolved = 0
    private var compileTotal = 0
    private var compiled = 0
    private var failed = 0
    private var compiling: String? = null
    private var statusShown = false
    private var lastRenderNanos = 0L

    /** A dependency has entered the resolve graph. */
    fun discovered() = synchronized(lock) { totalDeps++; render(force = false) }

    fun downloadStart(name: String, total: Long) = synchronized(lock) {
        active[name] = DL(0, total)
        if (!live && verbose) emit("  ${Glyphs.DOWNLOAD} fetching $name${sizeSuffix(total)}")
        render(force = false)
    }

    fun downloadProgress(name: String, received: Long) = synchronized(lock) {
        active[name]?.received = received
        render(force = false)
    }

    /** A package finished resolving. [line] is the detailed `✅ name -> path`. */
    fun resolved(line: String, name: String) = synchronized(lock) {
        active.remove(name); resolved++
        if (verbose) emit(line)            // otherwise the bar speaks for it
        render(force = true)
    }

    /** A failure — always surfaced, in every mode. */
    fun failed(line: String, name: String) = synchronized(lock) {
        active.remove(name); failed++; emit(line); render(force = true)
    }

    /** Entering the precompile phase; [total] library deps will be compiled. */
    fun beginCompile(total: Int) = synchronized(lock) { compileTotal = total; render(force = true) }

    fun compileStart(name: String) = synchronized(lock) {
        compiling = name
        if (!live && verbose) emit("  ${Glyphs.GEAR} compiling $name")
        render(force = false)
    }

    fun compileDone() = synchronized(lock) { compiled++; compiling = null; render(force = true) }

    /** Clear the sticky line so the final summary prints cleanly. */
    fun finish() = synchronized(lock) { clearStatus() }

    // --- rendering -------------------------------------------------------

    private fun emit(line: String) {
        clearStatus()
        println(line)
    }

    private fun render(force: Boolean) {
        if (!live) return
        val now = System.nanoTime()
        if (!force && now - lastRenderNanos < 80_000_000L) return   // throttle to ~12 fps
        lastRenderNanos = now
        val status = statusLine() ?: run { clearStatus(); return }
        print("\r\u001b[2K$status")
        System.out.flush()
        statusShown = true
    }

    private fun clearStatus() {
        if (live && statusShown) {
            print("\r\u001b[2K")
            System.out.flush()
            statusShown = false
        }
    }

    /** The single sticky line: a bar for the current phase + what's happening now. */
    private fun statusLine(): String? {
        val compilePhase = compiling != null || compiled > 0
        return if (compilePhase) {
            val head = "${Glyphs.GEAR} ${bar(compiled, compileTotal)} $compiled/$compileTotal compiled"
            val now = compiling?.let { "  ·  $it" } ?: ""
            "  $head$now${failSuffix()}"
        } else {
            val head = if (totalDeps > 0)
                "${Glyphs.DOWNLOAD} ${bar(resolved, totalDeps)} $resolved/$totalDeps resolved"
            else
                "${Glyphs.DOWNLOAD} resolving…"
            "  $head${activitySuffix()}${failSuffix()}"
        }.takeIf { it.isNotBlank() }
    }

    /** What's downloading right now (one name with bytes, or an aggregate). */
    private fun activitySuffix(): String {
        if (active.isEmpty()) return ""
        if (active.size == 1) {
            val (name, d) = active.entries.first()
            val size = if (d.total > 0) " (${human(d.received)}/${human(d.total)})" else ""
            return "  ·  $name$size"
        }
        val recv = active.values.sumOf { it.received }
        val tot = active.values.fold(0L) { a, d -> if (d.total > 0) a + d.total else a }
        val size = if (tot > 0) " (${human(recv)}/${human(tot)})" else ""
        return "  ·  ${active.size} downloading$size"
    }

    private fun failSuffix() = if (failed > 0) "  ·  ${Glyphs.CROSS} $failed failed" else ""

    private fun bar(done: Int, total: Int, width: Int = 16): String {
        val filled = if (total <= 0) 0 else (done.toDouble() / total * width).roundToInt().coerceIn(0, width)
        return "[" + Glyphs.BAR_FULL.repeat(filled) + Glyphs.BAR_EMPTY.repeat(width - filled) + "]"
    }

    private fun sizeSuffix(total: Long) = if (total > 0) " (${human(total)})" else ""

    private fun human(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        return "%.1f MB".format(kb / 1024.0)
    }

    companion object {
        private fun defaultLive(): Boolean {
            if (System.getenv("MEZZE_NO_PROGRESS") != null) return false
            if (System.getenv("MEZZE_FORCE_PROGRESS") != null) return true
            return System.console() != null   // null when piped / redirected / CI
        }
    }
}
