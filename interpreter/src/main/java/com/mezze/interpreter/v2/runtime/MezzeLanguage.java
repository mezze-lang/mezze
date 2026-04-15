package com.mezze.interpreter.v2.runtime;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeUnit;
import com.mezze.language.parser.MezzeParser;
import com.mezze.language.truffleir.FunctionIR;
import com.mezze.language.typechecker.TypeChecker;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mezze language registration for Truffle - V2 OPTIMIZED.
 *
 * Fully functional language implementation with:
 * - Source code parsing via MezzeParser
 * - Type checking via TypeChecker
 * - TruffleIR compilation via V2PipelineRunner/TruffleCompiler
 *
 * Uses "mezze" as the language ID - V2 is the default interpreter.
 */
@TruffleLanguage.Registration(
    id = MezzeLanguage.ID,
    name = "Mezze",
    version = "2.0",
    defaultMimeType = MezzeLanguage.MIME_TYPE,
    characterMimeTypes = MezzeLanguage.MIME_TYPE
)
public final class MezzeLanguage extends TruffleLanguage<MezzeContext> {

    public static final String ID = "mezze";
    public static final String MIME_TYPE = "application/x-mezze";

    // Store last execution result for test access
    private static final AtomicReference<Object> lastResultStore = new AtomicReference<>();

    // Store last execution context (variable bindings) for test access
    private static final AtomicReference<Map<String, Object>> lastContextStore = new AtomicReference<>();

    // Shared parser and typechecker — created once, reused across all parse() calls.
    // Both are stateless (no per-compilation mutable state in the instances themselves).
    private static final MezzeParser sharedParser = new MezzeParser();
    private static final TypeChecker sharedTypeChecker = new TypeChecker();

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
    private volatile boolean stdlibCompiled = false;

    // User function IR registry — FunctionIRs registered per-test, compiled on demand.
    // Unlike stdlibCallTargetCache (which persists across tests), this is cleared per test.
    private final java.util.concurrent.ConcurrentHashMap<String, com.mezze.language.truffleir.FunctionIR>
        userFunctionIRRegistry = new java.util.concurrent.ConcurrentHashMap<>();

    /** Register a user FunctionIR for lazy compilation. */
    public void registerFunctionIR(String hash, com.mezze.language.truffleir.FunctionIR ir) {
        userFunctionIRRegistry.put(hash, ir);
    }

    /** Clear user function registry between tests. */
    public void clearUserFunctionRegistry() {
        userFunctionIRRegistry.clear();
    }

    // User function CallTarget cache — compiled on demand, cleared between tests.
    // Separate from stdlibCallTargetCache to prevent accumulation across tests.
    private final java.util.concurrent.ConcurrentHashMap<String, CallTarget> userCallTargetCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Get a cached CallTarget by hash — checks stdlib cache first, then compiles user IR on demand.
     */
    public CallTarget getCachedStdlibCallTarget(String hash) {
        CallTarget ct = stdlibCallTargetCache.get(hash);
        if (ct != null) return ct;
        ct = userCallTargetCache.get(hash);
        if (ct != null) return ct;
        // Try user function IR — compile on demand with full function registry
        var ir = userFunctionIRRegistry.get(hash);
        if (ir != null) {
            var compiler = new com.mezze.interpreter.v2.compiler.TruffleCompiler(this);
            compiler.registerFunctions(new java.util.ArrayList<>(userFunctionIRRegistry.values()));
            var rootNode = compiler.compile(ir);
            ct = rootNode.getCallTarget();
            userCallTargetCache.put(hash, ct);
            return ct;
        }
        // Try stdlib FunctionIR — may be available if preloweredFunctions not yet cleared
        var precompiled = com.mezze.language.typechecker.PrecompiledStdlib.INSTANCE;
        var stdlibIR = precompiled.getPreloweredFunctions().get(hash);
        if (stdlibIR != null) {
            var compiler = new com.mezze.interpreter.v2.compiler.TruffleCompiler(this);
            compiler.registerFunctions(new java.util.ArrayList<>(precompiled.getPreloweredFunctions().values()));
            var rootNode = compiler.compile(stdlibIR);
            ct = rootNode.getCallTarget();
            stdlibCallTargetCache.put(hash, ct);
            return ct;
        }
        return null;
    }

    /**
     * Compile referenced stdlib functions on demand (lazy, per-Language instance).
     * Each Language instance (=each Context) compiles only the stdlib functions
     * needed by the current test. ~35 functions per test instead of 1044.
     */
    public void compileReferencedStdlib(
            com.mezze.interpreter.v2.compiler.TruffleCompiler compiler,
            java.util.Set<String> referencedHashes,
            java.util.Set<String> userHashes) {
        var precompiled = com.mezze.language.typechecker.PrecompiledStdlib.INSTANCE;
        for (String hash : referencedHashes) {
            if (!userHashes.contains(hash) && !stdlibCallTargetCache.containsKey(hash)) {
                var fn = precompiled.getPreloweredFunctions().get(hash);
                if (fn != null) {
                    var rootNode = compiler.compile(fn);
                    stdlibCallTargetCache.put(hash, rootNode.getCallTarget());
                }
            }
        }
        // Build name map + performer records if not done
        if (stdlibHashToName == null) {
            var nameMap = new java.util.HashMap<String, String>();
            for (var entry : precompiled.getPreloweredFunctions().entrySet()) {
                var fn = entry.getValue();
                if (!fn.getName().equals("lambda") && !fn.getName().startsWith("__")) {
                    nameMap.put(entry.getKey(), fn.getName());
                }
            }
            stdlibHashToName = nameMap;
            stdlibPerformerRecords = precompiled.getPreloweredPerformerRecords();
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

    // ThreadLocal for direct TruffleIR execution (bypasses parsing)
    private static final ThreadLocal<List<FunctionIR>> directExecutionFunctions = new ThreadLocal<>();
    private static final ThreadLocal<List<String[]>> directExecutionPerformers = new ThreadLocal<>();
    private static final ThreadLocal<Path> directExecutionRoot = new ThreadLocal<>();

    @Override
    protected MezzeContext createContext(Env env) {
        MezzeContext context = new MezzeContext(this, env);
        // Trigger background warmup for previously compiled functions
        triggerWarmupHints(context);
        return context;
    }

    /**
     * Use CompiledNodeCache warmup hints to prioritize frequently-used functions.
     * This checks disk cache for previously compiled hashes and preloads metadata.
     */
    @CompilerDirectives.TruffleBoundary
    private void triggerWarmupHints(MezzeContext context) {
        try {
            Set<String> previouslyCompiled = context.getCompiledNodeCache().getPreviouslyCompiledHashes();
            // Store hints in context for future use during compilation
            // The actual CallTargets can't be restored from disk, but we can
            // prioritize these for eager compilation when they're encountered
            for (String hash : previouslyCompiled) {
                context.markForEagerCompilation(hash);
            }
        } catch (Exception e) {
            // Warmup is best-effort, don't fail on errors
        }
    }

    @Override
    protected CallTarget parse(ParsingRequest request) throws Exception {
        Source source = request.getSource();
        String sourceCode = source.getCharacters().toString();

        // Clear previous test's user function state — each test gets fresh functions.
        userFunctionIRRegistry.clear();
        userCallTargetCache.clear();

        // Check for direct TruffleIR execution (from tests or CLI)
        List<FunctionIR> directFunctions = directExecutionFunctions.get();
        if (directFunctions != null) {
            directExecutionFunctions.remove();
            List<String[]> performers = directExecutionPerformers.get();
            directExecutionPerformers.remove();
            Path projectRoot = directExecutionRoot.get();
            directExecutionRoot.remove();
            if (projectRoot == null) {
                projectRoot = Paths.get(System.getProperty("mezze.project.root", "."));
            }

            // All modules are already included in the FunctionIR list — no import extraction needed
            return com.mezze.interpreter.v2.compiler.V2PipelineRunner.compileFromIR(
                this, directFunctions, Collections.emptyList(), projectRoot,
                performers != null ? performers : Collections.emptyList()
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

        // Parse to v2 ParsedModule and typecheck via v2 pipeline
        var parsedModule = sharedParser.parseParsedModule(sourceCode, source.getPath() != null ? source.getPath() : "<unknown>");
        var typeCheckResult = sharedTypeChecker.typeCheck(parsedModule, projectRoot, true, null);

        var loweredFunctions = typeCheckResult.getLoweredFunctions();
        if (loweredFunctions != null && !loweredFunctions.isEmpty()) {
            var performers = typeCheckResult.getPerformerRecords();
            return com.mezze.interpreter.v2.compiler.V2PipelineRunner.compileFromIR(
                this, loweredFunctions, Collections.emptyList(), projectRoot,
                performers != null ? performers : Collections.emptyList()
            );
        }

        throw new RuntimeException("TruffleIR not produced by typechecker. loweredFunctions is " +
            (loweredFunctions == null ? "null" : "empty (" + loweredFunctions.size() + ")") +
            ". typedModule is " + (typeCheckResult.getTypedModule() == null ? "null" : "present"));
    }

    /**
     * V2 pipeline: compile source through all 5 stages to Truffle nodes.
     *
     * source → ParsedModule → TypedModule → MonoModule → ResolvedModule → TruffleIR → Truffle nodes
     *
     * This is the new compilation path that uses content-hash addressing throughout.
     * Enable with system property: -Dmezze.pipeline=v2
     *
     * Delegates to Kotlin V2PipelineRunner to avoid inline class interop issues.
     */
    /**
     * Compile from pre-lowered TruffleIR (FunctionIR list).
     * This is the new path: typechecker produces TruffleIR directly.
     * All modules are already included in the FunctionIR list — no import extraction needed.
     */
    private CallTarget compileFunctionIR(
            List<FunctionIR> functions,
            Path projectRoot) {
        // All dependency modules are already monomorphized and included in FunctionIR list
        return com.mezze.interpreter.v2.compiler.V2PipelineRunner.compileFromIR(
            this, functions, Collections.emptyList(), projectRoot
        );
    }

    /**
     * Create a ResultCapturingNode that stores the execution result.
     * Used by V2PipelineRunner.
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

        @Override
        public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
            Object result = bodyNode.execute(frame);
            lastResultStore.set(result);
            captureGlobalContext();
            return MezzeUnit.INSTANCE;
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
        com.mezze.interpreter.v2.nodes.continuation.DelimiterStack.clear();
        // Clear state machine context to prevent stale continuation state between tests
        com.mezze.interpreter.v2.nodes.statemachine.StateMachineContext.clearCurrentContext();
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
