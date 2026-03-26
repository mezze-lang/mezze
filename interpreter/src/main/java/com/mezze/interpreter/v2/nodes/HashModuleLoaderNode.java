package com.mezze.interpreter.v2.nodes;

import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.Map;

/**
 * V2 Module Loader — registers pre-compiled functions by content hash.
 *
 * At compile time, all dependency modules are compiled to CallTargets.
 * This node registers them in the global context on first execution,
 * then runs the main body.
 *
 * No file I/O. No parsing. No typechecking at runtime.
 * Just: hash → CallTarget registration, then execute.
 */
@NodeInfo(shortName = "hash-module-loader")
public final class HashModuleLoaderNode extends MezzeNode {

    @Child private MezzeNode bodyNode;

    /** Pre-compiled functions from all dependency modules, keyed by content hash */
    @CompilationFinal(dimensions = 1)
    private final String[] functionHashes;

    @CompilationFinal(dimensions = 1)
    private final CallTarget[] functionTargets;

    @CompilationFinal
    private boolean modulesLoaded = false;

    public HashModuleLoaderNode(
            MezzeNode bodyNode,
            Map<String, CallTarget> compiledFunctions
    ) {
        this.bodyNode = bodyNode;
        this.functionHashes = compiledFunctions.keySet().toArray(new String[0]);
        this.functionTargets = compiledFunctions.values().toArray(new CallTarget[0]);
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
        for (int i = 0; i < functionHashes.length; i++) {
            String hash = functionHashes[i];
            CallTarget target = functionTargets[i];
            if (!context.hasGlobal(hash)) {
                // Create a MezzeFunction and register by hash
                var function = com.mezze.interpreter.v2.values.MezzeFunction.create(target, hash);
                context.defineGlobal(hash, function);
            }
        }
        modulesLoaded = true;
    }
}
