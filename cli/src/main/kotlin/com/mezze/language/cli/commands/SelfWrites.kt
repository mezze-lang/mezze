package com.mezze.language.cli.commands

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Files this process wrote, so the file watcher does not react to them.
 *
 * ## The problem
 *
 * A committed [Changeset] writes the files it just type-checked. The watcher then
 * fires for those writes and runs the whole cycle again — invalidate, recompile,
 * reload, re-diagnose — which is exactly the duplicated work a declared changeset
 * exists to remove, arriving by a different route. The two must not both act.
 *
 * ## Why content, not time
 *
 * The obvious fix is to mute the watcher for a moment after writing. That would
 * reintroduce the thing W-2 rejects: inferring a boundary from TIMING. A window too
 * short lets the duplicate through, one too long swallows a real edit made while it
 * was open, and no value is right on every filesystem.
 *
 * So the question asked is not "was this recent" but "is this file's content exactly
 * what we wrote". Content-addressed, like everything else here — and the hash is
 * already computed for the store write, so it costs nothing.
 *
 * Two consequences worth stating, because they are the design and not an oversight:
 *
 *  - A record is NOT consumed on the first match. macOS fires several events per
 *    save, and consuming would suppress one and let the rest through — the very
 *    double-reload being prevented. A record stays until the content differs.
 *  - A user who edits a file to byte-for-byte what we wrote IS suppressed. That edit
 *    changes nothing, so there is nothing to reload; suppressing it is correct rather
 *    than merely tolerable.
 *
 * A real edit changes the hash and passes straight through. That is the property that
 * matters: suppression can never swallow a change.
 */
object SelfWrites {

    /** Absolute path → SHA-256 of the content this process last wrote there. */
    private val written = ConcurrentHashMap<String, String>()

    /** Remember that [content] was written to [file] by us. */
    fun record(file: File, content: String) {
        written[key(file)] = sha256(content)
    }

    /**
     * True when [file] currently holds exactly what this process last wrote there.
     *
     * Reads the file, so it is called only for a `.mz` change event — not on any hot
     * path. An unreadable file answers false: if we cannot tell, the event must get
     * through, because dropping a real change is the failure that matters and a
     * redundant reload is only slow.
     */
    fun wasSelfWritten(file: File): Boolean {
        val expected = written[key(file)] ?: return false
        val actual = try {
            sha256(file.readText())
        } catch (_: Exception) {
            return false
        }
        if (actual != expected) {
            // The file has moved on; the record describes nothing now.
            written.remove(key(file))
            return false
        }
        return true
    }

    /** Forget [file], so the next event for it is treated as a real change. */
    fun forget(file: File) {
        written.remove(key(file))
    }

    /** Drop every record. For tests, and for a session that is starting over. */
    fun clear() = written.clear()

    /** How many files are currently recorded. For tests. */
    fun size(): Int = written.size

    /**
     * Canonical path, so `./src/a.mz` and `/abs/src/a.mz` are one key.
     *
     * Falls back to the absolute path when canonicalisation fails — a file that does
     * not exist yet has no canonical form, and a changeset that ADDS a module records
     * its write before anything can read it.
     */
    private fun key(file: File): String =
        try { file.canonicalPath } catch (_: Exception) { file.absolutePath }

    private fun sha256(content: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
