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

        // Pass 1: Register all functions by hash (raw wrappers)
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

        // Pass 2: Evaluate closure factories — call each function with empty record.
        // Functions whose body is MakeLambdaIR return a MezzeFunction closure.
        // Replace the raw wrapper with the closure in globals.
        // This replicates what ModuleLoadingNode did: execute module code to get closures.
        for (int i = 0; i < functionHashes.length; i++) {
            String hash = functionHashes[i];
            CallTarget target = functionTargets[i];
            try {
                Object result = target.call(new Object[] { emptyRecord });
                if (result instanceof MezzeFunction closure) {
                    // Replace raw wrapper with evaluated closure
                    context.defineGlobal(hash, closure);
                    if (i < functionNames.length && functionNames[i] != null) {
                        context.defineGlobal(functionNames[i], closure);
                    }
                }
            } catch (Exception e) {
                // Not a closure factory or evaluation needs specific args — keep raw wrapper
            }
        }

        // Pass 3: Construct performer records from compiled operation functions.
        // Each performer record is a MezzeRecord with operation functions as fields.
        for (String[] info : performerInfos) {
            if (info.length < 2) continue;
            String performerHash = info[0];
            String effectHash = info[1];
            // Remaining entries are pairs: [opHash, opName, opHash, opName, ...]
            int opCount = (info.length - 2) / 2;
            if (opCount > 0) {
                constructPerformerRecord(context, performerHash, effectHash, info, opCount);
            }
        }

        modulesLoaded = true;
    }

    @CompilerDirectives.TruffleBoundary
    private void constructPerformerRecord(MezzeContext context, String performerHash, String effectHash, String[] info, int opCount) {
        String[] opNames = new String[opCount];
        Object[] opValues = new Object[opCount];
        for (int i = 0; i < opCount; i++) {
            String opHash = info[2 + i * 2];
            opNames[i] = info[2 + i * 2 + 1];
            // Look up the compiled operation function
            Object opFunc = context.lookupGlobal(opHash);
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
        // Register under performer hash AND effect hash
        context.defineGlobal(performerHash, record);
        if (!effectHash.isEmpty()) {
            context.defineGlobal(effectHash, record);
        }
    }
}
