package com.mezze.language.parser

import com.mezze.language.parsedast.ParsedModule

/**
 * Optional cache hook consulted by parser-using sites before invoking the
 * parser. The hook lets the CLI/LSP point parser callers at the project
 * SQLite cache without coupling parser/typechecker modules to the storage
 * layer (which lives downstream).
 *
 * ## Lifecycle
 *
 * Set once during process startup (CLI: from DevRunner / Check / Build /
 * Format; LSP: from MezzeTextDocumentService when the workspace cache
 * opens). Cleared on shutdown so caches don't outlive their backing files.
 *
 * Thread-safe: implementations are expected to handle concurrent access.
 *
 * ## Lookup keying
 *
 * Lookups are by `(filePath, sourceHash)` — *not* module path. The parser
 * doesn't know the module path; the resolver (or caller) does. Hooks that
 * need to map filePath → modulePath can do so internally; the cache row's
 * primary key is `(modulePath, sourceHash)`.
 *
 * `sourceHash` is the same SHA-256 of file bytes that StorePopulator uses,
 * so writes-from-StorePopulator and reads-from-cache match.
 */
interface ParsedModuleCache {
    /**
     * Try to resolve a cached ParsedModule for this file. Implementations
     * MUST verify the source_hash matches; stale rows MUST NOT be returned.
     *
     * @return cached ParsedModule, or null if absent / stale / on any error.
     *   Errors must be swallowed so callers can fall through to the parser.
     */
    fun get(filePath: String, sourceHash: String): ParsedModule?
}

/**
 * Process-wide slot for the active cache hook. Mutable; replaced at startup.
 * Default is no-op (cache disabled), so unit tests and stdlib precompile
 * don't need to install one.
 */
object ParsedModuleCacheHolder {
    @Volatile
    private var cache: ParsedModuleCache? = null

    fun set(c: ParsedModuleCache?) { cache = c }

    fun get(filePath: String, sourceHash: String): ParsedModule? =
        cache?.get(filePath, sourceHash)
}
