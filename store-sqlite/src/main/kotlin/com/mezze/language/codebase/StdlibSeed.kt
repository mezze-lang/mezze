package com.mezze.language.codebase

import com.mezze.language.store.*

import java.io.File

/**
 * In the three-file world stdlib lives in its own attached SQLite at
 * `.mz/stdlib.sqlite`, materialised once per project from the bundled
 * `stdlib.sqlite` JAR resource. There's no row-level copy any more —
 * the bundle IS the project's stdlib file.
 *
 * Most of this is now handled inside `SQLiteMezzeStore.openProject`
 * (the file copy + ATTACH happens there). This object stays as the
 * canonical entry for tools that want to seed stdlib without going
 * through openProject — primarily the build-time `StdlibPrecompiler`
 * tooling.
 */
object StdlibSeed {

    /**
     * Ensure `.mz/stdlib.sqlite` exists for the given project, copying
     * the bundle in if it's missing. Returns true if a copy ran, false
     * if the file already existed.
     */
    fun seedIfMissing(projectRoot: File): Boolean {
        val stdlibFile = SQLiteMezzeStore.resolveStdlibFile(projectRoot)
        if (stdlibFile.exists()) return false
        val stream = StdlibSeed::class.java.classLoader
            .getResourceAsStream(SQLiteMezzeStore.STDLIB_FILE) ?: return false
        stdlibFile.parentFile?.mkdirs()
        stream.use { it.copyTo(stdlibFile.outputStream()) }
        return true
    }
}
