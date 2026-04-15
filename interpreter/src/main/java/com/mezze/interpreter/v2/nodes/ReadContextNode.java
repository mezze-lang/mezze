package com.mezze.interpreter.v2.nodes;

import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Read a named value from the MezzeContext.
 *
 * Used for scoped effect variables like `scope` which are registered
 * in the context by HandleNode and need to be read by handler operation
 * lambdas that execute in a different frame.
 */
@NodeInfo(shortName = "read-context")
public final class ReadContextNode extends MezzeNode {

    @CompilationFinal
    private final String name;

    public ReadContextNode(String name) {
        this.name = name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return lookupInContext();
    }

    @TruffleBoundary
    private Object lookupInContext() {
        MezzeContext context = MezzeContext.get(this);
        Object value = context.lookupLocal(name);
        if (value == null) {
            value = context.lookupGlobal(name);
        }
        // Fall back to Language-level lazy compilation (for functions referenced by hash)
        if (value == null) {
            var lang = com.mezze.interpreter.v2.runtime.MezzeLanguage.get(this);
            var ct = lang.getCachedStdlibCallTarget(name);
            if (ct != null) {
                value = com.mezze.interpreter.v2.values.MezzeFunction.create(ct, name);
            }
        }
        if (value == null) {
            throw new RuntimeException("Context variable '" + name + "' not found");
        }
        return value;
    }
}
