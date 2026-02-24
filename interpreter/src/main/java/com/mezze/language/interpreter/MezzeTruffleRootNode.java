package com.mezze.language.interpreter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.nodes.RootNode;

import com.mezze.language.ast.EffectDeclaration;
import com.mezze.language.interpreter.nodes.MezzeNode;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optimized Truffle root node for program execution using specialized nodes.
 * Handles module loading and execution before running the main program.
 *
 * Written in Java to avoid Kotlin intrinsics that trigger JIT compilation blockers.
 */
public class MezzeTruffleRootNode extends RootNode {

    @Child
    private MezzeNode programNode;

    private final String sourceCode;
    private final List<String> importedModules;
    private final Path projectRoot;
    private final List<EffectDeclaration> effectDeclarations;

    // Lazy-initialized module cache to avoid reloading modules on every execution
    private final Set<String> loadedModules = new HashSet<>();

    // Track whether effects have been registered
    @CompilationFinal
    private boolean effectsRegistered = false;

    // Track whether modules have been loaded
    @CompilationFinal
    private boolean modulesLoaded = false;

    public MezzeTruffleRootNode(
            MezzeLanguage language,
            MezzeNode programNode,
            FrameDescriptor frameDescriptor,
            String sourceCode,
            List<String> importedModules,
            Path projectRoot,
            List<EffectDeclaration> effectDeclarations
    ) {
        super(language, frameDescriptor);
        this.programNode = programNode;
        this.sourceCode = sourceCode;
        this.importedModules = importedModules;
        this.projectRoot = projectRoot;
        this.effectDeclarations = effectDeclarations;
    }

    /**
     * Main execute method - keep this as simple as possible to avoid
     * triggering JIT compilation blockers.
     */
    @Override
    public Object execute(VirtualFrame frame) {
        // One-time setup: register effects and load modules
        // This branch will be folded away after the first execution due to @CompilationFinal
        if (!effectsRegistered || !modulesLoaded) {
            performOneTimeSetup();
        }

        // Execute the main program - this is the hot path
        MezzeValue result = programNode.execute(frame);

        // Post-process result - delegate to boundary method
        return postProcessResult(result);
    }

    /**
     * One-time setup: register effects and load modules.
     * Marked as TruffleBoundary because it only runs once and does complex operations.
     */
    @TruffleBoundary
    private void performOneTimeSetup() {
        MezzeContext context = MezzeLanguage.Companion.getCurrentContext(this);

        // Register effect declarations
        if (!effectsRegistered) {
            EffectHandler effectHandler = context.getEffectHandler();
            for (EffectDeclaration effectDecl : effectDeclarations) {
                Map<String, MezzeValue> operationsMap = new HashMap<>();
                for (var op : effectDecl.getOperations()) {
                    operationsMap.put(op.getName(), MezzeNull.INSTANCE);
                }
                MezzeRecord effectRecord = MezzeRecord.invoke(operationsMap);
                effectHandler.registerEffect(effectDecl.getName(), effectRecord);
            }
            effectsRegistered = true;
        }

        // Load modules
        if (!modulesLoaded) {
            loadModules(importedModules, context);
            modulesLoaded = true;
        }
    }

    /**
     * Post-process the execution result - marked as TruffleBoundary
     * to keep type checks and conversions outside JIT path.
     */
    @TruffleBoundary
    private Object postProcessResult(MezzeValue result) {
        try {
            // Store execution context and result for test access
            MezzeContext context = MezzeLanguage.Companion.getCurrentContext(this);
            MezzeLanguage.storeLastExecutionContext(context);
            MezzeLanguage.storeLastExecutionResult(result);

            // Convert MezzeValue to appropriate return type for Truffle interop
            if (result instanceof MezzeInt) {
                return ((MezzeInt) result).getValue();
            } else if (result instanceof MezzeFloat) {
                return ((MezzeFloat) result).getValue();
            } else if (result instanceof MezzeString) {
                return ((MezzeString) result).getValue();
            } else if (result instanceof MezzeBool) {
                return ((MezzeBool) result).getValue();
            } else if (result instanceof MezzeNull) {
                return "null";
            } else {
                return result.getDisplayString();
            }
        } catch (Exception e) {
            return handleExecutionException(e);
        }
    }

    /**
     * Handle execution exceptions - marked as TruffleBoundary to exclude from runtime compilation.
     */
    @TruffleBoundary
    private Object handleExecutionException(Exception e) {
        System.err.println("ERROR: Exception in MezzeTruffleRootNode.execute:");
        System.err.println("  Message: " + e.getMessage());
        System.err.println("  Class: " + e.getClass().getName());
        System.err.println("  Cause: " + e.getCause());
        e.printStackTrace(System.err);
        throw new MezzeException("Execution error in Truffle node: " + e.getMessage(), e);
    }

    /**
     * Load and execute all modules imported via UseDeclarations.
     * This registers module functions in the context so they can be called.
     */
    private void loadModules(List<String> modulePaths, MezzeContext context) {
        for (String modulePath : modulePaths) {
            loadModuleRecursively(modulePath, context);
        }
    }

    /**
     * Load a module and all its dependencies recursively.
     * Uses the Kotlin module loading infrastructure.
     */
    @TruffleBoundary
    private void loadModuleRecursively(String modulePath, MezzeContext context) {
        // Delegate to Kotlin implementation via companion object
        MezzeTruffleRootNodeHelper.INSTANCE.loadModuleRecursively(
                modulePath, context, loadedModules, projectRoot
        );
    }
}
