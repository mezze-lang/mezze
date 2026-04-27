package com.mezze.interpreter.runtime;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeUnit;
import com.mezze.language.truffleir.FunctionIR;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mezze language registration for Truffle - V2 OPTIMIZED.
 *
 * Fully functional language implementation with:
 * - Source code parsing via MezzeParser
 * - Type checking via TypeChecker
 * - TruffleIR compilation via PipelineRunner/TruffleCompiler
 *
 * Uses "mezze" as the language ID - V2 is the default interpreter.
 */
@TruffleLanguage.Registration(
    id = MezzeLanguage.ID,
    name = "Mezze",
    version = "2.0",
    defaultMimeType = MezzeLanguage.MIME_TYPE,
    characterMimeTypes = MezzeLanguage.MIME_TYPE,
    contextPolicy = TruffleLanguage.ContextPolicy.SHARED
)
public final class MezzeLanguage extends TruffleLanguage<MezzeContext> {

    public static final String ID = "mezze";
    public static final String MIME_TYPE = "application/x-mezze";

    // Store last execution result for test access
    private static final AtomicReference<Object> lastResultStore = new AtomicReference<>();

    // Store last execution context (variable bindings) for test access
    private static final AtomicReference<Map<String, Object>> lastContextStore = new AtomicReference<>();

    // Parser and typechecker live on MezzeContext (per-context isolation).
    // NOT static — GraalVM native-image cannot serialize TypeChecker into the image heap
    // because it triggers BuiltInAbilities/stdlib loading at class init time.

    // CallTarget cache — shared across all tests for the same Language instance.
    // Content-hash keys ensure identical functions reuse the same CallTarget,
    // preventing accumulation on the Engine across 5000+ test runs.
    private final java.util.concurrent.ConcurrentHashMap<String, CallTarget> stdlibCallTargetCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** Expose cache for FunctionDefinitionNode to store lambda CallTargets by content hash. */
    public java.util.concurrent.ConcurrentHashMap<String, CallTarget> getCachedStdlibCallTargetCache() {
        return stdlibCallTargetCache;
    }
    private volatile java.util.Map<String, String> stdlibHashToName = null;
    private volatile java.util.List<String[]> stdlibPerformerRecords = null;

    /** Register a user FunctionIR for lazy compilation (per-context). */
    public void registerFunctionIR(String hash, com.mezze.language.truffleir.FunctionIR ir) {
        getCurrentContext().registerUserFunctionIR(hash, ir);
    }

    /**
     * Get a cached CallTarget by hash — checks stdlib cache first, then compiles user IR on demand.
     */
    public CallTarget getCachedStdlibCallTarget(String hash) {
        // 1. Check shared stdlib cache
        CallTarget ct = stdlibCallTargetCache.get(hash);
        if (ct != null) return ct;

        // 2. Check per-context user functions (compile on demand)
        MezzeContext ctx;
        try { ctx = getCurrentContext(); } catch (Exception e) { ctx = null; }
        if (ctx != null) {
            ct = ctx.getUserCallTarget(hash);
            if (ct != null) return ct;
            var ir = ctx.getUserFunctionIR(hash);
            if (ir != null) {
                var compiler = new com.mezze.interpreter.compiler.TruffleCompiler(this);
                compiler.registerFunctions(new java.util.ArrayList<>(ctx.getAllUserFunctionIRs()));
                var rootNode = compiler.compile(ir);
                ct = rootNode.getCallTarget();
                ctx.cacheUserCallTarget(hash, ct);
                return ct;
            }
        }

        // 3. Compile from stdlib FunctionIR on demand
        var stdlibIR = com.mezze.language.typechecker.PrecompiledStdlib.INSTANCE.getPreloweredFunctions().get(hash);
        if (stdlibIR != null) {
            var compiler = new com.mezze.interpreter.compiler.TruffleCompiler(this);
            var rootNode = compiler.compile(stdlibIR);
            ct = rootNode.getCallTarget();
            stdlibCallTargetCache.put(hash, ct);
            return ct;
        }
        return null;
    }

    /**
     * Compile referenced stdlib functions into the shared cache.
     * Only compiles functions not already cached. Idempotent.
     */
    public void compileReferencedStdlib(
            com.mezze.interpreter.compiler.TruffleCompiler compiler,
            java.util.Set<String> referencedHashes,
            java.util.Set<String> userHashes) {
        var precompiled = com.mezze.language.typechecker.PrecompiledStdlib.INSTANCE;
        var allFunctions = precompiled.getPreloweredFunctions();

        // Build name map + performer records on first call
        if (stdlibHashToName == null) {
            var nameMap = new java.util.HashMap<String, String>();
            for (var entry : allFunctions.entrySet()) {
                var fn = entry.getValue();
                if (!fn.getName().equals("lambda") && !fn.getName().startsWith("__")) {
                    nameMap.put(entry.getKey(), fn.getName());
                }
            }
            stdlibHashToName = nameMap;
            stdlibPerformerRecords = precompiled.getPreloweredPerformerRecords();
        }

        // Compile only referenced stdlib functions not yet cached
        for (String hash : referencedHashes) {
            if (!userHashes.contains(hash) && !stdlibCallTargetCache.containsKey(hash)) {
                var ir = allFunctions.get(hash);
                if (ir != null) {
                    var rootNode = compiler.compile(ir);
                    stdlibCallTargetCache.put(hash, rootNode.getCallTarget());
                }
            }
        }
    }

    /** Get stdlib hash→name map (available after ensureStdlibCompiled). */
    public java.util.Map<String, String> getStdlibHashToName() {
        return stdlibHashToName != null ? stdlibHashToName : java.util.Collections.emptyMap();
    }

    /** Get stdlib performer records (available after ensureStdlibCompiled). */
    public java.util.List<String[]> getStdlibPerformerRecords() {
        return stdlibPerformerRecords != null ? stdlibPerformerRecords : java.util.Collections.emptyList();
    }

    // Cache for imported module CallTargets (non-entry, non-stdlib functions)
    private final java.util.concurrent.ConcurrentHashMap<String, CallTarget> importedCallTargetCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    public CallTarget getCachedCallTarget(String hash) {
        return importedCallTargetCache.get(hash);
    }

    public void cacheCallTarget(String hash, CallTarget ct) {
        importedCallTargetCache.put(hash, ct);
    }

    /**
     * Evict CallTargets for the given hashes from all Language-level caches.
     * Stdlib hashes never change in a JVM lifetime (content-addressed), so
     * they're typically absent — caller decides.
     */
    public void invalidateHashes(java.util.Collection<String> hashes) {
        for (String h : hashes) {
            importedCallTargetCache.remove(h);
            stdlibCallTargetCache.remove(h);
        }
    }

    /**
     * DevRunner sets this before each watch reload — parse() picks it up and
     * calls invalidateHashes() before compiling the fresh user FunctionIRs.
     * Thread-local because parse() runs on whatever thread Polyglot picks.
     */
    private static final ThreadLocal<java.util.Collection<String>> hashesToInvalidate = new ThreadLocal<>();

    public static void setHashesToInvalidate(java.util.Collection<String> hashes) {
        hashesToInvalidate.set(hashes);
    }

    // ThreadLocal for direct TruffleIR execution (bypasses parsing)
    private static final ThreadLocal<List<FunctionIR>> directExecutionFunctions = new ThreadLocal<>();
    private static final ThreadLocal<List<String[]>> directExecutionPerformers = new ThreadLocal<>();
    private static final ThreadLocal<Path> directExecutionRoot = new ThreadLocal<>();
    private static final ThreadLocal<String> directExecutionEntryName = new ThreadLocal<>();

    @Override
    protected MezzeContext createContext(Env env) {
        return new MezzeContext(this, env);
    }

    @Override
    protected void initializeMultipleContexts() {
        // Called by Truffle when SHARED policy is active.
        // Our caches are already thread-safe ConcurrentHashMaps — nothing extra needed.
    }

    @Override
    protected CallTarget parse(ParsingRequest request) throws Exception {
        Source source = request.getSource();
        String sourceCode = source.getCharacters().toString();

        // Watch-mode targeted invalidation: DevRunner has computed the set of
        // hashes whose content changed since last reload. Evict only those.
        java.util.Collection<String> changed = hashesToInvalidate.get();
        if (changed != null) {
            hashesToInvalidate.remove();
            invalidateHashes(changed);
        }

        // Clear per-context user function state — each test gets fresh user functions.
        // Stdlib cache (Language-level) is NOT cleared — it persists across all Contexts.
        var ctx = getCurrentContext();
        if (ctx == null) {
            throw new RuntimeException("MezzeLanguage.parse() called without a context");
        }
        ctx.clearUserState();

        // Check for direct TruffleIR execution (from tests or CLI)
        List<FunctionIR> directFunctions = directExecutionFunctions.get();
        if (directFunctions != null) {
            directExecutionFunctions.remove();
            List<String[]> performers = directExecutionPerformers.get();
            directExecutionPerformers.remove();
            Path projectRoot = directExecutionRoot.get();
            directExecutionRoot.remove();
            String entryName = directExecutionEntryName.get();
            directExecutionEntryName.remove();
            if (projectRoot == null) {
                projectRoot = Paths.get(System.getProperty("mezze.project.root", "."));
            }

            // All modules are already included in the FunctionIR list — no import extraction needed
            return com.mezze.interpreter.compiler.PipelineRunner.compileFromIR(
                this, directFunctions, Collections.emptyList(), projectRoot,
                performers != null ? performers : Collections.emptyList(),
                entryName
            );
        }

        // Determine project root
        Path projectRoot;
        String projectRootProp = System.getProperty("mezze.project.root");
        if (projectRootProp != null) {
            projectRoot = Paths.get(projectRootProp);
        } else if (source.getPath() != null) {
            Path sourcePath = Paths.get(source.getPath());
            projectRoot = sourcePath.getParent() != null ? sourcePath.getParent() : sourcePath;
        } else {
            projectRoot = Paths.get(".");
        }

        // Parse and typecheck via context-owned instances (proper isolation, native-image safe)
        var parsedModule = ctx.getParser().parseParsedModule(sourceCode, source.getPath() != null ? source.getPath() : "<unknown>");
        var typeCheckResult = ctx.getTypeChecker().typeCheck(parsedModule, projectRoot, true, null);

        var loweredFunctions = typeCheckResult.getLoweredFunctions();
        if (loweredFunctions != null && !loweredFunctions.isEmpty()) {
            var performers = typeCheckResult.getPerformerRecords();
            return com.mezze.interpreter.compiler.PipelineRunner.compileFromIR(
                this, loweredFunctions, Collections.emptyList(), projectRoot,
                performers != null ? performers : Collections.emptyList()
            );
        }

        throw new RuntimeException("TruffleIR not produced by typechecker. loweredFunctions is " +
            (loweredFunctions == null ? "null" : "empty (" + loweredFunctions.size() + ")") +
            ". typedModule is " + (typeCheckResult.getTypedModule() == null ? "null" : "present"));
    }

    /**
     * Create a ResultCapturingNode that stores the execution result.
     * Used by PipelineRunner.
     */
    public static MezzeNode createResultCapturingNode(MezzeNode bodyNode) {
        return new ResultCapturingNode(bodyNode);
    }

    /**
     * Node that captures the result for test access.
     */
    private static final class ResultCapturingNode extends MezzeNode {
        @Child private MezzeNode bodyNode;

        ResultCapturingNode(MezzeNode bodyNode) {
            this.bodyNode = bodyNode;
        }

        @Child private com.oracle.truffle.api.nodes.IndirectCallNode forceCallNode;

        @Override
        public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
            Object result = bodyNode.execute(frame);
            // Force suspended computations — effects should have been handled
            // by the time we reach the top-level result
            if (result instanceof com.mezze.interpreter.values.MezzeSuspended suspended) {
                result = forceSuspended(suspended);
            }
            lastResultStore.set(result);
            captureGlobalContext();
            return MezzeUnit.INSTANCE;
        }

        @CompilerDirectives.TruffleBoundary
        private Object forceSuspended(com.mezze.interpreter.values.MezzeSuspended suspended) {
            try {
                MezzeContext context = MezzeContext.get(this);
                if (forceCallNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    forceCallNode = insert(com.oracle.truffle.api.nodes.IndirectCallNode.create());
                }
                // Try to execute with handlers from the handler stack
                java.util.Map<String, Object> handlers = new java.util.HashMap<>();
                // Collect all available handlers from context
                for (String effect : suspended.getUnhandledEffects()) {
                    Object handler = context.getHandlerFor(effect);
                    if (handler != null) {
                        handlers.put(effect, handler);
                    }
                }
                if (!handlers.isEmpty()) {
                    Object forced = suspended.executeWith(handlers, context, forceCallNode);
                    if (!(forced instanceof com.mezze.interpreter.values.MezzeSuspended)) {
                        return forced;
                    }
                }
            } catch (Exception e) {
                // Force failed — return suspended as-is
            }
            return suspended;
        }

        @CompilerDirectives.TruffleBoundary
        private void captureGlobalContext() {
            // Capture all global variables registered by WriteLocalWithContextNode.
            // This enables tests using runAndGetContext() to read variable values.
            try {
                MezzeContext context = MezzeContext.get(this);
                Map<String, Object> globals = context.getAllGlobals();
                if (globals != null && !globals.isEmpty()) {
                    lastContextStore.set(globals);
                }
            } catch (Exception e) {
                // No context available
            }
        }
    }

    /**
     * Get the language instance from a node.
     */
    public static MezzeLanguage get(Node node) {
        return REFERENCE.get(node);
    }

    /**
     * Get the current context for the current thread.
     */
    public MezzeContext getCurrentContext() {
        return CONTEXT_REFERENCE.get(null);
    }

    /**
     * Set pre-lowered TruffleIR functions for direct execution.
     * Used by CLI and test helpers to bypass parsing — all modules are
     * already included in the FunctionIR list via monomorphization.
     */
    public static void setFunctionsForDirectExecution(List<FunctionIR> functions, Path projectRoot) {
        directExecutionFunctions.set(functions);
        directExecutionPerformers.set(null);
        directExecutionRoot.set(projectRoot);
    }

    /**
     * Set pre-lowered TruffleIR functions AND performer records for direct execution.
     */
    public static void setFunctionsForDirectExecution(List<FunctionIR> functions, List<String[]> performers, Path projectRoot) {
        directExecutionFunctions.set(functions);
        directExecutionPerformers.set(performers);
        directExecutionRoot.set(projectRoot);
        directExecutionEntryName.set(null);
    }

    /**
     * Set pre-lowered TruffleIR functions with explicit entry function name.
     * Used by mez dev — the entry function (e.g., "main") is called automatically.
     */
    public static void setFunctionsForDirectExecution(List<FunctionIR> functions, List<String[]> performers, Path projectRoot, String entryName) {
        directExecutionFunctions.set(functions);
        directExecutionPerformers.set(performers);
        directExecutionRoot.set(projectRoot);
        directExecutionEntryName.set(entryName);
    }

    /**
     * Get the last execution result.
     * Used by test helpers.
     */
    public static Object getLastExecutionResult() {
        return lastResultStore.get();
    }

    /**
     * Clear the last execution result and any accumulated state.
     */
    public static void clearLastResult() {
        lastResultStore.set(null);
        // Clear delimiter stack to ensure clean state between tests
        com.mezze.interpreter.nodes.continuation.DelimiterStack.clear();
        // Clear state machine context to prevent stale continuation state between tests
        com.mezze.interpreter.nodes.statemachine.StateMachineContext.clearCurrentContext();
    }

    /**
     * Get the last execution context (variable bindings).
     * Used by test helpers to inspect variable values after execution.
     */
    public static Map<String, Object> getLastExecutionContext() {
        Map<String, Object> context = lastContextStore.get();
        return context != null ? context : Collections.emptyMap();
    }

    /**
     * Clear the last execution context.
     */
    public static void clearLastContext() {
        lastContextStore.set(null);
    }

    /**
     * Enable multi-threaded access for concurrency support.
     * Required for Loom virtual threads and parallel execution.
     */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        // Allow all threads to access this language
        return true;
    }

    private static final LanguageReference<MezzeLanguage> REFERENCE =
        LanguageReference.create(MezzeLanguage.class);

    private static final ContextReference<MezzeContext> CONTEXT_REFERENCE =
        ContextReference.create(MezzeLanguage.class);
}
