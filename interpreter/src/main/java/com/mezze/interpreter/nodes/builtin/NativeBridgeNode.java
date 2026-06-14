package com.mezze.interpreter.nodes.builtin;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Generic DS-Wire fast path: evaluates N positional argument expressions
 * (skipping the MezzeRecord allocation and field-by-name lookup that the
 * NativeDispatch path forces) and calls a precomputed handler with the
 * resulting array.
 *
 * <p>Each wired native op (e.g. {@code __native_map_get},
 * {@code __native_set_contains}) provides a small lambda that unpacks the
 * positional arguments and delegates to the corresponding
 * {@code XxxNatives.method(...)}.
 *
 * <p>Why a single node class instead of one Truffle DSL node per op:
 *   - ~120 ops would mean ~120 hand-written nodes, almost all identical
 *   - The actual work is in the native method body; the dispatch wrapper
 *     just feeds positional args. JIT inlines through the lambda after
 *     monomorphic warmup.
 *
 * <h2>Arity specialization</h2>
 *
 * {@link #create} dispatches to 1-arg and 2-arg specializations
 * ({@link OneArg} / {@link TwoArg}) for the common Mut primitive shape
 * (e.g. {@code __native_mut_int_incr {n, delta}} is 2 args). These
 * specializations pass arguments as individual {@code Object} params
 * rather than via an {@code Object[]}, eliminating the per-call array
 * allocation that otherwise compounds in tight loops. For 3+ args we
 * fall back to {@link NArg} which allocates an {@code Object[]} —
 * that's a small minority of ops and rarely a hot loop.
 *
 * <p>@TruffleBoundary'd handlers (most stdlib ops) keep their boundary
 * inside the lambda; this node itself is partial-evaluable.
 */
@NodeInfo(shortName = "native-bridge")
public abstract class NativeBridgeNode extends MezzeNode {

    /** Handler signature for 1-arg specialization. */
    @FunctionalInterface
    public interface Handler1 {
        Object apply(Object a, IndirectCallNode callNode);
    }

    /** Typed long-in, long-out handler. Skips both boxing and Object[] alloc.
     *  Used by intrinsic-style natives (int::abs, int::neg, int::sign, etc.)
     *  where the call site evaluates a LONG-typed arg and the receiver is
     *  about to consume a primitive long. */
    @FunctionalInterface
    public interface HandlerLongLong {
        long apply(long a);
    }

    /** Typed double-in, double-out handler. Same shape as HandlerLongLong
     *  for float intrinsics (float::abs, float::sqrt, etc.). */
    @FunctionalInterface
    public interface HandlerDoubleDouble {
        double apply(double a);
    }

    /** Handler signature for 2-arg specialization. */
    @FunctionalInterface
    public interface Handler2 {
        Object apply(Object a, Object b, IndirectCallNode callNode);
    }

    /** Handler signature for N-arg generic path. Receives positional args
     *  and (optionally) an IndirectCallNode for HOF callbacks. */
    @FunctionalInterface
    public interface Handler {
        Object apply(Object[] args, IndirectCallNode callNode);
    }

    /**
     * Adapter that lets existing call sites which call
     * {@link #create(MezzeNode[], Handler)} continue to work — the
     * 1-arg and 2-arg specializations re-pack into the legacy
     * Object[] signature so the original (Object[], IndirectCallNode)
     * lambda still applies.
     *
     * <p>Eventually the call sites should migrate to {@link Handler1} /
     * {@link Handler2} to skip the re-pack. For now we keep the existing
     * call shape and only the array allocation is what we're killing.
     */
    public static MezzeNode create(MezzeNode[] argNodes, Handler handler) {
        if (argNodes.length == 1) {
            return new OneArg(argNodes[0], (a, c) -> handler.apply(new Object[] { a }, c), false);
        }
        if (argNodes.length == 2) {
            return new TwoArg(argNodes[0], argNodes[1],
                (a, b, c) -> handler.apply(new Object[] { a, b }, c), false);
        }
        return new NArg(argNodes, handler, false);
    }

    public static MezzeNode createWithCallNode(MezzeNode[] argNodes, Handler handler) {
        if (argNodes.length == 1) {
            return new OneArg(argNodes[0], (a, c) -> handler.apply(new Object[] { a }, c), true);
        }
        if (argNodes.length == 2) {
            return new TwoArg(argNodes[0], argNodes[1],
                (a, b, c) -> handler.apply(new Object[] { a, b }, c), true);
        }
        return new NArg(argNodes, handler, true);
    }

    /**
     * 1-arg specialization: no Object[] allocation, single arg
     * evaluated and passed directly. The most common shape for Mut
     * primitives (e.g. {@code __native_mut_int_read {n}}).
     */
    @NodeInfo(shortName = "native-bridge-1")
    public static final class OneArg extends NativeBridgeNode {
        @Child private MezzeNode arg;
        private final Handler1 handler;
        private final boolean needsCallNode;
        @Child private IndirectCallNode callNode;

        OneArg(MezzeNode arg, Handler1 handler, boolean needsCallNode) {
            this.arg = arg;
            this.handler = handler;
            this.needsCallNode = needsCallNode;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object a = arg.execute(frame);
            if (needsCallNode && callNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                callNode = insert(IndirectCallNode.create());
            }
            return handler.apply(a, callNode);
        }
    }

    /**
     * Typed long→long 1-arg native bridge. Skips all boxing for intrinsic-
     * style ops (int::abs, int::neg, etc.). The arg expression is read via
     * executeLong so the JVM never sees a boxed Long; the handler receives
     * a primitive long and returns a primitive long which we box once for
     * the Object-returning execute contract.
     *
     * Critically, this node also overrides {@link #executeLong} — if the
     * caller is a typed-aware path (DirectAdd, TailContinueScalar, etc.),
     * we hand back the primitive directly with no box at all.
     */
    @NodeInfo(shortName = "native-bridge-l2l")
    public static final class OneArgLongLong extends NativeBridgeNode {
        @Child private MezzeNode arg;
        private final HandlerLongLong handler;

        OneArgLongLong(MezzeNode arg, HandlerLongLong handler) {
            this.arg = arg;
            this.handler = handler;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            // Slow path for Object-returning execute contract — only called
            // when an upstream node consumes via execute, not executeLong.
            try {
                return handler.apply(arg.executeLong(frame));
            } catch (com.oracle.truffle.api.nodes.UnexpectedResultException ure) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeException(
                    "long native bridge got non-long arg: " + ure.getResult());
            }
        }

        @Override
        public long executeLong(VirtualFrame frame)
                throws com.oracle.truffle.api.nodes.UnexpectedResultException {
            return handler.apply(arg.executeLong(frame));
        }
    }

    /**
     * Typed double→double 1-arg native bridge. Same shape as
     * {@link OneArgLongLong} but for float intrinsics (float::abs,
     * float::sqrt, etc.).
     */
    @NodeInfo(shortName = "native-bridge-d2d")
    public static final class OneArgDoubleDouble extends NativeBridgeNode {
        @Child private MezzeNode arg;
        private final HandlerDoubleDouble handler;

        OneArgDoubleDouble(MezzeNode arg, HandlerDoubleDouble handler) {
            this.arg = arg;
            this.handler = handler;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            try {
                return handler.apply(arg.executeDouble(frame));
            } catch (com.oracle.truffle.api.nodes.UnexpectedResultException ure) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeException(
                    "double native bridge got non-double arg: " + ure.getResult());
            }
        }

        @Override
        public double executeDouble(VirtualFrame frame)
                throws com.oracle.truffle.api.nodes.UnexpectedResultException {
            return handler.apply(arg.executeDouble(frame));
        }
    }

    /** Build a typed long→long bridge node. */
    public static MezzeNode createLongLong(MezzeNode arg, HandlerLongLong handler) {
        return new OneArgLongLong(arg, handler);
    }

    /** Build a typed double→double bridge node. */
    public static MezzeNode createDoubleDouble(MezzeNode arg, HandlerDoubleDouble handler) {
        return new OneArgDoubleDouble(arg, handler);
    }

    /**
     * 2-arg specialization: no Object[] allocation, two args
     * evaluated and passed directly. Used by the typical Mut state-
     * update shape (e.g. {@code __native_mut_int_incr {n, delta}} or
     * {@code __native_mut_int_write {n, v}}).
     */
    @NodeInfo(shortName = "native-bridge-2")
    public static final class TwoArg extends NativeBridgeNode {
        @Child private MezzeNode arg0;
        @Child private MezzeNode arg1;
        private final Handler2 handler;
        private final boolean needsCallNode;
        @Child private IndirectCallNode callNode;

        TwoArg(MezzeNode arg0, MezzeNode arg1, Handler2 handler, boolean needsCallNode) {
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.handler = handler;
            this.needsCallNode = needsCallNode;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object a = arg0.execute(frame);
            Object b = arg1.execute(frame);
            if (needsCallNode && callNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                callNode = insert(IndirectCallNode.create());
            }
            return handler.apply(a, b, callNode);
        }
    }

    /**
     * N-arg generic fallback. Allocates an Object[] per call — used
     * only for 3+ args (e.g. {@code __native_mut_bytes_copy} or HOFs
     * with multiple args). Tight inner-loop ops shouldn't end up here
     * — if they do, add another arity-N specialization.
     */
    @NodeInfo(shortName = "native-bridge-n")
    public static final class NArg extends NativeBridgeNode {
        @Children private final MezzeNode[] argNodes;
        private final Handler handler;
        private final boolean needsCallNode;
        @Child private IndirectCallNode callNode;

        NArg(MezzeNode[] argNodes, Handler handler, boolean needsCallNode) {
            this.argNodes = argNodes;
            this.handler = handler;
            this.needsCallNode = needsCallNode;
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
}
