package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.runtime.MezzeLanguage;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Closure-prepending adapter root — the closure half of what
 * {@link LazyStubRootNode} did. Baked at MezzeFunction construction time
 * when the wrapped delegate is a bare BDSL trampoline root and closures
 * are non-empty. Every call prepends the cached closures to
 * {@code frame.getArguments()} before forwarding to the delegate.
 *
 * <p>Mirrors {@link LazyStubRootNode#prependClosures} (lines 98–106) and
 * {@link LazyStubRootNode#execute} (lines 84–96) verbatim — this class
 * exists so #391 can delete LSRN's deferred-resolution baggage while
 * keeping the closure-prepend contract that callers depend on.
 */
public final class ClosureAdaptRootNode extends RootNode {

    @CompilerDirectives.CompilationFinal(dimensions = 1)
    private final Object[] closures;

    @Child
    private DirectCallNode forward;

    public ClosureAdaptRootNode(MezzeLanguage lang, CallTarget delegate, Object[] closures) {
        super(lang, FrameDescriptor.newBuilder().build());
        this.closures = closures != null ? closures : new Object[0];
        this.forward = DirectCallNode.create(delegate);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return forward.call(prependClosures(frame.getArguments()));
    }

    private Object[] prependClosures(Object[] rawArgs) {
        int closureLen = closures.length;
        if (closureLen == 0) return rawArgs;
        Object[] combined = new Object[closureLen + rawArgs.length];
        System.arraycopy(closures, 0, combined, 0, closureLen);
        System.arraycopy(rawArgs, 0, combined, closureLen, rawArgs.length);
        return combined;
    }
}
