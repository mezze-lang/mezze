package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.interpreter.bytecode.LazyStubRootNode;
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

        // LOOK THROUGH AN UNINFLATED STUB FIRST. A function that has not been
        // compiled yet presents a `LazyStubRootNode`, not the trampoline the
        // check below wants — and for a HOF callback that is not a rare first
        // call, it is the steady state: a probe in the caller's fallback
        // reported `root=LazyStubRootNode` on 650 of 650 `bytes::map` calls,
        // which is why `bytes::map` ran its callback through the record path at
        // 25 500 ps a byte while a fused `bytes::fold` ran at 155.
        //
        // The same cache-populated-too-early bug was fixed once already, in
        // `CallIndirectScalar1/2`, where it cost every indirect call site in
        // the language its allocation-free path (8.6x on a closure callback,
        // 2.7x on `seq::fold`). This is the HOF-callback sibling of it, left
        // behind because the two gates are different code.
        //
        // The peek REFUSES a function carrying closures — the stub prepends
        // them per call and a bypassing caller would not, so handing out the
        // target would silently drop captures. That makes this strictly
        // additive: a capturing callback takes exactly the path it took
        // before.
        if (target instanceof RootCallTarget stubTarget
                && stubTarget.getRootNode() instanceof LazyStubRootNode stub) {
            CallTarget forwarded = stub.peekForwardTargetNoClosures();
            if (forwarded == null) return null;
            target = forwarded;
        }

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
