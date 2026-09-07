package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.interpreter.bytecode.TrampolineRootNode;
import com.mezze.interpreter.values.MezzeFunction;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;

/**
 * Shared helper for the native HOF scalar-ABI bypass pattern.
 *
 * When a HOF callback is a bypassable BDSL function (TrampolineRootNode
 * without tail calls), we can resolve the inner CallTarget and pass
 * arguments as raw scalars — eliminating MezzeRecord allocation per
 * iteration.
 */
final class ScalarBypass {
    private ScalarBypass() {}

    /**
     * Try to resolve the inner BDSL target for scalar-ABI bypass.
     * Returns the inner CallTarget if the function is bypassable,
     * or null for AST/effect-handler/partial-application callees
     * that need record-wrapped arguments.
     */
    static CallTarget tryResolve(MezzeFunction fn) {
        if (fn.hasPartialArgs()) return null;
        CallTarget target = fn.getCallTarget();
        if (target instanceof RootCallTarget rct
                && rct.getRootNode() instanceof TrampolineRootNode tramp
                && !tramp.hasTailCalls()
                && tramp.getInnerTarget() != null) {
            return tramp.getInnerTarget();
        }
        return null;
    }
}
