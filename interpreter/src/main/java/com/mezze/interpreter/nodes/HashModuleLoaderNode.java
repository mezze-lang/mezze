package com.mezze.interpreter.nodes;

import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.language.typedast.Hash;
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
    private final Hash[] functionHashes;

    @CompilationFinal(dimensions = 1)
    private final CallTarget[] functionTargets;

    @CompilationFinal(dimensions = 1)
    private final String[] functionNames;

    /**
     * Per-performer typed metadata. Parsed once at constructor time from the
     * wire-format `String[][]` (each row was
     * `[performerHashHex, effectHashHex, op1HashHex, op1Name, op2HashHex, op2Name, ...]`).
     * The typed records prevent the parallel-array index-drift bug class.
     */
    @CompilationFinal(dimensions = 1)
    private final PerformerInfo[] performerInfos;

    /** Operation entry in a performer record: pre-resolved hash + display name. */
    public static final class OpBinding {
        public final Hash hash;
        public final String name;
        OpBinding(Hash hash, String name) {
            this.hash = hash;
            this.name = name;
        }
    }

    /** Pre-decoded performer record: {@code performerHash}, optional {@code effectHash}, list of operation bindings. */
    public static final class PerformerInfo {
        public final Hash performerHash;
        /** Effect hash; may be null for performers not bound to a single effect. */
        public final Hash effectHash;
        public final OpBinding[] ops;
        PerformerInfo(Hash performerHash, Hash effectHash, OpBinding[] ops) {
            this.performerHash = performerHash;
            this.effectHash = effectHash;
            this.ops = ops;
        }
    }

    private static PerformerInfo[] decodePerformerInfos(String[][] raw) {
        if (raw == null || raw.length == 0) return new PerformerInfo[0];
        PerformerInfo[] out = new PerformerInfo[raw.length];
        int n = 0;
        for (String[] info : raw) {
            if (info.length < 2) continue;
            Hash performerHash = new Hash(info[0]);
            Hash effectHash = info[1].isEmpty() ? null : new Hash(info[1]);
            int opCount = (info.length - 2) / 2;
            OpBinding[] ops = new OpBinding[opCount];
            for (int i = 0; i < opCount; i++) {
                ops[i] = new OpBinding(new Hash(info[2 + i * 2]), info[2 + i * 2 + 1]);
            }
            out[n++] = new PerformerInfo(performerHash, effectHash, ops);
        }
        if (n != out.length) {
            PerformerInfo[] trimmed = new PerformerInfo[n];
            System.arraycopy(out, 0, trimmed, 0, n);
            return trimmed;
        }
        return out;
    }

    @CompilationFinal
    private boolean modulesLoaded = false;

    // Closure cache moved to MezzeContext (per-context) to avoid sharing CallTargets across Polyglot contexts.

    public HashModuleLoaderNode(
            MezzeNode bodyNode,
            Map<Hash, CallTarget> compiledFunctions
    ) {
        this(bodyNode, compiledFunctions, null);
    }

    public HashModuleLoaderNode(
            MezzeNode bodyNode,
            Map<Hash, CallTarget> compiledFunctions,
            Map<Hash, String> hashToName
    ) {
        this(bodyNode, compiledFunctions, hashToName, null);
    }

    public HashModuleLoaderNode(
            MezzeNode bodyNode,
            Map<Hash, CallTarget> compiledFunctions,
            Map<Hash, String> hashToName,
            String[][] performerInfos
    ) {
        this.bodyNode = bodyNode;
        this.functionHashes = compiledFunctions.keySet().toArray(new Hash[0]);
        this.functionTargets = compiledFunctions.values().toArray(new CallTarget[0]);
        this.performerInfos = decodePerformerInfos(performerInfos);
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
            Hash hash = functionHashes[i];
            CallTarget target = functionTargets[i];
            if (!context.hasGlobal(hash)) {
                var function = MezzeFunction.create(target, hash.getHex());
                context.defineGlobal(hash, function);
                // Note: name-keyed globals dropped — name lookup goes via stdlibHashToName,
                // not the per-context globals map. Keeping the typed Hash key prevents
                // collisions with user-defined names that happen to look like hashes.
            }
        }

        // Pass 2: Construct performer records.
        //
        // Performer records live in MezzeContext.handlersByHash, NOT in
        // globals. This is the single source of truth for `perform Handler in
        // body` to resolve handlers — HandleNode.lookupInContext goes there
        // and nowhere else. Keeping them out of `globals` means a later
        // re-load can't overwrite a good handler record with a worse one if
        // op-function resolution flakes between context resets.
        //
        // For parameterized performers (whose hash maps to a factory
        // function that takes params), keep the factory function and skip —
        // ParameterizedHandlerLookupNode invokes it with the actual params
        // at perform time.
        for (PerformerInfo info : performerInfos) {
            if (info.ops.length == 0) continue;
            Hash performerHash = info.performerHash;
            Hash effectHash = info.effectHash;
            // Skip parameterized factories: their hash maps to a MezzeFunction
            // in globals (pass 1 above). They're resolved per-call with params.
            Object globalEntry = context.lookupGlobal(performerHash);
            if (globalEntry instanceof MezzeFunction) {
                continue;
            }
            // Write-once: a good record under this hash already wins. Don't
            // let a later load corrupt it.
            if (context.lookupHandler(performerHash) instanceof MezzeRecord) {
                continue;
            }
            constructPerformerRecord(context, info);
        }

        modulesLoaded = true;
        } catch (Exception e) {
            modulesLoaded = true;
        }
    }

    @CompilerDirectives.TruffleBoundary
    private void constructPerformerRecord(MezzeContext context, PerformerInfo info) {
        int opCount = info.ops.length;
        String[] opNames = new String[opCount];
        Object[] opValues = new Object[opCount];
        for (int i = 0; i < opCount; i++) {
            Hash opHash = info.ops[i].hash;
            opNames[i] = info.ops[i].name;
            // Resolve the op function: first the context's globals (set by
            // pass 1 from THIS loader), then the Language stdlib cache
            // (compiled on demand from PrecompiledStdlib).
            Object opFunc = context.lookupGlobal(opHash);
            if (opFunc == null) {
                var lang = com.mezze.interpreter.runtime.MezzeLanguage.get(this);
                var ct = lang.getCachedStdlibCallTarget(opHash);
                if (ct != null) {
                    opFunc = MezzeFunction.create(ct, opHash.getHex());
                }
            }
            if (opFunc == null) {
                // Don't publish a partial handler. The op function isn't
                // available yet on this context — a later module-load pass
                // (with the missing function pre-registered) will see the
                // entry still empty and try again. Better an explicit
                // "Handler not found" at perform time than a silent dispatch
                // to MezzeUnit fields.
                return;
            }
            opValues[i] = opFunc;
        }
        var locations = new com.mezze.interpreter.values.MezzeRecord.FieldLocation[opCount];
        for (int i = 0; i < opCount; i++) {
            locations[i] = new com.mezze.interpreter.values.MezzeRecord.FieldLocation(
                com.mezze.interpreter.values.MezzeRecord.FieldLocation.StorageType.OBJECT, i);
        }
        var shape = new com.mezze.interpreter.values.MezzeRecord.Shape(opNames, locations);
        var record = com.mezze.interpreter.values.MezzeRecord.withAllObjects(shape, opValues);
        // Publish under performer hash AND effect hash in handlersByHash.
        // This is the single source HandleNode consults.
        context.defineHandler(info.performerHash, record);
        if (info.effectHash != null) {
            context.defineHandler(info.effectHash, record);
        }
    }

}
