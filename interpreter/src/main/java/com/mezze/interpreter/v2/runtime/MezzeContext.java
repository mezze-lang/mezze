package com.mezze.interpreter.v2.runtime;

import com.mezze.interpreter.v2.cache.CompiledNodeCache;
import com.mezze.interpreter.v2.effects.EffectRegistry;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    // Ability Implementations
    // ========================

    // Ability name → impl record (for runtime ability lookups by name - legacy)
    private final ConcurrentHashMap<String, Object> abilityImpls = new ConcurrentHashMap<>();

    // Content hash → impl record (for O(1) lookup by hash - JIT-friendly)
    private final ConcurrentHashMap<String, Object> abilityImplsByHash = new ConcurrentHashMap<>();

    @TruffleBoundary
    public void registerAbilityImpl(String abilityName, Object implRecord) {
        abilityImpls.put(abilityName, implRecord);
    }

    /**
     * Register an ability implementation by content hash.
     * Used for polymorphic functions where the typechecker resolves implementations
     * and provides their content hashes.
     */
    @TruffleBoundary
    public void registerAbilityImplByHash(String contentHash, Object implRecord) {
        abilityImplsByHash.put(contentHash, implRecord);
    }

    @TruffleBoundary
    public Object lookupAbilityImpl(String abilityName) {
        return abilityImpls.get(abilityName);
    }

    /**
     * Look up an ability implementation by content hash.
     * Returns null if not found.
     */
    @TruffleBoundary
    public Object getAbilityImplByHash(String contentHash) {
        return abilityImplsByHash.get(contentHash);
    }

    @TruffleBoundary
    public String getRegisteredAbilityNames() {
        return abilityImpls.keySet().toString();
    }

    // ========================
    // Effect Handler Stack (for lazy effects)
    // ========================

    // Thread-local handler stack for nested perform expressions
    private final ThreadLocal<Deque<Map<String, Object>>> handlerStack =
        ThreadLocal.withInitial(ArrayDeque::new);

    // Effect name → set of performer names that handle it
    // e.g., "Log" → {"Console", "FileLogger"}
    private final ConcurrentHashMap<String, Set<String>> effectToPerformers = new ConcurrentHashMap<>();

    // Performer name → effect name (bidirectional mapping)
    // e.g., "Console" → "Log", "ToResult" → "Exception"
    private final ConcurrentHashMap<String, String> performerToEffect = new ConcurrentHashMap<>();

    // Global effects registry (effect name → effect record)
    private final ConcurrentHashMap<String, Object> globalEffects = new ConcurrentHashMap<>();

    /**
     * Get the global effect registry for bidirectional performer-effect lookups.
     * This is the centralized registry that provides O(1) lookups in both directions.
     */
    public EffectRegistry getEffectRegistry() {
        return EffectRegistry.getInstance();
    }

    /**
     * Push a set of handlers onto the handler stack.
     * Called by perform expressions before executing their body.
     */
    @TruffleBoundary
    public void pushHandlers(Map<String, Object> handlers) {
        handlerStack.get().push(new HashMap<>(handlers));
    }

    /**
     * Pop handlers from the stack.
     * Called when a perform expression completes.
     */
    @TruffleBoundary
    public void popHandlers() {
        Deque<Map<String, Object>> stack = handlerStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    /**
     * Get the current handlers from the top of the stack.
     * Returns null if no handlers are active.
     */
    @TruffleBoundary
    public Map<String, Object> getCurrentHandlers() {
        Deque<Map<String, Object>> stack = handlerStack.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * Check if there are any handlers on the stack.
     */
    @TruffleBoundary
    public boolean hasHandlers() {
        return !handlerStack.get().isEmpty();
    }

    /**
     * Check if a handler is currently available for the given effect.
     */
    @TruffleBoundary
    /**
     * Check if there's a handler for the given effect content hash.
     *
     * @param effectContentHash The content hash of the effect (NOT the effect name)
     * @return true if a handler exists for this effect
     */
    public boolean hasHandlerFor(String effectContentHash) {
        Map<String, Object> handlers = getCurrentHandlers();
        if (handlers == null) return false;

        // Direct O(1) lookup by content hash
        return handlers.containsKey(effectContentHash);
    }

    /**
     * Get the handler for an effect from the current handler stack.
     * Searches the ENTIRE stack from top to bottom (innermost to outermost handler).
     *
     * @param effectContentHash The content hash of the effect (NOT the effect name)
     * @return The handler for the effect, or null if not found
     */
    @TruffleBoundary
    public Object getHandlerFor(String effectContentHash) {
        Deque<Map<String, Object>> stack = handlerStack.get();
        if (stack.isEmpty()) return null;

        // Search the entire stack (top to bottom)
        // Handlers are keyed by content hash - direct O(1) lookup
        for (Map<String, Object> handlers : stack) {
            Object handler = handlers.get(effectContentHash);
            if (handler != null) return handler;
        }

        return null;
    }

    /**
     * Get all current handlers from the handler stack.
     * Returns a flattened map of all handler entries from all stack frames.
     * Used for capturing handlers to propagate to spawned threads.
     */
    @TruffleBoundary
    public Map<String, Object> getAllCurrentHandlers() {
        Deque<Map<String, Object>> stack = handlerStack.get();
        if (stack.isEmpty()) return null;

        // Flatten all stack frames into a single map
        // Later entries (inner scopes) override earlier entries (outer scopes)
        Map<String, Object> allHandlers = new HashMap<>();
        // Iterate from bottom to top so inner scopes override outer
        var list = new java.util.ArrayList<>(stack);
        java.util.Collections.reverse(list);
        for (Map<String, Object> handlers : list) {
            allHandlers.putAll(handlers);
        }
        return allHandlers;
    }

    /**
     * Register a performer as handling an effect.
     * Updates both forward and inverted indexes for O(1) bidirectional lookup.
     * e.g., registerPerformer("Console", "Log") means Console implements Log
     */
    @TruffleBoundary
    public void registerPerformer(String performerName, String effectName) {
        // Update forward mapping (performer -> effect)
        performerToEffect.put(performerName, effectName);

        // Update inverted index (effect -> performers)
        effectToPerformers.computeIfAbsent(effectName, k -> ConcurrentHashMap.newKeySet())
            .add(performerName);

        // Also register in global EffectRegistry
        getEffectRegistry().registerPerformer(performerName, effectName);
    }

    /**
     * Get the effect that a performer implements.
     * O(1) lookup via forward mapping.
     *
     * @param performerName The performer name (e.g., "Console")
     * @return The effect name (e.g., "Log"), or null if performer not registered
     */
    @TruffleBoundary
    public String getEffectForPerformer(String performerName) {
        return performerToEffect.get(performerName);
    }

    /**
     * Get all performers that implement a given effect.
     * O(1) lookup via inverted index.
     *
     * @param effectName The effect name (e.g., "Log")
     * @return Set of performer names (never null, may be empty)
     */
    @TruffleBoundary
    public Set<String> getPerformersForEffect(String effectName) {
        Set<String> performers = effectToPerformers.get(effectName);
        return performers != null ? performers : Set.of();
    }

    /**
     * Check if a performer handles a specific effect.
     */
    @TruffleBoundary
    public boolean performerHandlesEffect(String performerName, String effectName) {
        String effect = performerToEffect.get(performerName);
        return effect != null && effect.equals(effectName);
    }

    /**
     * Register a global effect declaration.
     */
    @TruffleBoundary
    public void registerEffect(String effectName, Object effectRecord) {
        globalEffects.put(effectName, effectRecord);

        // Also register in global EffectRegistry if it's a MezzeRecord
        if (effectRecord instanceof MezzeRecord record) {
            getEffectRegistry().registerEffect(effectName, record);
        }
    }

    /**
     * Get a global effect declaration.
     */
    @TruffleBoundary
    public Object getEffect(String effectName) {
        return globalEffects.get(effectName);
    }

    /**
     * Check if an effect is registered.
     */
    @TruffleBoundary
    public boolean hasEffect(String effectName) {
        return globalEffects.containsKey(effectName);
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
