package com.mezze.interpreter.v2.runtime;

import com.mezze.interpreter.v2.converter.ASTToTruffleConverterV2;
import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.nodes.MezzeRootNode;
import com.mezze.interpreter.v2.values.MezzeUnit;
import com.mezze.language.ast.LetBinding;
import com.mezze.language.ast.ModulePath;
import com.mezze.language.ast.Program;
import com.mezze.language.ast.ProgramItem;
import com.mezze.language.ast.PubUseDeclaration;
import com.mezze.language.ast.SelectivePath;
import com.mezze.language.ast.UseDeclaration;
import com.mezze.language.ast.UsePath;
import com.mezze.language.parser.MezzeParser;
import com.mezze.language.truffleir.FunctionIR;
import com.mezze.language.typechecker.TypeChecker;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
 * - Optimized node tree via ASTToTruffleConverterV2
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

    // Store variable slots for context capture
    private static final ThreadLocal<Map<String, Integer>> lastVariableSlots = new ThreadLocal<>();

    // Shared parser and typechecker — created once, reused across all parse() calls.
    // Both are stateless (no per-compilation mutable state in the instances themselves).
    private static final MezzeParser sharedParser = new MezzeParser();
    private static final TypeChecker sharedTypeChecker = new TypeChecker();

    // ThreadLocal for direct TruffleIR execution (bypasses parsing)
    private static final ThreadLocal<List<FunctionIR>> directExecutionFunctions = new ThreadLocal<>();
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

        // Check for direct TruffleIR execution (from tests or CLI)
        List<FunctionIR> directFunctions = directExecutionFunctions.get();
        if (directFunctions != null) {
            directExecutionFunctions.remove();
            Path projectRoot = directExecutionRoot.get();
            directExecutionRoot.remove();
            if (projectRoot == null) {
                projectRoot = Paths.get(System.getProperty("mezze.project.root", "."));
            }

            // All modules are already included in the FunctionIR list — no import extraction needed
            return com.mezze.interpreter.v2.compiler.V2PipelineRunner.compileFromIR(
                this, directFunctions, Collections.emptyList(), projectRoot
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

        // Parse via v1 parser (entry point), then typecheck which runs the v2 pipeline
        // internally and produces TruffleIR (loweredFunctions).
        // Note: The v1 parse() is only used as entry point — the typechecker's v2 pipeline
        // (ParsedModule → TypedModule → Mono → Resolve → Lower) does the actual work.
        // Full migration to V2PipelineRunner.tryCompile() blocked on closure factory issue.
        Program ast = sharedParser.parse(sourceCode, true, null);
        var typeCheckResult = sharedTypeChecker.typeCheck(ast, projectRoot, true, null);

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
     * Compile a typed program to a CallTarget.
     * TODO: Remove this method once all callers use TruffleIR path (compileFromIR).
     */
    // Prelude modules loaded from stdlib/std/_prelude.mz — no hardcoded list here

    private CallTarget compileProgram(Program program, Path projectRoot) {
        // Extract imported modules from the program
        List<String> importedModules = extractImportedModules(program);

        // Convert to optimized V2 nodes
        ASTToTruffleConverterV2 converter = new ASTToTruffleConverterV2(null, null, null, null);
        MezzeNode bodyNode = converter.convertProgram(program);
        FrameDescriptor frameDescriptor = converter.getFrameDescriptor();
        Map<String, Integer> variableSlots = converter.getVariableSlots();

        // Store variable slots for context capture after execution
        lastVariableSlots.set(variableSlots);

        // Wrap with module loader and context-capturing result node
        MezzeNode wrappedNode = new ModuleLoadingNode(
            new ContextCapturingNode(bodyNode, variableSlots),
            importedModules,
            projectRoot
        );

        // Create root node
        MezzeRootNode rootNode = new MezzeRootNode(
            this,
            frameDescriptor,
            wrappedNode,
            "main",
            0,
            0,
            new int[0]
        );

        return rootNode.getCallTarget();
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

    private CallTarget compileProgramV2(String sourceCode, Path projectRoot) {
        return com.mezze.interpreter.v2.compiler.V2PipelineRunner.tryCompile(
            this, sourceCode, projectRoot, null
        );
    }

    /**
     * Extract module paths from UseDeclarations in the program.
     */
    private List<String> extractImportedModules(Program program) {
        Set<String> modules = new HashSet<>();
        for (ProgramItem item : program.getItems()) {
            if (item instanceof UseDeclaration useDecl) {
                String modulePath = extractModulePath(useDecl.getPath());
                if (modulePath != null) {
                    modules.add(modulePath);
                }
            } else if (item instanceof PubUseDeclaration pubUseDecl) {
                String modulePath = extractModulePath(pubUseDecl.getPath());
                if (modulePath != null) {
                    modules.add(modulePath);
                }
            }
        }
        return new ArrayList<>(modules);
    }

    /**
     * Extract module path string from UsePath.
     */
    private String extractModulePath(UsePath path) {
        if (path instanceof ModulePath modulePath) {
            return String.join("::", modulePath.getSegments());
        } else if (path instanceof SelectivePath selectivePath) {
            return String.join("::", selectivePath.getModule());
        }
        return null;
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
     * Node that captures both result and execution context (variable bindings) for test access.
     * This allows tests to inspect variable values after execution.
     */
    private static final class ContextCapturingNode extends MezzeNode {
        @Child private MezzeNode bodyNode;
        private final Map<String, Integer> variableSlots;

        ContextCapturingNode(MezzeNode bodyNode, Map<String, Integer> variableSlots) {
            this.bodyNode = bodyNode;
            this.variableSlots = variableSlots;
        }

        @Override
        public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
            Object result = bodyNode.execute(frame);
            lastResultStore.set(result);

            // Materialize the frame so we can access it from the boundary method
            com.oracle.truffle.api.frame.MaterializedFrame materializedFrame = frame.materialize();
            captureContext(materializedFrame, variableSlots);

            // Return Unit to polyglot to avoid interop issues with raw Java arrays.
            return MezzeUnit.INSTANCE;
        }

        @CompilerDirectives.TruffleBoundary
        private static void captureContext(com.oracle.truffle.api.frame.MaterializedFrame frame, Map<String, Integer> variableSlots) {
            // Capture all variable bindings from the frame
            Map<String, Object> context = new java.util.HashMap<>();
            for (Map.Entry<String, Integer> entry : variableSlots.entrySet()) {
                String name = entry.getKey();
                int slot = entry.getValue();
                try {
                    // Try to read the value based on its actual slot kind
                    Object value = readSlotValue(frame, slot);
                    if (value != null) {
                        context.put(name, value);
                    }
                } catch (Exception e) {
                    // Slot might be uninitialized, skip it
                }
            }
            lastContextStore.set(context);
        }

        /**
         * Read a frame slot value using getValue() which handles all kinds.
         */
        private static Object readSlotValue(com.oracle.truffle.api.frame.MaterializedFrame frame, int slot) {
            // getValue() returns the value regardless of type (Object, Long, Double, Boolean)
            return frame.getValue(slot);
        }
    }

    /**
     * Node that loads modules on first execution.
     * Loads and executes typed modules from the TypeChecker cache.
     */
    /**
     * Create a ModuleLoadingNode for use by V2PipelineRunner.
     */
    public static MezzeNode createModuleLoadingNode(MezzeNode bodyNode, java.util.List<String> importedModules, Path projectRoot) {
        return new ModuleLoadingNode(bodyNode, importedModules, projectRoot);
    }

    private static final class ModuleLoadingNode extends MezzeNode {
        @Child private MezzeNode bodyNode;

        private final List<String> importedModules;
        private final Path projectRoot;

        @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
        private boolean modulesLoaded = false;

        private final Set<String> loadedModuleSet = new HashSet<>();

        ModuleLoadingNode(MezzeNode bodyNode, List<String> importedModules, Path projectRoot) {
            this.bodyNode = bodyNode;
            this.importedModules = importedModules;
            this.projectRoot = projectRoot;
        }

        @Override
        public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
            // One-time module loading
            if (!modulesLoaded) {
                loadAllModules();
            }
            return bodyNode.execute(frame);
        }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        private void loadAllModules() {
            MezzeContext context = MezzeContext.get(this);

            for (String modulePath : importedModules) {
                loadModuleRecursively(modulePath, context);
            }
            modulesLoaded = true;
        }

        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        private void loadModuleRecursively(String modulePath, MezzeContext context) {
            // Skip if already loaded
            if (loadedModuleSet.contains(modulePath)) {
                return;
            }

            try {
                // Get typed module from TypeChecker cache, or load on demand
                Program typedModule = TypeChecker.Companion.getTypedModule(modulePath);
                if (typedModule == null) {
                    // Module not in cache — load and typecheck on demand
                    typedModule = loadAndTypeCheckModule(modulePath);
                    if (typedModule == null) {
                        throw new RuntimeException("Module not found: " + modulePath);
                    }
                }

                // Extract and load dependencies first
                for (ProgramItem item : typedModule.getItems()) {
                    if (item instanceof UseDeclaration useDecl) {
                        String depPath = extractModulePathStatic(useDecl.getPath());
                        if (depPath != null) {
                            loadModuleRecursively(depPath, context);
                        }
                    } else if (item instanceof PubUseDeclaration pubUseDecl) {
                        String depPath = extractModulePathStatic(pubUseDecl.getPath());
                        if (depPath != null) {
                            loadModuleRecursively(depPath, context);
                        }
                    }
                }

                // Convert module to V2 nodes
                ASTToTruffleConverterV2 moduleConverter = new ASTToTruffleConverterV2(context, null, null, null);
                MezzeNode moduleNode = moduleConverter.convertProgram(typedModule);
                FrameDescriptor moduleFrameDescriptor = moduleConverter.getFrameDescriptor();
                Map<String, Integer> variableSlots = moduleConverter.getVariableSlots();

                // Execute module
                com.oracle.truffle.api.frame.MaterializedFrame moduleFrame =
                    com.oracle.truffle.api.Truffle.getRuntime()
                        .createMaterializedFrame(new Object[0], moduleFrameDescriptor);
                moduleNode.execute(moduleFrame);

                // After module execution, register exports with qualified names
                // Read values from the frame using the slot mappings
                registerModuleExports(modulePath, typedModule, context, moduleFrame, variableSlots);

                // Mark as loaded
                loadedModuleSet.add(modulePath);

            } catch (Exception e) {
                throw new RuntimeException("Failed to load module: " + modulePath + " - " + e.getMessage(), e);
            }
        }

        /**
         * Load and typecheck a module on demand when not in cache.
         * Uses the import resolution machinery to parse, typecheck, and cache the module.
         */
        @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        private Program loadAndTypeCheckModule(String modulePath) {
            try {
                // Create a synthetic program that imports just this module
                var segments = java.util.Arrays.asList(modulePath.split("::"));
                var usePath = new ModulePath(segments);
                var useDecl = new UseDeclaration(usePath, null, null);
                var syntheticProgram = new Program(List.of(useDecl), Collections.emptyList());

                // Type-check the synthetic program — this loads and typechecks the module
                var typeChecker = new TypeChecker();
                var result = typeChecker.typeCheck(syntheticProgram,
                    projectRoot != null ? projectRoot : Paths.get("."));

                // Now the module should be in the cache
                return TypeChecker.Companion.getTypedModule(modulePath);
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * Register a module's public exports in the context.
         * Reads values directly from the frame using slot mappings.
         */
        private void registerModuleExports(
                String modulePath,
                Program typedModule,
                MezzeContext context,
                com.oracle.truffle.api.frame.MaterializedFrame moduleFrame,
                Map<String, Integer> variableSlots
        ) {
            String shortPrefix = modulePath.contains("::") ?
                modulePath.substring(modulePath.lastIndexOf("::") + 2) : modulePath;

            for (ProgramItem item : typedModule.getItems()) {
                if (item instanceof LetBinding letBinding) {
                    if (letBinding.getVisibility() == com.mezze.language.ast.Visibility.PUBLIC) {
                        String name = letBinding.getVariableName();
                        Integer slot = variableSlots.get(name);
                        if (slot != null) {
                            Object value = readSlotValue(moduleFrame, slot);
                            if (value != null) {
                                // Primary: Register by content hash for O(1) content-addressable lookup
                                String contentHash = letBinding.getContentHash();
                                if (contentHash != null && !contentHash.isEmpty()) {
                                    context.defineGlobal(contentHash, value);
                                }
                                // Backward compatibility: Register under qualified names
                                // These are used by some runtime code paths that haven't been migrated
                                String fullQualifiedName = modulePath + "::" + name;
                                String shortQualifiedName = shortPrefix + "::" + name;
                                String internalQualifiedName = modulePath.replace("::", "__") + "__" + name;
                                context.defineGlobal(fullQualifiedName, value);
                                context.defineGlobal(shortQualifiedName, value);
                                context.defineGlobal(internalQualifiedName, value);
                                context.defineGlobal(name, value);
                            }
                        }
                    }
                } else if (item instanceof com.mezze.language.ast.ImplDeclaration implDecl) {
                    // Register effect performers
                    String performerName = implDecl.getPerformerName();
                    String contentHash = implDecl.getContentHash();
                    // Try performer name first, then content hash (v1 converter stores handler
                    // records by content hash, not performer name)
                    Integer slot = variableSlots.get(performerName);
                    if (slot == null && contentHash != null) {
                        slot = variableSlots.get(contentHash);
                    }
                    // Also try with # prefix (v1 hash format)
                    if (slot == null && contentHash != null) {
                        slot = variableSlots.get("#" + contentHash);
                    }
                    if (slot != null) {
                        Object value = readSlotValue(moduleFrame, slot);
                        if (value != null) {
                            // Primary: Register by content hash for O(1) content-addressable lookup
                            if (contentHash != null && !contentHash.isEmpty()) {
                                context.defineGlobal(contentHash, value);
                            }
                            // Backward compatibility: Register under qualified names
                            String fullQualifiedName = modulePath + "::" + performerName;
                            String shortQualifiedName = shortPrefix + "::" + performerName;
                            String internalQualifiedName = modulePath.replace("::", "__") + "__" + performerName;
                            context.defineGlobal(fullQualifiedName, value);
                            context.defineGlobal(shortQualifiedName, value);
                            context.defineGlobal(internalQualifiedName, value);
                            context.defineGlobal(performerName, value);
                        }
                    }
                }
            }
        }

        /**
         * Read a frame slot value using getValue() which handles all kinds.
         * Used by module loading to export values that might be primitives.
         */
        private static Object readSlotValue(com.oracle.truffle.api.frame.MaterializedFrame frame, int slot) {
            // getValue() returns the value regardless of type (Object, Long, Double, Boolean)
            return frame.getValue(slot);
        }

        private static String extractModulePathStatic(UsePath path) {
            if (path instanceof ModulePath modulePath) {
                return String.join("::", modulePath.getSegments());
            } else if (path instanceof SelectivePath selectivePath) {
                return String.join("::", selectivePath.getModule());
            }
            return null;
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
