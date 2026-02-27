package com.mezze.interpreter.v2.runtime;

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

    // Polyglot support (lazy initialization)
    private volatile MezzePolyglotSupport polyglotSupport;

    public MezzeContext(MezzeLanguage language, Env env) {
        this.language = language;
        this.env = env;
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
