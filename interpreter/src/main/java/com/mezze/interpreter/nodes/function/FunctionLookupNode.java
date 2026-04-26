package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeFunction;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Looks up a function by content hash and returns the MezzeFunction object
 * WITHOUT calling it. Used for tail call optimization where TailCallNode
 * needs the function object to throw TailCallException.
 */
@NodeInfo(shortName = "function-lookup")
public final class FunctionLookupNode extends MezzeNode {

    @CompilationFinal
    private final String contentHash;

    @CompilationFinal
    private MezzeFunction cachedFunction;

    public FunctionLookupNode(String contentHash) {
        this.contentHash = contentHash;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (cachedFunction != null) {
            return cachedFunction;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        return resolveFunction();
    }

    @TruffleBoundary
    private MezzeFunction resolveFunction() {
        MezzeContext context = MezzeContext.get(this);
        Object value = context.lookupGlobal(contentHash);

        // Fall back to Language-level cache (same pattern as MonomorphicCallNode).
        // User functions are registered via language.registerFunctionIR but not
        // eagerly placed in the per-context global map.
        if (value == null) {
            var lang = com.mezze.interpreter.runtime.MezzeLanguage.get(this);
            var ct = lang.getCachedStdlibCallTarget(contentHash);
            if (ct != null) {
                value = MezzeFunction.create(ct, contentHash);
                context.defineGlobal(contentHash, value);
            }
        }

        if (value instanceof MezzeFunction fn) {
            cachedFunction = fn;
            return fn;
        }
        throw new RuntimeException("Function not found for tail call: " + contentHash);
    }
}
