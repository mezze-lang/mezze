package com.mezze.interpreter.nodes;

import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeFunction;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.Map;

/**
 * V2 Module Loader — registers pre-compiled functions by content hash.
 *
 * At compile time, all dependency modules are compiled to CallTargets.
 * This node registers them in the global context on first execution,
 * then runs the main body.
 *
 * Closure factory functions (whose body is MakeLambdaIR) are registered as-is.
 * MonomorphicCallNode evaluates them lazily on first call to get the actual closure.
 *
 * No file I/O. No parsing. No typechecking at runtime.
 * Just: hash → CallTarget registration, then execute.
 */
@NodeInfo(shortName = "hash-module-loader")
public final class HashModuleLoaderNode extends MezzeNode {

    @Child private MezzeNode bodyNode;

    @CompilationFinal(dimensions = 1)
    private final String[] functionHashes;

    @CompilationFinal(dimensions = 1)
    private final CallTarget[] functionTargets;

    @CompilationFinal(dimensions = 1)
    private final String[] functionNames;

    /** Performer records: each entry is [performerHash, effectHash, op1Hash, op1Name, op2Hash, op2Name, ...] */
    @CompilationFinal(dimensions = 1)
    private final String[][] performerInfos;

    @CompilationFinal
    private boolean modulesLoaded = false;

    // Closure cache moved to MezzeContext (per-context) to avoid sharing CallTargets across Polyglot contexts.

    public HashModuleLoaderNode(
            MezzeNode bodyNode,
            Map<String, CallTarget> compiledFunctions
    ) {
        this(bodyNode, compiledFunctions, null);
    }

    public HashModuleLoaderNode(
            MezzeNode bodyNode,
            Map<String, CallTarget> compiledFunctions,
            Map<String, String> hashToName
    ) {
        this(bodyNode, compiledFunctions, hashToName, null);
    }

    public HashModuleLoaderNode(
            MezzeNode bodyNode,
            Map<String, CallTarget> compiledFunctions,
            Map<String, String> hashToName,
            String[][] performerInfos
    ) {
        this.bodyNode = bodyNode;
        this.functionHashes = compiledFunctions.keySet().toArray(new String[0]);
        this.functionTargets = compiledFunctions.values().toArray(new CallTarget[0]);
        this.performerInfos = performerInfos != null ? performerInfos : new String[0][];
        if (hashToName != null) {
            this.functionNames = new String[functionHashes.length];
            for (int i = 0; i < functionHashes.length; i++) {
                this.functionNames[i] = hashToName.get(functionHashes[i]);
            }
        } else {
            this.functionNames = new String[0];
        }
    }

    // Track which MezzeContext instances we've loaded into
    // (needed because with SHARED policy, same CallTarget runs in multiple Contexts)
    @CompilationFinal
    private Object lastContextId = null;

    @Override
    public Object execute(VirtualFrame frame) {
        // Re-register if context changed (SHARED policy: same CallTarget, different Contexts)
        MezzeContext currentContext;
        try {
            currentContext = MezzeContext.get(this);
        } catch (Exception e) {
            currentContext = null;
        }
        if (!modulesLoaded || (currentContext != null && System.identityHashCode(currentContext) != (lastContextId instanceof Integer i ? i : -1))) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loadModules();
            lastContextId = System.identityHashCode(currentContext);
        }
        return bodyNode.execute(frame);
    }

    @CompilerDirectives.TruffleBoundary
    private void loadModules() {
        MezzeContext context = MezzeContext.get(this);
        var emptyRecord = com.mezze.interpreter.values.MezzeRecord.empty();
        // Functions are pre-compiled (stdlib cached at Language level, user code fresh per test)
        try {

        // Closure cache is now per-context (MezzeContext.closureCache) — no clearing needed.

        // Pass 1: Register all functions by hash AND name (raw wrappers)
        for (int i = 0; i < functionHashes.length; i++) {
            String hash = functionHashes[i];
            CallTarget target = functionTargets[i];
            if (!context.hasGlobal(hash)) {
                var function = MezzeFunction.create(target, hash);
                context.defineGlobal(hash, function);
                if (i < functionNames.length && functionNames[i] != null) {
                    context.defineGlobal(functionNames[i], function);
                }
            }
        }

        // Pass 2: Construct performer records.
        // For parameterized performers (whose hash maps to a factory function that takes params),
        // keep the function as-is — it will be called with actual params at perform time.
        for (String[] info : performerInfos) {
            if (info.length < 2) continue;
            String performerHash = info[0];
            String effectHash = info[1];
            int opCount = (info.length - 2) / 2;
            if (opCount > 0) {
                // Check if performer hash maps to a factory function (parameterized performer).
                // If so, keep it as a function — ParameterizedHandlerLookupNode will call it
                // with actual params at perform time.
                Object existing = context.lookupGlobal(performerHash);
                if (existing instanceof MezzeFunction fn) {
                    // If a MezzeFunction is registered at the performer hash, it's a
                    // parameterized performer factory (compiled by TypedASTCompiler).
                    // Non-parameterized performers don't have a function at the performer hash
                    // — only individual operation functions at their own opHash.
                    // Keep the factory as-is so ParameterizedHandlerLookupNode can call it
                    // with actual params at perform time.
                    continue;
                }
                // Check cache
                Object cached = context.getCachedClosure(performerHash);
                if (cached instanceof com.mezze.interpreter.values.MezzeRecord) {
                    context.defineGlobal(performerHash, cached);
                    if (!effectHash.isEmpty()) context.defineGlobal(effectHash, cached);
                } else {
                    constructPerformerRecord(context, performerHash, effectHash, info, opCount);
                }
            }
        }

        modulesLoaded = true;
        } catch (Exception e) {
            modulesLoaded = true;
        }
    }

    @CompilerDirectives.TruffleBoundary
    private void constructPerformerRecord(MezzeContext context, String performerHash, String effectHash, String[] info, int opCount) {
        String[] opNames = new String[opCount];
        Object[] opValues = new Object[opCount];
        var emptyRecord = com.mezze.interpreter.values.MezzeRecord.empty();
        for (int i = 0; i < opCount; i++) {
            String opHash = info[2 + i * 2];
            opNames[i] = info[2 + i * 2 + 1];
            // Look up the compiled operation function — try context, then Language cache
            Object opFunc = context.lookupGlobal(opHash);
            if (opFunc == null) {
                // Fall back to Language-level stdlib cache
                var lang = com.mezze.interpreter.runtime.MezzeLanguage.get(this);
                var ct = lang.getCachedStdlibCallTarget(opHash);
                if (ct != null) {
                    opFunc = MezzeFunction.create(ct, opHash);
                }
            }

            if (opFunc instanceof MezzeFunction fn) {
                Object cached = context.getCachedClosure(opHash);
                if (cached instanceof MezzeFunction) {
                    opFunc = cached;
                }
            }
            opValues[i] = opFunc != null ? opFunc : com.mezze.interpreter.values.MezzeUnit.INSTANCE;
        }
        // Create performer record with operation functions as fields
        var locations = new com.mezze.interpreter.values.MezzeRecord.FieldLocation[opCount];
        for (int i = 0; i < opCount; i++) {
            locations[i] = new com.mezze.interpreter.values.MezzeRecord.FieldLocation(
                com.mezze.interpreter.values.MezzeRecord.FieldLocation.StorageType.OBJECT, i);
        }
        var shape = new com.mezze.interpreter.values.MezzeRecord.Shape(opNames, locations);
        var record = com.mezze.interpreter.values.MezzeRecord.withAllObjects(shape, opValues);
        // Register under performer hash AND effect hash, and cache
        context.defineGlobal(performerHash, record);
        context.putCachedClosure(performerHash, record);
        if (!effectHash.isEmpty()) {
            context.defineGlobal(effectHash, record);
        }
    }

}
