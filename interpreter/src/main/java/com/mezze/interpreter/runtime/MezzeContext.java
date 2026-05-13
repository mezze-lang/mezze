package com.mezze.interpreter.runtime;

import com.mezze.interpreter.effects.EffectRegistry;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.instrumentation.AllocationReporter;
import com.oracle.truffle.api.nodes.Node;

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
 * - Effect performer registry (by-name lookups)
 * - Polyglot support
 */
public final class MezzeContext {

    private final MezzeLanguage language;
    private final Env env;

    // Content hash → global value (functions, impls, etc.)
    private final ConcurrentHashMap<Hash, Object> globals = new ConcurrentHashMap<>();

    // Variable name → value, ONLY for test inspection via getLastExecutionContext().
    // Strictly separate from `globals` so name vs hash can never collide at the
    // dispatch layer. Populated by WriteLocalWithContextNode for top-level lets.
    private final ConcurrentHashMap<String, Object> testInspectionVars = new ConcurrentHashMap<>();

    // Content hash → compiled CallTarget (for caching)
    private final ConcurrentHashMap<Hash, CallTarget> callTargetCache = new ConcurrentHashMap<>();

    // Per-context closure cache — replaces the static evaluatedClosureCache in HashModuleLoaderNode.
    // Key: function hash, Value: evaluated MezzeFunction closure (or MezzeRecord for performers)
    private final ConcurrentHashMap<Hash, Object> closureCache = new ConcurrentHashMap<>();

    // Per-context user function IR registry — FunctionIRs from the current test's source code.
    // Cleared at start of each parse() via clearUserState().
    private final ConcurrentHashMap<Hash, com.mezze.language.truffleir.FunctionIR>
        userFunctionIRRegistry = new ConcurrentHashMap<>();

    // Per-context user CallTarget cache — compiled on demand from userFunctionIRRegistry.
    private final ConcurrentHashMap<Hash, CallTarget> userCallTargetCache = new ConcurrentHashMap<>();

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

    // Cached AllocationReporter — Env.lookup() is not free, and this gets
    // called from every record / seq / variant allocation. Looking it up
    // once and stashing the reference matches the Truffle examples.
    private volatile AllocationReporter allocationReporter;

    /**
     * The Truffle AllocationReporter for this context. Always non-null —
     * Truffle returns a no-op reporter when no instrument is listening,
     * so allocation sites can call it unconditionally.
     */
    public AllocationReporter getAllocationReporter() {
        AllocationReporter r = allocationReporter;
        if (r == null) {
            synchronized (this) {
                r = allocationReporter;
                if (r == null) {
                    r = env.lookup(AllocationReporter.class);
                    allocationReporter = r;
                }
            }
        }
        return r;
    }

    /**
     * Allocation-reporter accessor that's safe to call from a Node.execute()
     * even when no polyglot context is active (e.g., from unit tests that
     * exercise nodes directly without spinning up a full Engine).
     *
     * Returns null when there's no active context. Allocation-site callers
     * should null-check and skip reporting in that case — there's no
     * instrument listening anyway, so skipping is observationally identical.
     */
    public static AllocationReporter tryGetAllocationReporter(Node node) {
        try {
            return get(node).getAllocationReporter();
        } catch (Throwable t) {
            // No polyglot context entered — happens in unit tests that
            // construct + execute nodes outside Engine.create().
            return null;
        }
    }

    // ========================
    // User function state (per-context, cleared between tests)
    // ========================

    @TruffleBoundary
    public void registerUserFunctionIR(Hash hash, com.mezze.language.truffleir.FunctionIR ir) {
        userFunctionIRRegistry.put(hash, ir);
    }

    @TruffleBoundary
    public com.mezze.language.truffleir.FunctionIR getUserFunctionIR(Hash hash) {
        return userFunctionIRRegistry.get(hash);
    }

    @TruffleBoundary
    public java.util.Collection<com.mezze.language.truffleir.FunctionIR> getAllUserFunctionIRs() {
        return userFunctionIRRegistry.values();
    }

    @TruffleBoundary
    public CallTarget getUserCallTarget(Hash hash) {
        return userCallTargetCache.get(hash);
    }

    @TruffleBoundary
    public void cacheUserCallTarget(Hash hash, CallTarget ct) {
        userCallTargetCache.put(hash, ct);
    }

    /** Clear per-test state at start of each parse(). */
    public void clearUserState() {
        userFunctionIRRegistry.clear();
        userCallTargetCache.clear();
        testInspectionVars.clear();
    }

    // ========================
    // Closure cache (per-context)
    // ========================

    @TruffleBoundary
    public Object getCachedClosure(Hash hash) {
        return closureCache.get(hash);
    }

    @TruffleBoundary
    public void putCachedClosure(Hash hash, Object value) {
        closureCache.put(hash, value);
    }

    // ========================
    // Global definitions
    // ========================

    @TruffleBoundary
    public void defineGlobal(Hash contentHash, Object value) {
        globals.put(contentHash, value);
    }

    /**
     * Get all global definitions. Used by test infrastructure to capture variable context.
     */
    @TruffleBoundary
    public Map<Hash, Object> getAllGlobals() {
        return new HashMap<>(globals);
    }

    @TruffleBoundary
    public Object lookupGlobal(Hash contentHash) {
        return globals.get(contentHash);
    }

    public java.util.Set<Hash> getAllGlobalKeys() {
        return globals.keySet();
    }

    @TruffleBoundary
    public boolean hasGlobal(Hash contentHash) {
        return globals.containsKey(contentHash);
    }

    /** Test-inspection: register a top-level binding's value under its name. */
    @TruffleBoundary
    public void recordTestInspectionVar(String name, Object value) {
        testInspectionVars.put(name, value);
    }

    /** Test-inspection: snapshot of all top-level bindings keyed by name. */
    @TruffleBoundary
    public Map<String, Object> getTestInspectionVars() {
        return new HashMap<>(testInspectionVars);
    }

    // ========================
    // CallTarget cache
    // ========================

    @TruffleBoundary
    public CallTarget getCachedCallTarget(Hash contentHash) {
        return callTargetCache.get(contentHash);
    }

    @TruffleBoundary
    public void cacheCallTarget(Hash contentHash, CallTarget callTarget) {
        callTargetCache.put(contentHash, callTarget);
    }

    @TruffleBoundary
    public CallTarget getOrCacheCallTarget(Hash contentHash, java.util.function.Supplier<CallTarget> supplier) {
        return callTargetCache.computeIfAbsent(contentHash, k -> supplier.get());
    }

    // ========================
    // Local definitions
    // ========================

    // Handler / impl-record registrations keyed by content hash.
    // Populated by HandleNode when a `with` block installs a handler so
    // PerformNode / DynamicEffectCallNode can find it by hash.
    private final ConcurrentHashMap<com.mezze.language.typedast.Hash, Object> handlersByHash =
        new ConcurrentHashMap<>();

    // Name-keyed scope slots. Currently only used for the reserved name "scope"
    // (scoped-effect record) and local-variable fallback in
    // ReadLocalWithFallbackNode. Strict separation from handlersByHash means a
    // 64-hex local-variable name can never collide with an impl hash.
    private final ConcurrentHashMap<String, Object> scopeVars = new ConcurrentHashMap<>();

    @TruffleBoundary
    public void defineHandler(com.mezze.language.typedast.Hash hash, Object value) {
        handlersByHash.put(hash, value);
    }

    @TruffleBoundary
    public Object lookupHandler(com.mezze.language.typedast.Hash hash) {
        return handlersByHash.get(hash);
    }

    @TruffleBoundary
    public void defineScopeVar(String name, Object value) {
        scopeVars.put(name, value);
    }

    @TruffleBoundary
    public Object lookupScopeVar(String name) {
        return scopeVars.get(name);
    }

    // ========================
    // Ability Implementations
    // ========================

    // Content hash → impl record (for O(1) lookup by hash)
    private final ConcurrentHashMap<Hash, Object> abilityImplsByHash = new ConcurrentHashMap<>();

    /**
     * Register an ability implementation by content hash.
     */
    @TruffleBoundary
    public void registerAbilityImplByHash(Hash contentHash, Object implRecord) {
        abilityImplsByHash.put(contentHash, implRecord);
    }

    /**
     * Look up an ability implementation by content hash.
     * Returns null if not found.
     */
    @TruffleBoundary
    public Object getAbilityImplByHash(Hash contentHash) {
        return abilityImplsByHash.get(contentHash);
    }

    // ========================
    // Effect Performer Registry
    // ========================

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
