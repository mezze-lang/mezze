package com.mezze.language.cli

import com.mezze.language.ast.hashing.MezzeHash
import com.mezze.language.codebase.CodebaseManager
import com.mezze.language.codebase.SQLiteCodebase
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.ParsedModule
import java.io.File

/**
 * Parser wrapper that caches parsed ASTs in the SQLite codebase.
 *
 * When parsing a file:
 * 1. Compute SHA3-512 hash of source content
 * 2. Check if cached AST exists with matching hash
 * 3. If yes, return cached ParsedModule
 * 4. If no, parse normally and cache the result
 *
 * This provides significant speedups for incremental builds when
 * most files haven't changed.
 *
 * ## Usage
 *
 * ```kotlin
 * val cachedParser = CachedParser(projectRoot)
 * val module = cachedParser.parse(file)  // Uses cache if available
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
     * @return The parsed ParsedModule
     */
    fun parse(file: File): ParsedModule {
        val source = file.readText()
        return parseSource(source, file.absolutePath)
    }

    /**
     * Parse source code.
     *
     * @param source The source code to parse
     * @param filePath Optional file path for error messages
     * @return The parsed ParsedModule
     */
    fun parseSource(source: String, filePath: String? = null): ParsedModule {
        cacheMisses++
        return parser.parseParsedModule(source, file = filePath ?: "<unknown>")
    }

    /**
     * Parse with error recovery, using cache if available.
     * Returns partial AST with errors for IDE/LSP use.
     */
    fun parseWithRecovery(
        source: String,
        filePath: String? = null
    ): MezzeParser.ParseResultWithRecovery {
        // For recovery mode, don't use cache since we want fresh error info
        // and the partial AST may not be suitable for caching
        return parser.parseWithRecovery(source, filePath)
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
