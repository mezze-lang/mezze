package com.mezze.language.codebase

import com.mezze.language.parsedast.MODULE_PATH_SEPARATOR
import java.io.File

/**
 * Single source of truth for deriving a module's canonical path string from
 * its physical file location.
 *
 * The convention follows ModuleResolver: `module::path::leaf` resolves to
 * `<projectRoot>/src/module/path/leaf.mz`. So the inverse, given a file at
 * `<projectRoot>/src/<rel>.mz`, is `<rel>` with `/` → `::`.
 *
 * Two cases need special handling:
 *
 * 1. **The entry app** — `Mezze.toml` says the package is `myproj` with
 *    entry at `src/app.mz`. The resolver doesn't see this as a normal
 *    `src/<rel>` file; the entry is mapped to module `myproj` directly.
 *    Callers tell us this via [entryFilePath] + [packageName].
 *
 * 2. **Files outside `src/`** — return null; caller decides whether that's
 *    an error or a one-off scratch file we don't index.
 *
 * Used by:
 * - DevRunner / BuildCommand: derive modulePath for every file the same
 *   way the typechecker's resolver does.
 * - LSP: same rule, so concurrent LSP and `mez dev` write to the same
 *   `(modulePath, sourceHash)` keys in cache.sqlite.
 */
object ModulePath {

    /**
     * Derive the canonical modulePath for [file], or null if the file lives
     * outside `[projectRoot]/src/`.
     *
     * @param projectRoot directory containing Mezze.toml
     * @param file the source .mz file
     * @param packageName `[package].name` from Mezze.toml
     * @param entryFilePath optional absolute path of the entry app file. If
     *        [file] equals this, the result is just [packageName] regardless
     *        of where the file lives under `src/`.
     */
    fun derive(
        projectRoot: File,
        file: File,
        packageName: String,
        entryFilePath: String? = null,
    ): String? {
        val fileAbs = file.absolutePath
        if (entryFilePath != null && fileAbs == File(entryFilePath).absolutePath) {
            return packageName
        }

        val rootPath = projectRoot.toPath().toAbsolutePath().normalize()
        val srcPath = rootPath.resolve("src")
        val filePath = file.toPath().toAbsolutePath().normalize()
        if (!filePath.startsWith(srcPath)) return null

        val rel = srcPath.relativize(filePath)
        val parts = rel.toList().mapIndexed { i, p ->
            val s = p.toString()
            if (i == rel.nameCount - 1 && s.endsWith(".mz")) s.removeSuffix(".mz") else s
        }
        if (parts.isEmpty()) return null
        return parts.joinToString(MODULE_PATH_SEPARATOR)
    }
}
