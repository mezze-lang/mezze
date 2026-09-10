package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.language.ast.ScopedEffectConstants;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;

/**
 * Path D8 scope-hook plumbing for BDSL Handle. Mirrors the same helpers
 * in {@link com.mezze.interpreter.nodes.builtin.EffectHandlerDriver} so
 * handlers with {@code initialize_scope} / {@code finalize_scope} (e.g.
 * {@code SeededRandom}, {@code STM}) work identically under BDSL.
 *
 * <p>All methods are {@link TruffleBoundary}-safe — scope hooks fire once
 * per Handle enter/exit, not in the hot per-op path.
 */
final class D8ScopeHooks {
    private D8ScopeHooks() {}

    @TruffleBoundary
    static Object invokeInitializeScope(MezzeFunction initFn) {
        Object[] cv = initFn.getClosureValues();
        Object[] args = new Object[cv.length + 1];
        System.arraycopy(cv, 0, args, 0, cv.length);
        args[cv.length] = MezzeRecord.empty();
        return IndirectCallNode.getUncached().call(initFn.getCallTarget(), args);
    }

    @TruffleBoundary
    static void invokeFinalizeScope(MezzeFunction finFn, Object scope) {
        Object[] cv = finFn.getClosureValues();
        Object[] args = new Object[cv.length + 1];
        System.arraycopy(cv, 0, args, 0, cv.length);
        args[cv.length] = MezzeRecord.fromFields(
            new String[]{ScopedEffectConstants.SCOPE},
            new Object[]{scope == null ? MezzeRecord.empty() : scope}
        );
        try {
            IndirectCallNode.getUncached().call(finFn.getCallTarget(), args);
        } catch (Throwable ignored) {
            // finalize_scope failures must not mask the perform's result.
        }
    }

    @TruffleBoundary
    static Object installScope(Node dispatchNode, Object scope) {
        return com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.installScopeVar(scope);
    }

    @TruffleBoundary
    static void restoreScope(Node dispatchNode, Object prev) {
        com.mezze.interpreter.nodes.continuation.HandlerChainRegistry.restoreScopeVar(
            prev != null ? prev : MezzeRecord.empty()
        );
    }
}
