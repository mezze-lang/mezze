package com.mezze.interpreter.nodes.continuation;

import com.mezze.interpreter.bytecode.LazyStubRootNode;
import com.mezze.interpreter.bytecode.MezzeBytecodeRootNode;
import com.mezze.interpreter.bytecode.TrampolineRootNode;
import com.mezze.interpreter.values.MezzeFunction;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * C4: may a function VALUE suspend when called? Read off its root's stamped
 * `FunctionIR.maySuspend` through whatever wraps it (trampoline, lazy stub).
 * Unknown — an AST root, a stub not yet inflated — is `true`: the resumable path is
 * always correct, only slower. A native reads this once per call, not per element.
 */
public final class SuspendFacts {
    private SuspendFacts() {}

    @TruffleBoundary
    public static boolean mayFunctionSuspend(MezzeFunction fn) {
        return mayTargetSuspend(fn.getCallTarget(), 0);
    }

    private static boolean mayTargetSuspend(CallTarget target, int depth) {
        if (!(target instanceof RootCallTarget rct) || depth > 4) return true;
        RootNode root = rct.getRootNode();
        if (root instanceof MezzeBytecodeRootNode r) return r.maySuspend;
        if (root instanceof TrampolineRootNode t) {
            RootCallTarget inner = t.peekInnerTarget();
            return inner == null || mayTargetSuspend(inner, depth + 1);
        }
        if (root instanceof LazyStubRootNode s) {
            CallTarget inflated = s.getInflatedTarget();
            return inflated == null || mayTargetSuspend(inflated, depth + 1);
        }
        return true;
    }
}
