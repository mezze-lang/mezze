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

    /** Can ANY park reach this thread right now? False outside every cont-capable perform
     *  boundary — which is where ordinary programs run — so the checks below cost nothing there. */
    public static boolean parkPossible() {
        return NativeSuspension.ENABLED && HandlerChainRegistry.parkPossible();
    }

    /** The route decision for a native that runs {@code fn}: a park is possible here AND the
     *  function may suspend by its effect row. */
    public static boolean mayPark(MezzeFunction fn) {
        return parkPossible() && mayFunctionSuspend(fn);
    }

    @TruffleBoundary
    public static boolean mayFunctionSuspend(MezzeFunction fn) {
        return mayTargetSuspend(fn.getCallTarget(), 0);
    }

    private static boolean mayTargetSuspend(CallTarget target, int depth) {
        if (!(target instanceof RootCallTarget rct) || depth > 4) return true;
        RootNode root = rct.getRootNode();
        if (root instanceof MezzeBytecodeRootNode r) return r.maySuspend;
        // BUG 71: an un-inflated stub or an unattached trampoline answers from its own
        // stamp instead of guessing "may suspend" — a guess that fails a `replaces`
        // guard once demotes that node for the rest of the process.
        if (root instanceof TrampolineRootNode t) {
            RootCallTarget inner = t.peekInnerTarget();
            return inner == null ? t.maySuspend : mayTargetSuspend(inner, depth + 1);
        }
        if (root instanceof LazyStubRootNode s) {
            CallTarget inflated = s.getInflatedTarget();
            return inflated == null ? s.maySuspend : mayTargetSuspend(inflated, depth + 1);
        }
        return true;
    }
}
