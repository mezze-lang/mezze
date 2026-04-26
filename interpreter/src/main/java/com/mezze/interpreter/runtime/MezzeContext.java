package com.mezze.interpreter.runtime;

import com.mezze.interpreter.effects.EffectRegistry;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
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

    // Per-context closure cache — replaces the static evaluatedClosureCache in HashModuleLoaderNode.
    // Key: function hash, Value: evaluated MezzeFunction closure (or MezzeRecord for performers)
    private final ConcurrentHashMap<String, Object> closureCache = new ConcurrentHashMap<>();

    // Per-context user function IR registry — FunctionIRs from the current test's source code.
    // Cleared at start of each parse() via clearUserState().
    private final ConcurrentHashMap<String, com.mezze.language.truffleir.FunctionIR>
        userFunctionIRRegistry = new ConcurrentHashMap<>();

    // Per-context user CallTarget cache — compiled on demand from userFunctionIRRegistry.
    private final ConcurrentHashMap<String, CallTarget> userCallTargetCache = new ConcurrentHashMap<>();

    // Polyglot support (lazy initialization)
    private volatile MezzePolyglotSupport polyglotSupport;

    // Parser and typechecker — per-context, created lazily on first use.
    // NOT on MezzeLanguage (static fields break GraalVM native-image).
    // Prelude/stdlib caches live on TypeChecker.companion (JVM-wide singleton) —
    // so the second context reuses prelude work from the first.
    private volatile com.mezze.language.parser.MezzeParser parser;
    private volatile com.mezze.language.typechecker.TypeChecker typeChecker;

    public MezzeContext(MezzeLanguage language, Env env) {
        this.language = language;
        this.env = env;
    }

    /** Get the per-context parser (created lazily). */
    public com.mezze.language.parser.MezzeParser getParser() {
        var p = parser;
        if (p == null) {
            synchronized (this) {
                p = parser;
                if (p == null) {
                    p = new com.mezze.language.parser.MezzeParser();
                    parser = p;
                }
            }
        }
        return p;
    }

    /** Get the per-context typechecker (created lazily). */
    public com.mezze.language.typechecker.TypeChecker getTypeChecker() {
        var tc = typeChecker;
        if (tc == null) {
            synchronized (this) {
                tc = typeChecker;
                if (tc == null) {
                    tc = new com.mezze.language.typechecker.TypeChecker();
                    typeChecker = tc;
                }
            }
        }
        return tc;
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
    // User function state (per-context, cleared between tests)
    // ========================

    @TruffleBoundary
    public void registerUserFunctionIR(String hash, com.mezze.language.truffleir.FunctionIR ir) {
        userFunctionIRRegistry.put(hash, ir);
    }

    @TruffleBoundary
    public com.mezze.language.truffleir.FunctionIR getUserFunctionIR(String hash) {
        return userFunctionIRRegistry.get(hash);
    }

    @TruffleBoundary
    public java.util.Collection<com.mezze.language.truffleir.FunctionIR> getAllUserFunctionIRs() {
        return userFunctionIRRegistry.values();
    }

    @TruffleBoundary
    public CallTarget getUserCallTarget(String hash) {
        return userCallTargetCache.get(hash);
    }

    @TruffleBoundary
    public void cacheUserCallTarget(String hash, CallTarget ct) {
        userCallTargetCache.put(hash, ct);
    }

    /** Clear per-test state at start of each parse(). */
    public void clearUserState() {
        userFunctionIRRegistry.clear();
        userCallTargetCache.clear();
    }

    // ========================
    // Closure cache (per-context)
    // ========================

    @TruffleBoundary
    public Object getCachedClosure(String hash) {
        return closureCache.get(hash);
    }

    @TruffleBoundary
    public void putCachedClosure(String hash, Object value) {
        closureCache.put(hash, value);
    }

    // ========================
    // Global definitions
    // ========================

    @TruffleBoundary
    public void defineGlobal(String contentHash, Object value) {
        globals.put(contentHash, value);
    }

    /**
     * Get all global definitions. Used by test infrastructure to capture variable context.
     */
    @TruffleBoundary
    public Map<String, Object> getAllGlobals() {
        return new HashMap<>(globals);
    }

    @TruffleBoundary
    public Object lookupGlobal(String contentHash) {
        return globals.get(contentHash);
    }

    public java.util.Set<String> getAllGlobalKeys() {
        return globals.keySet();
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

    // Content hash → impl record (for O(1) lookup by hash)
    private final ConcurrentHashMap<String, Object> abilityImplsByHash = new ConcurrentHashMap<>();

    /**
     * Register an ability implementation by content hash.
     */
    @TruffleBoundary
    public void registerAbilityImplByHash(String contentHash, Object implRecord) {
        abilityImplsByHash.put(contentHash, implRecord);
    }

    /**
     * Look up an ability implementation by content hash.
     * Returns null if not found.
     */
    @TruffleBoundary
    public Object getAbilityImplByHash(String contentHash) {
        return abilityImplsByHash.get(contentHash);
    }

    // ========================
    // Effect Handler Stack (for lazy effects)
    // ========================

    // Thread-local handler stack for nested perform expressions
    private final ThreadLocal<Deque<Map<String, Object>>> handlerStack =
        ThreadLocal.withInitial(ArrayDeque::new);

    // OPTIMIZATION: Collapsed handler map for O(1) lookup (instead of O(N) stack search)
    // This map is updated on every push/pop to maintain a flattened view of all active handlers.
    // Inner scopes shadow outer scopes automatically since we rebuild on each push/pop.
    private final ThreadLocal<Map<String, Object>> collapsedHandlers =
        ThreadLocal.withInitial(HashMap::new);

    // OPTIMIZATION: Stack version counter for DynamicEffectCallNode caching
    // Incremented on every push/pop operation. Allows nodes to detect when their
    // cached handler resolution is stale without expensive identity comparisons.
    private final ThreadLocal<long[]> handlerStackVersion =
        ThreadLocal.withInitial(() -> new long[]{0L});

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
     *
     * OPTIMIZATION: Also updates collapsed handler map for O(1) lookup
     * and increments version counter for cache invalidation.
     */
    @TruffleBoundary
    public void pushHandlers(Map<String, Object> handlers) {
        handlerStack.get().push(new HashMap<>(handlers));
        // Update collapsed map - inner handlers shadow outer
        collapsedHandlers.get().putAll(handlers);
        // Increment version for cache invalidation
        handlerStackVersion.get()[0]++;
    }

    /**
     * Pop handlers from the stack.
     * Called when a perform expression completes.
     *
     * OPTIMIZATION: Rebuilds collapsed handler map and increments version counter.
     */
    @TruffleBoundary
    public void popHandlers() {
        Deque<Map<String, Object>> stack = handlerStack.get();
        if (!stack.isEmpty()) {
            stack.pop();
            // Rebuild collapsed map from remaining stack
            rebuildCollapsedHandlers();
            // Increment version for cache invalidation
            handlerStackVersion.get()[0]++;
        }
    }

    /**
     * Rebuild the collapsed handler map from the current stack.
     * Called after pop to ensure correctness when inner handlers are removed.
     */
    private void rebuildCollapsedHandlers() {
        Map<String, Object> collapsed = collapsedHandlers.get();
        collapsed.clear();
        // Iterate from bottom to top so inner scopes shadow outer
        Deque<Map<String, Object>> stack = handlerStack.get();
        for (Map<String, Object> handlers : stack) {
            collapsed.putAll(handlers);
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
     * OPTIMIZED: Uses collapsed handler map for O(1) lookup instead of O(N) stack search.
     *
     * @param effectContentHash The content hash of the effect (NOT the effect name)
     * @return The handler for the effect, or null if not found
     */
    @TruffleBoundary
    public Object getHandlerFor(String effectContentHash) {
        // O(1) lookup in collapsed map - no stack iteration needed
        return collapsedHandlers.get().get(effectContentHash);
    }

    public String getActiveEffectHashes() {
        var keys = collapsedHandlers.get().keySet();
        var sb = new StringBuilder("[");
        int i = 0;
        for (var k : keys) {
            if (i++ > 0) sb.append(", ");
            sb.append(k, 0, Math.min(16, k.length()));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Fallback handler search: scan all active handlers for one that contains
     * the given operation as a field. Used when hash lookup fails due to hash
     * mismatch between user modules and stdlib modules.
     *
     * @param operationName The operation name to look for (e.g., "log", "spawn")
     * @return The handler record, or null if not found
     */
    @TruffleBoundary
    public Object findHandlerWithOperation(String operationName) {
        Map<String, Object> collapsed = collapsedHandlers.get();
        for (Object handler : collapsed.values()) {
            if (handler instanceof com.mezze.interpreter.values.MezzeRecord record) {
                Object op = record.getField(operationName);
                if (op instanceof com.mezze.interpreter.values.MezzeFunction) {
                    return record;
                }
            }
        }
        return null;
    }

    /**
     * Get the current handler stack version.
     * Used by DynamicEffectCallNode for cache invalidation.
     * When the version changes, cached handler resolutions are stale.
     *
     * @return The current version counter (increments on every push/pop)
     */
    public long getHandlerStackVersion() {
        return handlerStackVersion.get()[0];
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
