package com.mezze.language.cli

import com.mezze.language.ast.TypeCheckResult
import com.mezze.language.codebase.CodebaseManager
import com.mezze.language.codebase.CodebasePopulator
import com.mezze.language.typechecker.cache.TypeCheckCache
import java.io.File
import java.nio.file.Path

/**
 * Helper for populating the codebase after type checking.
 *
 * This is used by CLI commands (build, check, dev) and the LSP
 * to store type-checked definitions in the SQLite codebase.
 */
object CodebaseHelper {

    /**
     * Populate the codebase after successful type check.
     *
     * Only populates if the project root contains a Mezze.toml file.
     * Creates .mez/codebase.db if it doesn't exist.
     *
     * @param projectRoot The project root (directory with Mezze.toml)
     * @param typeCheckResult The successful type check result
     * @param modulePath Optional module path for the main program
     * @return true if codebase was populated, false if not in project mode
     */
    fun populateIfInProject(
        projectRoot: Path,
        typeCheckResult: TypeCheckResult,
        modulePath: String? = null
    ): Boolean {
        val projectFile = projectRoot.toFile()

        if (!CodebaseManager.isInProject(projectFile)) {
            return false
        }

        val codebase = CodebaseManager.forProjectRoot(projectFile)
        try {
            // Initialize TypeCheckCache with SQLite codebase for L2 persistence
            TypeCheckCache.initialize(codebase)

            // Note: We only store the main program for now
            // Module programs are stored separately when they are type-checked
            CodebasePopulator.populate(
                codebase = codebase,
                typeCheckResult = typeCheckResult,
                modules = emptyMap(),  // Modules stored separately
                mainModulePath = modulePath
            )
            return true
        } finally {
            codebase.close()
        }
    }

    /**
     * Populate the codebase after successful type check.
     *
     * @param projectRoot The project root (directory with Mezze.toml)
     * @param typeCheckResult The successful type check result
     * @param modulePath Optional module path for the main program
     * @return true if codebase was populated, false if not in project mode
     */
    fun populateIfInProject(
        projectRoot: File,
        typeCheckResult: TypeCheckResult,
        modulePath: String? = null
    ): Boolean {
        return populateIfInProject(projectRoot.toPath(), typeCheckResult, modulePath)
    }
}
