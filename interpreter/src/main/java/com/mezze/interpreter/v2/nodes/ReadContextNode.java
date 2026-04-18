package com.mezze.interpreter.v2.nodes;

import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Read a named value from the MezzeContext.
 *
 * Used for scoped effect variables like `scope` which are registered
 * in the context by HandleNode and need to be read by handler operation
 * lambdas that execute in a different frame.
 *
 * Auto-evaluates zero-param zero-capture "value thunks" (like trig::pi, trig::e).
 * These are top-level values compiled as zero-param FunctionIRs. When read in
 * non-call position (e.g., `pi / 2.0`), the thunk is called to get the actual
 * value instead of returning the MezzeFunction wrapper.
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
                value = MezzeFunction.create(ct, name);
            }
        }
        if (value == null) {
            throw new RuntimeException("Context variable '" + name + "' not found");
        }
        // Auto-evaluate value thunks: zero-param zero-capture functions that wrap
        // top-level values (like trig::pi = 3.14...). These are compiled as FunctionIRs
        // but should return their computed value when read, not the function itself.
        if (value instanceof MezzeFunction fn) {
            value = autoEvaluateThunk(fn, context);
        }
        return value;
    }

    /**
     * If the function is a zero-param zero-capture thunk (value definition),
     * call it to get the actual value. Cache the result in the global context
     * so subsequent lookups are instant.
     */
    @TruffleBoundary
    private Object autoEvaluateThunk(MezzeFunction fn, MezzeContext context) {
        CallTarget ct = fn.getCallTarget();
        if (!(ct instanceof RootCallTarget rct)) return fn;
        RootNode root = rct.getRootNode();
        // Check for MezzeRootNode (no-param thunk) — these are value definitions
        if (root instanceof MezzeRootNode mrn) {
            if (mrn.getClosureCount() == 0 && mrn.getParameterCount() == 0
                    && fn.getClosureValues().length == 0) {
                try {
                    Object result = ct.call(com.mezze.interpreter.v2.values.MezzeUnit.INSTANCE);
                    // Cache the evaluated value so we don't re-evaluate on every access
                    context.defineGlobal(name, result);
                    return result;
                } catch (Exception e) {
                    // If evaluation fails, return the function as-is (it might be
                    // a function that legitimately takes no params but has side effects)
                    return fn;
                }
            }
        }
        return fn;
    }
}
