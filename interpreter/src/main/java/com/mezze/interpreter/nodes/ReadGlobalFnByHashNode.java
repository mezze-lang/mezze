package com.mezze.interpreter.nodes;

import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Read a function from the global registry by content hash WITHOUT auto-evaluating thunks.
 *
 * This is used for dictionary passing: when we inject ability op functions as
 * hidden arguments to polymorphic functions, we need the raw MezzeFunction
 * object, not the result of calling it. Regular ReadGlobalByHashNode auto-evaluates
 * zero-param thunks (like value definitions), which would call the ability op
 * function and return its result instead of the function itself.
 *
 * This node specifically returns the MezzeFunction without calling it.
 *
 * <p>After warmup the resolved function is cached as a {@link CompilationFinal}
 * constant — no {@code @TruffleBoundary} crossing on steady-state reads. #54.
 */
@NodeInfo(shortName = "read-global-fn-by-hash")
public final class ReadGlobalFnByHashNode extends MezzeNode {

    @CompilationFinal
    private final Hash hash;

    @CompilationFinal private boolean resolved;
    @CompilationFinal private Object cachedValue;

    public ReadGlobalFnByHashNode(Hash hash) {
        this.hash = hash;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        if (resolved) {
            return cachedValue;
        }
        return resolveAndCache();
    }

    private Object resolveAndCache() {
        Object value = lookupFunction();
        CompilerDirectives.transferToInterpreterAndInvalidate();
        cachedValue = value;
        resolved = true;
        return value;
    }

    @TruffleBoundary
    private Object lookupFunction() {
        MezzeContext context = MezzeContext.get(this);
        Object value = context.lookupGlobal(hash);

        // Fall back to Language-level lazy compilation (for functions referenced by hash)
        if (value == null) {
            var lang = com.mezze.interpreter.runtime.MezzeLanguage.get(this);
            var ct = lang.getCachedStdlibCallTarget(hash);
            if (ct != null) {
                value = MezzeFunction.create(ct, hash.getHex());
                // Cache in context for subsequent lookups
                context.defineGlobal(hash, value);
            }
        }

        if (value == null) {
            throw new RuntimeException(
                "Dict function not found in context: '" + hash.getHex() + "'");
        }

        // If the value is already a MezzeFunction, return it directly.
        // Do NOT auto-evaluate — that's the whole point of this node.
        if (value instanceof MezzeFunction) {
            return value;
        }

        // If somehow the value was already evaluated (e.g., cached as a result),
        // we need to re-resolve the function. Look up the CallTarget directly.
        var lang = com.mezze.interpreter.runtime.MezzeLanguage.get(this);
        var ct = lang.getCachedStdlibCallTarget(hash);
        if (ct != null) {
            var fn = MezzeFunction.create(ct, hash.getHex());
            context.defineGlobal(hash, fn);
            return fn;
        }

        // Last resort: return whatever we got. The DictMethodCallNode's
        // fallback path will handle non-function values gracefully.
        return value;
    }
}
