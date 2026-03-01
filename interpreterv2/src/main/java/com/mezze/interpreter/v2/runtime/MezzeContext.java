package com.mezze.interpreter.v2.runtime;

import com.mezze.interpreter.v2.cache.CompiledNodeCache;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime context for Mezze execution.
 *
 * Contains:
 * - Global definitions (by content hash)
 * - CallTarget cache (hash → compiled target)
 * - Persistent compiled node cache
 * - Effect handler stack
 * - Polyglot support
 */
public final class MezzeContext {

    private final MezzeLanguage language;
    private final Env env;

    // Content hash → global value (functions, impls, etc.)
    private final ConcurrentHashMap<String, Object> globals = new ConcurrentHashMap<>();

    // Content hash → compiled CallTarget (for caching)
    private final ConcurrentHashMap<String, CallTarget> callTargetCache = new ConcurrentHashMap<>();

    // Content hashes marked for eager compilation (from warmup hints)
    private final java.util.Set<String> eagerCompilationHints = ConcurrentHashMap.newKeySet();

    // Persistent compiled node cache with disk backing
    private final CompiledNodeCache compiledNodeCache;

    // Polyglot support (lazy initialization)
    private volatile MezzePolyglotSupport polyglotSupport;

    public MezzeContext(MezzeLanguage language, Env env) {
        this.language = language;
        this.env = env;
        this.compiledNodeCache = new CompiledNodeCache();
    }

    /**
     * Get the context from a node.
     */
    public static MezzeContext get(Node node) {
        return MezzeLanguage.get(node).getCurrentContext();
    }

    public MezzeLanguage getLanguage() {
        return language;
    }

    public Env getEnv() {
        return env;
    }

    // ========================
    // Global definitions
    // ========================

    @TruffleBoundary
    public void defineGlobal(String contentHash, Object value) {
        globals.put(contentHash, value);
    }

    @TruffleBoundary
    public Object lookupGlobal(String contentHash) {
        return globals.get(contentHash);
    }

    @TruffleBoundary
    public boolean hasGlobal(String contentHash) {
        return globals.containsKey(contentHash);
    }

    // ========================
    // CallTarget cache
    // ========================

    @TruffleBoundary
    public CallTarget getCachedCallTarget(String contentHash) {
        return callTargetCache.get(contentHash);
    }

    @TruffleBoundary
    public void cacheCallTarget(String contentHash, CallTarget callTarget) {
        callTargetCache.put(contentHash, callTarget);
    }

    @TruffleBoundary
    public CallTarget getOrCacheCallTarget(String contentHash, java.util.function.Supplier<CallTarget> supplier) {
        return callTargetCache.computeIfAbsent(contentHash, k -> supplier.get());
    }

    // ========================
    // Compiled node cache
    // ========================

    /**
     * Get the persistent compiled node cache.
     */
    public CompiledNodeCache getCompiledNodeCache() {
        return compiledNodeCache;
    }

    /**
     * Get or compute a CallTarget using the persistent cache.
     * This checks both in-memory and disk cache hints.
     */
    @TruffleBoundary
    public CallTarget getOrComputeCached(String contentHash, java.util.function.Supplier<CallTarget> supplier) {
        // Try the persistent cache first (which includes in-memory)
        CallTarget cached = compiledNodeCache.get(contentHash);
        if (cached != null) {
            return cached;
        }

        // Compute and cache
        CallTarget computed = supplier.get();
        compiledNodeCache.put(contentHash, computed);
        return computed;
    }

    /**
     * Check if a function was previously compiled in any session.
     * Useful for warmup prioritization.
     */
    @TruffleBoundary
    public boolean wasPreviouslyCompiled(String contentHash) {
        return compiledNodeCache.wasPreviouslyCompiled(contentHash);
    }

    /**
     * Mark a content hash for eager compilation when encountered.
     * Used by warmup hints from previous sessions.
     */
    @TruffleBoundary
    public void markForEagerCompilation(String contentHash) {
        eagerCompilationHints.add(contentHash);
    }

    /**
     * Check if a content hash should be eagerly compiled.
     */
    @TruffleBoundary
    public boolean shouldEagerlyCompile(String contentHash) {
        return eagerCompilationHints.contains(contentHash);
    }

    // ========================
    // Local definitions
    // ========================

    // Local variables (thread-local scope)
    private final ConcurrentHashMap<String, Object> locals = new ConcurrentHashMap<>();

    @TruffleBoundary
    public void defineLocal(String name, Object value) {
        locals.put(name, value);
    }

    @TruffleBoundary
    public Object lookupLocal(String name) {
        return locals.get(name);
    }

    // ========================
    // Effect Handler
    // ========================

    // Placeholder for effect handler - to be implemented
    public final Object effectHandler = null;

    // ========================
    // Polyglot support
    // ========================

    @TruffleBoundary
    public MezzePolyglotSupport getPolyglotSupport() {
        if (polyglotSupport == null) {
            synchronized (this) {
                if (polyglotSupport == null) {
                    polyglotSupport = new MezzePolyglotSupport(env);
                }
            }
        }
        return polyglotSupport;
    }

    /**
     * Check if a polyglot language is available.
     */
    @TruffleBoundary
    public boolean isLanguageAvailable(String languageId) {
        return getPolyglotSupport().isLanguageAvailable(languageId);
    }
}
