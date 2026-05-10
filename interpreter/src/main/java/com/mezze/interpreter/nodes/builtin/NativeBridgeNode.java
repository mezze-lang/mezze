package com.mezze.interpreter.nodes.builtin;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.function.Function;

/**
 * Generic DS-Wire fast path: evaluates N positional argument expressions
 * (skipping the MezzeRecord allocation and field-by-name lookup that the
 * NativeDispatch path forces) and calls a precomputed handler with the
 * resulting array.
 *
 * Each wired native op (e.g. {@code __native_map_get}, {@code __native_set_contains})
 * provides a small lambda that unpacks the Object[] into typed positional
 * arguments and delegates to the corresponding {@code XxxNatives.method(...)}.
 *
 * Why a single node class instead of one Truffle DSL node per op:
 *   - 89 ops would mean 89 hand-written nodes, almost all identical
 *   - The actual work is in the native method body; the dispatch wrapper
 *     just feeds positional args. JIT inlines through the lambda after
 *     monomorphic warmup.
 *
 * @TruffleBoundary'd handlers (most stdlib ops) keep their boundary
 * inside the lambda; this node itself is partial-evaluable.
 */
@NodeInfo(shortName = "native-bridge")
public final class NativeBridgeNode extends MezzeNode {

    /** Handler signature: receives positional args, returns result.
     *  May also receive an IndirectCallNode for HOF callbacks (passed as
     *  the LAST arg slot if needsCallNode is true). */
    @FunctionalInterface
    public interface Handler {
        Object apply(Object[] args, IndirectCallNode callNode);
    }

    @Children private final MezzeNode[] argNodes;
    private final Handler handler;
    private final boolean needsCallNode;

    @Child private IndirectCallNode callNode;

    public NativeBridgeNode(MezzeNode[] argNodes, Handler handler, boolean needsCallNode) {
        this.argNodes = argNodes;
        this.handler = handler;
        this.needsCallNode = needsCallNode;
    }

    public static NativeBridgeNode create(MezzeNode[] argNodes, Handler handler) {
        return new NativeBridgeNode(argNodes, handler, false);
    }

    public static NativeBridgeNode createWithCallNode(MezzeNode[] argNodes, Handler handler) {
        return new NativeBridgeNode(argNodes, handler, true);
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object[] args = new Object[argNodes.length];
        for (int i = 0; i < argNodes.length; i++) {
            args[i] = argNodes[i].execute(frame);
        }
        if (needsCallNode && callNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            callNode = insert(IndirectCallNode.create());
        }
        return handler.apply(args, callNode);
    }
}
