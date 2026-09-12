package com.mezze.language.cli.commands

/**
 * Live progress for `mez sync`. Downloads run in parallel and precompiles run
 * in sequence, so a single reporter aggregates events from many threads.
 *
 * On a TTY it paints ONE sticky status line at the bottom — running download
 * bytes, resolved count, and the current compile — and lets per-package
 * completion lines scroll above it. When output is piped or captured (CI, the
 * e2e scripts) it falls back to discrete lines and no ANSI, so logs stay clean.
 *
 * Disable entirely with MEZZE_NO_PROGRESS=1; force the live line on
 * (e.g. to demo it through a pipe) with MEZZE_FORCE_PROGRESS=1.
 */
class SyncReporter(
    private val live: Boolean = defaultLive(),
) {
    private val lock = Any()
    private class DL(var received: Long, var total: Long)
    private val active = LinkedHashMap<String, DL>()   // in-flight downloads
    private var totalDeps = 0
    private var resolved = 0
    private var compiled = 0
    private var failed = 0
    private var compiling: String? = null
    private var statusShown = false
    private var lastRenderNanos = 0L

    /** A dependency has entered the resolve graph. */
    fun discovered() = synchronized(lock) { totalDeps++; render(force = false) }

    fun downloadStart(name: String, total: Long) = synchronized(lock) {
        active[name] = DL(0, total)
        if (!live) emit("  ${Glyphs.DOWNLOAD} fetching $name${sizeSuffix(total)}")
        render(force = false)
    }

    fun downloadProgress(name: String, received: Long) = synchronized(lock) {
        active[name]?.received = received
        render(force = false)
    }

    /** Per-package completion line (kept identical to the old output). */
    fun resolved(line: String, name: String) = synchronized(lock) {
        active.remove(name); resolved++; emit(line); render(force = true)
    }

    fun failed(line: String, name: String) = synchronized(lock) {
        active.remove(name); failed++; emit(line); render(force = true)
    }

    fun compileStart(name: String) = synchronized(lock) {
        compiling = name
        if (!live) emit("  ${Glyphs.GEAR} compiling $name")
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

    private fun statusLine(): String? {
        val parts = ArrayList<String>(4)
        if (active.isNotEmpty()) {
            val recv = active.values.sumOf { it.received }
            val tot = active.values.fold(0L) { a, d -> if (d.total > 0) a + d.total else a }
            val size = if (tot > 0) "${human(recv)}/${human(tot)}" else human(recv)
            parts += "${Glyphs.DOWNLOAD} ${active.size} fetching ($size)"
        }
        if (totalDeps > 0) parts += "${Glyphs.CHECK} $resolved/$totalDeps resolved"
        compiling?.let { parts += "${Glyphs.GEAR} compiling $it" }
            ?: run { if (compiled > 0) parts += "${Glyphs.GEAR} $compiled compiled" }
        if (failed > 0) parts += "${Glyphs.CROSS} $failed failed"
        if (parts.isEmpty()) return null
        return "  " + parts.joinToString("  ·  ")
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
