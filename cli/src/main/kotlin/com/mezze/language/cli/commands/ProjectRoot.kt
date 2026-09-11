package com.mezze.language.cli.commands

import java.io.File

/**
 * Where a Mezze project *is*, resolved the way every other toolchain resolves it:
 * the nearest ancestor directory (starting at the cwd) that holds a `Mezze.toml`.
 *
 * This is the one rule for "the project root", used by check / build / dev / sync
 * and the `.mez/` store location. Running a command from any subdirectory of a
 * project therefore behaves identically to running it from the root — and `.mez/`
 * is always created at the root, never scattered in whichever folder you happened
 * to be in (the way git finds `.git`, cargo finds `Cargo.toml`).
 *
 * Nested projects: the *nearest* `Mezze.toml` wins — a subdirectory with its own
 * `Mezze.toml` is its own independent project, and walk-up stops there.
 */
object ProjectRoot {

    const val MANIFEST = "Mezze.toml"

    // Generous but finite, so a stray cwd far from any project can't walk to `/`.
    private const val MAX_DEPTH = 40

    /**
     * The nearest directory at or above [start] containing a `Mezze.toml`, or null
     * if there is none (i.e. not inside a project).
     */
    fun discover(start: File): File? {
        var dir: File? = start.absoluteFile
        var depth = 0
        while (dir != null && depth++ < MAX_DEPTH) {
            if (File(dir, MANIFEST).isFile) return dir
            dir = dir.parentFile
        }
        return null
    }
}
