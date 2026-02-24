package com.mezze.language.cli

import com.mezze.language.ast.Program
import com.mezze.language.ast.hashing.MezzeHash
import com.mezze.language.codebase.CodebaseManager
import com.mezze.language.codebase.CodebaseSerialization
import com.mezze.language.codebase.SQLiteCodebase
import com.mezze.language.parser.MezzeParser
import java.io.File

/**
 * Parser wrapper that caches parsed ASTs in the SQLite codebase.
 *
 * When parsing a file:
 * 1. Compute SHA3-512 hash of source content
 * 2. Check if cached AST exists with matching hash
 * 3. If yes, deserialize and return cached AST
 * 4. If no, parse normally and cache the result
 *
 * This provides significant speedups for incremental builds when
 * most files haven't changed.
 *
 * ## Usage
 *
 * ```kotlin
 * val cachedParser = CachedParser(projectRoot)
 * val program = cachedParser.parse(file)  // Uses cache if available
 * cachedParser.close()  // Important: close when done
 * ```
 *
 * ## Thread Safety
 *
 * Not thread-safe. Each thread should use its own CachedParser instance.
 */
class CachedParser(
    projectRoot: File,
    private val parser: MezzeParser = MezzeParser()
) : AutoCloseable {

    private val codebase: SQLiteCodebase? =
        if (CodebaseManager.isInProject(projectRoot)) {
            try {
                SQLiteCodebase.forProject(projectRoot)
            } catch (e: Exception) {
                // If we can't open the codebase, just parse without caching
                null
            }
        } else {
            null
        }

    /**
     * Statistics for debugging/optimization.
     */
    var cacheHits = 0
        private set
    var cacheMisses = 0
        private set

    /**
     * Parse a file, using cache if available.
     *
     * @param file The source file to parse
     * @param desugar Whether to desugar the AST (default true)
     * @return The parsed Program
     */
    fun parse(file: File, desugar: Boolean = true): Program {
        val source = file.readText()
        return parseSource(source, desugar, file.absolutePath)
    }

    /**
     * Parse source code, using cache if available.
     *
     * @param source The source code to parse
     * @param desugar Whether to desugar the AST (default true)
     * @param filePath Optional file path for cache key and error messages
     * @return The parsed Program
     */
    fun parseSource(source: String, desugar: Boolean = true, filePath: String? = null): Program {
        // If no codebase or no file path, just parse normally
        if (codebase == null || filePath == null) {
            return parser.parse(source, desugar, filePath)
        }

        // Compute source hash
        val sourceHash = MezzeHash.fromString(source).toFullHexString()

        // Try to get from cache
        val cachedAst = codebase.getCachedAst(filePath, sourceHash)
        if (cachedAst != null) {
            try {
                val program = CodebaseSerialization.deserializeProgram(cachedAst)
                cacheHits++
                return program
            } catch (e: Exception) {
                // Cache corrupted or incompatible, parse fresh
                codebase.clearFileCache(filePath)
            }
        }

        // Cache miss - parse and cache
        cacheMisses++
        val program = parser.parse(source, desugar, filePath)

        // Cache the result
        try {
            val serialized = CodebaseSerialization.serializeProgram(program)
            codebase.cacheAst(filePath, sourceHash, serialized)
        } catch (e: Exception) {
            // Caching failed, but parsing succeeded - continue
        }

        return program
    }

    /**
     * Parse with error recovery, using cache if available.
     * Returns partial AST with errors for IDE/LSP use.
     */
    fun parseWithRecovery(
        source: String,
        desugar: Boolean = true,
        filePath: String? = null
    ): MezzeParser.ParseResultWithRecovery {
        // For recovery mode, don't use cache since we want fresh error info
        // and the partial AST may not be suitable for caching
        return parser.parseWithRecovery(source, desugar, filePath)
    }

    /**
     * Get the underlying parser (for accessing warnings, etc.)
     */
    fun getParser(): MezzeParser = parser

    /**
     * Clear the entire file cache.
     */
    fun clearCache() {
        codebase?.clearAllFileCache()
    }

    /**
     * Clear cache for a specific file.
     */
    fun clearCache(filePath: String) {
        codebase?.clearFileCache(filePath)
    }

    override fun close() {
        codebase?.close()
    }
}

/**
 * Parse multiple files in parallel with caching.
 *
 * This is optimized for batch operations like `mezze build` where
 * many files need to be parsed.
 *
 * @param projectRoot The project root (directory with Mezze.toml)
 * @param files The files to parse
 * @param desugar Whether to desugar the ASTs
 * @return Map of file path to parsed Program
 */
fun parseFilesWithCache(
    projectRoot: File,
    files: List<File>,
    desugar: Boolean = true
): Map<String, Program> {
    if (files.isEmpty()) return emptyMap()

    // Open a shared codebase for all files
    val codebase: SQLiteCodebase? =
        if (CodebaseManager.isInProject(projectRoot)) {
            try {
                SQLiteCodebase.forProject(projectRoot)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

    try {
        return files.associate { file ->
            val filePath = file.absolutePath
            val source = file.readText()
            val sourceHash = MezzeHash.fromString(source).toFullHexString()

            val program = if (codebase != null) {
                // Try cache first
                val cachedAst = codebase.getCachedAst(filePath, sourceHash)
                if (cachedAst != null) {
                    try {
                        CodebaseSerialization.deserializeProgram(cachedAst)
                    } catch (e: Exception) {
                        // Cache miss - parse fresh
                        parseAndCache(source, filePath, sourceHash, codebase, desugar)
                    }
                } else {
                    parseAndCache(source, filePath, sourceHash, codebase, desugar)
                }
            } else {
                MezzeParser().parse(source, desugar, filePath)
            }

            filePath to program
        }
    } finally {
        codebase?.close()
    }
}

private fun parseAndCache(
    source: String,
    filePath: String,
    sourceHash: String,
    codebase: SQLiteCodebase,
    desugar: Boolean
): Program {
    val parser = MezzeParser()
    val program = parser.parse(source, desugar, filePath)

    try {
        val serialized = CodebaseSerialization.serializeProgram(program)
        codebase.cacheAst(filePath, sourceHash, serialized)
    } catch (e: Exception) {
        // Caching failed, but parsing succeeded
    }

    return program
}
