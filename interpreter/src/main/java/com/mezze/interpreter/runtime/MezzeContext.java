package com.mezze.interpreter.runtime;

import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.instrumentation.AllocationReporter;
import com.oracle.truffle.api.nodes.Node;

import java.util.HashMap;
import java.util.Map;
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

    // Parser and typechecker — per-context, created lazily on first use,
    // via reflection so the runtime jar can omit :parser and :typechecker
    // entirely when the host only ever feeds pre-lowered IR (the #220 wire
    // protocol). Source-eval hosts (tests, embedders) keep both jars on
    // the classpath, so the reflective lookup succeeds.
    //
    // Typed as Object to keep the field signature free of :parser /
    // :typechecker class refs — JVM class-load of MezzeContext is then
    // safe even when those jars are absent.
    private volatile Object parser;
    private volatile Object typeChecker;

    public MezzeContext(MezzeLanguage language, Env env) {
        this.language = language;
        this.env = env;
    }

    /**
     * Get the per-context parser (created lazily). Returns Object so the
     * field is free of :parser refs; callers cast back to
     * com.mezze.language.parser.MezzeParser. Throws a clear error if
     * :parser isn't on the classpath (slim-runtime jar deployments).
     */
    public Object getParser() {
        var p = parser;
        if (p == null) {
            synchronized (this) {
                p = parser;
                if (p == null) {
                    try {
                        var cls = Class.forName("com.mezze.language.parser.MezzeParser");
                        p = cls.getDeclaredConstructor().newInstance();
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(
                            "MezzeParser not on classpath — this runtime jar was built " +
                            "without :parser (wire-protocol-only deployment). Hosts that " +
                            "eval .mz source must add :parser to the classpath.", e);
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException("Failed to construct MezzeParser", e);
                    }
                    parser = p;
                }
            }
        }
        return p;
    }

    /** See getParser for the rationale; same reflective lookup for :typechecker. */
    public Object getTypeChecker() {
        var tc = typeChecker;
        if (tc == null) {
            synchronized (this) {
                tc = typeChecker;
                if (tc == null) {
                    try {
                        var cls = Class.forName("com.mezze.language.typechecker.TypeChecker");
                        tc = cls.getDeclaredConstructor().newInstance();
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException(
                            "TypeChecker not on classpath — this runtime jar was built " +
                            "without :typechecker (wire-protocol-only deployment). Hosts " +
                            "that eval .mz source must add :typechecker to the classpath.", e);
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException("Failed to construct TypeChecker", e);
                    }
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
