package com.mezze.language.codebase

import java.io.File
import java.nio.file.Path

/**
 * Manages codebase instances for different execution contexts.
 *
 * This class encapsulates the logic for:
 * - Script mode: in-memory codebase (no persistence)
 * - Project mode: file-based codebase at .mez/codebase.db
 *
 * ## Usage
 *
 * ```kotlin
 * // For a file being edited/executed
 * val codebase = CodebaseManager.forPath(filePath)
 *
 * // For explicit project root
 * val codebase = CodebaseManager.forProjectRoot(projectDir)
 *
 * // Always in-memory (e.g., for compile to JAR)
 * val codebase = CodebaseManager.inMemory()
 * ```
 */
object CodebaseManager {

    /**
     * Get or create a codebase for a given file path.
     *
     * If the file is within a project (has Mezze.toml in parent directories),
     * returns a persistent file-based codebase at .mez/codebase.db.
     *
     * Otherwise, returns an in-memory codebase.
     *
     * @param filePath Path to a .mz file
     * @return A codebase instance (caller must close)
     */
    fun forPath(filePath: Path): Codebase {
        val projectRoot = findProjectRoot(filePath.toFile())
        return if (projectRoot != null) {
            SQLiteCodebase.forProject(projectRoot)
        } else {
            SQLiteCodebase.inMemory()
        }
    }

    /**
     * Get or create a codebase for a given file.
     */
    fun forFile(file: File): Codebase {
        return forPath(file.toPath())
    }

    /**
     * Get or create a codebase for an explicit project root.
     *
     * @param projectRoot Directory containing Mezze.toml
     * @return A persistent file-based codebase at .mez/codebase.db
     */
    fun forProjectRoot(projectRoot: File): Codebase {
        return SQLiteCodebase.forProject(projectRoot)
    }

    /**
     * Get or create a codebase for an explicit project root.
     */
    fun forProjectRoot(projectRoot: Path): Codebase {
        return forProjectRoot(projectRoot.toFile())
    }

    /**
     * Create an in-memory codebase.
     *
     * Use this for:
     * - Script execution without persistence
     * - Compile to JAR (intermediate storage)
     * - Testing
     */
    fun inMemory(): Codebase {
        return SQLiteCodebase.inMemory()
    }

    /**
     * Check if a path is within a project.
     */
    fun isInProject(filePath: Path): Boolean {
        return findProjectRoot(filePath.toFile()) != null
    }

    /**
     * Check if a path is within a project.
     */
    fun isInProject(file: File): Boolean {
        return findProjectRoot(file) != null
    }

    /**
     * Find the project root by looking for Mezze.toml in parent directories.
     *
     * @param startPath A file or directory to start searching from
     * @return The project root directory, or null if not in a project
     */
    fun findProjectRoot(startPath: File): File? {
        var current = if (startPath.isFile) startPath.parentFile else startPath

        while (current != null) {
            if (File(current, "Mezze.toml").exists()) {
                return current
            }
            current = current.parentFile
        }

        return null
    }

    /**
     * Find the project root by looking for Mezze.toml in parent directories.
     */
    fun findProjectRoot(startPath: Path): File? {
        return findProjectRoot(startPath.toFile())
    }

    /**
     * Get the codebase file path for a project.
     *
     * @param projectRoot The project root directory
     * @return The path to .mez/codebase.db
     */
    fun getCodebasePath(projectRoot: File): File {
        return File(projectRoot, "${SQLiteCodebase.PROJECT_DIR}/${SQLiteCodebase.CODEBASE_FILE}")
    }

    /**
     * Check if a project has an existing codebase.
     */
    fun hasCodebase(projectRoot: File): Boolean {
        return SQLiteCodebase.hasCodebase(projectRoot)
    }

    /**
     * Delete the codebase for a project (for clean rebuilds).
     *
     * @param projectRoot The project root directory
     * @return true if the codebase was deleted, false if it didn't exist
     */
    fun deleteCodebase(projectRoot: File): Boolean {
        val dbFile = getCodebasePath(projectRoot)
        if (dbFile.exists()) {
            // Also delete WAL and SHM files if they exist
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()
            return dbFile.delete()
        }
        return false
    }
}
