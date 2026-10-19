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

    // ── Things to shut down when this context goes away ─────────────────
    //
    // A native that starts something which OUTLIVES the call — a listening
    // socket, a pool of threads parked on a queue — has to give it back, or it
    // keeps running after the context that asked for it is gone. `HttpServer`
    // hands the guest a `stop`, and a guest that never calls it (a crash, a
    // `mez dev` interrupted, a test that forgot) left a bound port and a pool of
    // parked workers behind.
    //
    // Registered in start order and closed in reverse, because a later resource
    // may be built on an earlier one.
    private final java.util.Deque<AutoCloseable> onDispose = new java.util.concurrent.ConcurrentLinkedDeque<>();

    /** Register something to close when this context is disposed. */
    public void registerForDispose(AutoCloseable resource) {
        onDispose.addFirst(resource);
    }

    // ── Per-context state for natives ───────────────────────────────────
    //
    // A native is a static method, so its state used to be static too — and the
    // interpreter runs many Contexts on one Engine, so that state crossed them.
    // An in-memory filesystem shared by every program, HTTP mock responses from
    // one test answering another's request, a draw log that only ever grew.
    // Worse than the growth is the ISOLATION: two programs that never heard of
    // each other saw each other's writes.
    //
    // This gives a native one state object per context, created on first use. If
    // that object is AutoCloseable it is registered for dispose as well, so a
    // native holding something live — an inner polyglot context, a socket — hands
    // it back without a second registration step to forget.
    private final ConcurrentHashMap<Class<?>, Object> nativeState = new ConcurrentHashMap<>();

    /**
     * Deliberately NOT `computeIfAbsent`: [create] runs OUTSIDE the map, because
     * a state object's own constructor may well reach back for its state —
     * MemoryFileSystem's does, to seed the root directory — and ConcurrentHashMap
     * answers a mapping function that touches the same map with "Recursive
     * update". Losing the race is cheap: the loser's object is simply dropped,
     * never registered, never seen.
     */
    /**
     * CALL THIS ONLY FROM A `@TruffleBoundary`. It does a context lookup and a
     * map get, and `ConcurrentHashMap.get` / `putIfAbsent` are on SVM's
     * runtime-compilation blocklist — so an accessor reachable from a partially
     * evaluated path makes every user's `mez build --native` abort with
     * "Blocklisted methods are reachable for runtime compilation", naming the
     * accessor. That is how it was found: a native build that had passed an hour
     * earlier came back with 300-odd violations, all of them downstream of four
     * unannotated accessors added the same day.
     *
     * The boundary costs nothing in practice. Per-context state is read on the
     * cold side of a native — opening a file, stashing a suspended task — never
     * in a loop Truffle is specialising.
     */
    @SuppressWarnings("unchecked")
    public <T> T nativeState(Class<T> key, java.util.function.Supplier<T> create) {
        Object existing = nativeState.get(key);
        if (existing != null) return (T) existing;
        T made = create.get();
        Object winner = nativeState.putIfAbsent(key, made);
        if (winner != null) return (T) winner;
        if (made instanceof AutoCloseable closeable) {
            registerForDispose(closeable);
        }
        return made;
    }

    /**
     * Close everything registered, newest first, then drop the per-context
     * state itself. A failure to close one does not stop the rest — a
     * half-torn-down context is worse than a noisy one — but it is reported,
     * because silence here is how a leak survives.
     *
     * The `nativeState` clear is the second half of the job and was missing.
     * Closing an AutoCloseable state object releases what it HOLDS; it does
     * not drop the object, and most state objects are not AutoCloseable at
     * all because they hold nothing but memory. That memory then outlived
     * dispose: every entry stayed reachable from this field until the
     * MezzeContext itself was collected, so an in-memory filesystem, a mock
     * response table, a draw log and a cursor map all survived the close of
     * the context that made them. For a one-shot program that is invisible —
     * the context goes and the heap goes with it — but an embedder or pool
     * holding closed Context objects kept every byte, and a long-running
     * context never freed anything.
     *
     * Order is load-bearing: close first (PolyglotNatives' inner contexts are
     * shut that way), drop second. Clearing first would strand a live
     * resource with nothing left pointing at it.
     */
    @TruffleBoundary
    public void closeRegisteredResources() {
        AutoCloseable resource;
        while ((resource = onDispose.pollFirst()) != null) {
            try {
                resource.close();
            } catch (Exception e) {
                System.err.println("[mezze] failed to release " + resource.getClass().getSimpleName()
                    + " on context dispose: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
            }
        }
        nativeState.clear();
    }

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
    // Populated by HashModuleLoaderNode at module-load time so BDSL
    // perform dispatch can find them via YieldDispatch.
    private final ConcurrentHashMap<com.mezze.language.typedast.Hash, Object> handlersByHash =
        new ConcurrentHashMap<>();

    // Scope variable storage has moved to HandlerChainRegistry.EffectState
    // (per-thread, zero-cost field read instead of ConcurrentHashMap lookup).
    // See HandlerChainRegistry.installScopeVar / lookupScopeVar / restoreScopeVar.

    @TruffleBoundary
    public void defineHandler(com.mezze.language.typedast.Hash hash, Object value) {
        handlersByHash.put(hash, value);
    }

    /** Raw lookup — returns the entry as-is without inflating lazy PerformerInfo. */
    @TruffleBoundary
    public Object lookupHandlerRaw(com.mezze.language.typedast.Hash hash) {
        return handlersByHash.get(hash);
    }

    @TruffleBoundary
    public Object lookupHandler(com.mezze.language.typedast.Hash hash) {
        Object entry = handlersByHash.get(hash);
        // Lazy performer record inflation: if the entry is a PerformerInfo
        // (stored by HashModuleLoaderNode during module load), build the
        // record on first access. This defers ~55ms of BDSL compilation
        // per performer to the first `perform Handler in ...` call.
        if (entry instanceof com.mezze.interpreter.nodes.HashModuleLoaderNode.PerformerInfo info) {
            var record = inflatePerformerRecord(info);
            if (record != null) {
                handlersByHash.put(hash, record);
                // Also register under effectHash if present
                if (info.effectHash != null) {
                    handlersByHash.put(info.effectHash, record);
                }
                return record;
            }
            return null;
        }
        return entry;
    }

    /**
     * Build a MezzeRecord from a PerformerInfo on demand.
     * Mirrors HashModuleLoaderNode.constructPerformerRecord but runs here
     * so performer record construction is lazy (deferred to first lookup).
     */
    @TruffleBoundary
    private com.mezze.interpreter.values.MezzeRecord inflatePerformerRecord(
            com.mezze.interpreter.nodes.HashModuleLoaderNode.PerformerInfo info) {
        var lang = this.language;
        int opCount = info.ops.length;
        String[] opNames = new String[opCount];
        Object[] opValues = new Object[opCount];
        for (int i = 0; i < opCount; i++) {
            var opHash = info.ops[i].hash;
            opNames[i] = info.ops[i].name;
            Object opFunc = lookupGlobal(opHash);
            if (opFunc == null) {
                var ct = lang.getCachedStdlibCallTarget(opHash);
                if (ct != null) {
                    opFunc = com.mezze.interpreter.values.MezzeFunction.create(ct, opHash.getHex());
                }
            }
            if (opFunc == null) return null;
            opValues[i] = opFunc;
        }
        var locations = new com.mezze.interpreter.values.MezzeRecord.FieldLocation[opCount];
        for (int i = 0; i < opCount; i++) {
            locations[i] = new com.mezze.interpreter.values.MezzeRecord.FieldLocation(
                com.mezze.interpreter.values.MezzeRecord.FieldLocation.StorageType.OBJECT, i);
        }
        var shape = new com.mezze.interpreter.values.MezzeRecord.Shape(opNames, locations);
        return com.mezze.interpreter.values.MezzeRecord.withAllObjects(shape, opValues);
    }

    /**
     * @deprecated Use {@link com.mezze.interpreter.nodes.continuation.HandlerChainRegistry#installScopeVar} instead.
     */
    @Deprecated
    @TruffleBoundary
    public void defineScopeVar(String name, Object value) {
        com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.restoreScopeVar(value);
    }

    /**
     * @deprecated Use {@link com.mezze.interpreter.nodes.continuation.HandlerChainRegistry#lookupScopeVar} instead.
     */
    @Deprecated
    @TruffleBoundary
    public Object lookupScopeVar(String name) {
        return com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.lookupScopeVar();
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
