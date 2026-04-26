package com.mezze.interpreter.nodes;

import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeFunction;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Read a function from the MezzeContext by hash WITHOUT auto-evaluating thunks.
 *
 * This is used for dictionary passing: when we inject ability op functions as
 * hidden arguments to polymorphic functions, we need the raw MezzeFunction
 * object, not the result of calling it. Regular ReadContextNode auto-evaluates
 * zero-param thunks (like value definitions), which would call the ability op
 * function and return its result instead of the function itself.
 *
 * This node specifically returns the MezzeFunction without calling it.
 */
@NodeInfo(shortName = "read-context-fn")
public final class ReadContextFnNode extends MezzeNode {

    @CompilationFinal
    private final String name;

    public ReadContextFnNode(String name) {
        this.name = name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return lookupFunction();
    }

    @TruffleBoundary
    private Object lookupFunction() {
        MezzeContext context = MezzeContext.get(this);
        Object value = context.lookupGlobal(name);

        // Fall back to Language-level lazy compilation (for functions referenced by hash)
        if (value == null) {
            var lang = com.mezze.interpreter.runtime.MezzeLanguage.get(this);
            var ct = lang.getCachedStdlibCallTarget(name);
            if (ct != null) {
                value = MezzeFunction.create(ct, name);
                // Cache in context for subsequent lookups
                context.defineGlobal(name, value);
            }
        }

        if (value == null) {
            throw new RuntimeException(
                "Dict function not found in context: '" + name + "'");
        }

        // If the value is already a MezzeFunction, return it directly.
        // Do NOT auto-evaluate — that's the whole point of this node.
        if (value instanceof MezzeFunction) {
            return value;
        }

        // If somehow the value was already evaluated (e.g., cached as a result),
        // we need to re-resolve the function. Look up the CallTarget directly.
        var lang = com.mezze.interpreter.runtime.MezzeLanguage.get(this);
        var ct = lang.getCachedStdlibCallTarget(name);
        if (ct != null) {
            var fn = MezzeFunction.create(ct, name);
            context.defineGlobal(name, fn);
            return fn;
        }

        // Last resort: return whatever we got. The DictMethodCallNode's
        // fallback path will handle non-function values gracefully.
        return value;
    }
}
