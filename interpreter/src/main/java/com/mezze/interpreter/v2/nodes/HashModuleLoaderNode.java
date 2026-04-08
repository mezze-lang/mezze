package com.mezze.interpreter.v2.nodes;

import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.values.MezzeFunction;
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

    /** Static cache of evaluated closures — shared across tests within same JVM.
     *  Key: function hash, Value: evaluated MezzeFunction closure (or MezzeRecord for performers) */
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> evaluatedClosureCache
        = new java.util.concurrent.ConcurrentHashMap<>();

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

    @Override
    public Object execute(VirtualFrame frame) {
        if (!modulesLoaded) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loadModules();
        }
        return bodyNode.execute(frame);
    }

    @CompilerDirectives.TruffleBoundary
    private void loadModules() {
        MezzeContext context = MezzeContext.get(this);
        var emptyRecord = com.mezze.interpreter.v2.values.MezzeRecord.empty();
        try {

        // Clear the static closure cache to avoid sharing CallTargets across Polyglot contexts.
        // Each test creates a new context, and Truffle requires CallTargets to be context-specific.
        evaluatedClosureCache.clear();

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

        // Pass 2: Evaluate ONLY performer operation closure factories.
        // Non-performer functions are evaluated lazily by MonomorphicCallNode at call time.
        // This avoids OOM from eagerly evaluating hundreds of closure factories.
        java.util.Set<String> performerOpHashes = new java.util.HashSet<>();
        for (String[] info : performerInfos) {
            for (int j = 2; j < info.length; j += 2) {
                performerOpHashes.add(info[j]); // operation hash
            }
        }

        for (int i = 0; i < functionHashes.length; i++) {
            String hash = functionHashes[i];
            // Only evaluate performer operation functions — everything else is lazy
            if (!performerOpHashes.contains(hash)) {
                continue;
            }
            Object cached = evaluatedClosureCache.get(hash);
            if (cached != null) {
                context.defineGlobal(hash, cached);
                if (i < functionNames.length && functionNames[i] != null) {
                    context.defineGlobal(functionNames[i], cached);
                }
                continue;
            }
            CallTarget target = functionTargets[i];
            try {
                Object result = target.call(new Object[] { emptyRecord });
                if (result instanceof MezzeFunction closure) {
                    context.defineGlobal(hash, closure);
                    if (i < functionNames.length && functionNames[i] != null) {
                        context.defineGlobal(functionNames[i], closure);
                    }
                    evaluatedClosureCache.put(hash, closure);
                } else if (result instanceof com.mezze.interpreter.v2.values.MezzeRecord record) {
                    context.defineGlobal(hash, record);
                    if (i < functionNames.length && functionNames[i] != null) {
                        context.defineGlobal(functionNames[i], record);
                    }
                    evaluatedClosureCache.put(hash, record);
                }
            } catch (StackOverflowError soe) {
                // Infinite recursion in closure factory — skip, keep raw MezzeFunction
            } catch (OutOfMemoryError oom) {
                // OOM from deep recursion — skip
            } catch (Exception e) {
                // Not a closure factory or depends on unavailable captures
            }
        }

        // Pass 3: Construct performer records from evaluated closures.
        for (String[] info : performerInfos) {
            if (info.length < 2) continue;
            String performerHash = info[0];
            String effectHash = info[1];
            int opCount = (info.length - 2) / 2;
            if (opCount > 0) {
                // Check cache
                Object cached = evaluatedClosureCache.get(performerHash);
                if (cached instanceof com.mezze.interpreter.v2.values.MezzeRecord) {
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
        var emptyRecord = com.mezze.interpreter.v2.values.MezzeRecord.empty();
        for (int i = 0; i < opCount; i++) {
            String opHash = info[2 + i * 2];
            opNames[i] = info[2 + i * 2 + 1];
            // Look up the compiled operation function and evaluate to get closure
            Object opFunc = context.lookupGlobal(opHash);

            // Don't evaluate operation functions — they take parameters at runtime.
            // Just use the raw MezzeFunction as the performer record field value.
            if (opFunc instanceof MezzeFunction fn) {
                // Check if it's a closure factory (returns a function when called with empty record)
                // Only for cached entries — skip evaluation for fresh functions
                Object cached = evaluatedClosureCache.get(opHash);
                if (cached instanceof MezzeFunction) {
                    opFunc = cached;
                }
            }
            opValues[i] = opFunc != null ? opFunc : com.mezze.interpreter.v2.values.MezzeUnit.INSTANCE;
        }
        // Create performer record with operation functions as fields
        var locations = new com.mezze.interpreter.v2.values.MezzeRecord.FieldLocation[opCount];
        for (int i = 0; i < opCount; i++) {
            locations[i] = new com.mezze.interpreter.v2.values.MezzeRecord.FieldLocation(
                com.mezze.interpreter.v2.values.MezzeRecord.FieldLocation.StorageType.OBJECT, i);
        }
        var shape = new com.mezze.interpreter.v2.values.MezzeRecord.Shape(opNames, locations);
        var record = com.mezze.interpreter.v2.values.MezzeRecord.withAllObjects(shape, opValues);
        // Register under performer hash AND effect hash, and cache
        context.defineGlobal(performerHash, record);
        evaluatedClosureCache.put(performerHash, record);
        if (!effectHash.isEmpty()) {
            context.defineGlobal(effectHash, record);
        }
    }
}
