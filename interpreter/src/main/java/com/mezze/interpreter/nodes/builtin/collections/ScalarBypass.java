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
     *
     * <p>Functions with hidden dict params (e.g. {@code __dict_add__}) are NOT
     * bypassable because the scalar path skips the trampoline's dict-fallback
     * resolution, causing AIOOB when the inner BDSL target expects more args
     * than the caller supplies.
     */
    static CallTarget tryResolve(MezzeFunction fn) {
        if (fn.hasPartialArgs()) return null;
        CallTarget target = fn.getCallTarget();
        if (target instanceof RootCallTarget rct
                && rct.getRootNode() instanceof TrampolineRootNode tramp
                && !tramp.hasTailCalls()
                && tramp.getInnerTarget() != null
                && !hasDictParams(tramp)) {
            return tramp.getInnerTarget();
        }
        return null;
    }

    private static boolean hasDictParams(TrampolineRootNode tramp) {
        String[] names = tramp.getParameterNames();
        if (names == null) return false;
        for (String name : names) {
            if (com.mezze.language.ast.MagicParams.INSTANCE.isDict(name)) return true;
        }
        return false;
    }
}
