package com.mezze.interpreter.v2.nodes.mutation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MutableInt;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Operations on MutableInt - read, write, incr, decr.
 *
 * These are simple field access operations that should be fully inlined.
 * No @TruffleBoundary - these are the fast paths we want JIT-optimized.
 */
public final class MutIntOpsNode {

    private MutIntOpsNode() {} // Utility class

    /**
     * Read current value from MutableInt.
     */
    @NodeInfo(shortName = "mut-int-read")
    public static final class ReadNode extends MezzeNode {
        @Child private MezzeNode intNode;

        public ReadNode(MezzeNode intNode) {
            this.intNode = intNode;
        }

        public static ReadNode create(MezzeNode intNode) {
            return new ReadNode(intNode);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object n = intNode.execute(frame);
            if (n instanceof MutableInt mutableInt) {
                return mutableInt.get();
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("read_int expects MutableInt, got: " + n.getClass());
        }

        @Override
        public long executeLong(VirtualFrame frame) {
            Object n = intNode.execute(frame);
            if (n instanceof MutableInt mutableInt) {
                return mutableInt.get();
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("read_int expects MutableInt, got: " + n.getClass());
        }
    }

    /**
     * Write value to MutableInt.
     */
    @NodeInfo(shortName = "mut-int-write")
    public static final class WriteNode extends MezzeNode {
        @Child private MezzeNode intNode;
        @Child private MezzeNode valueNode;

        public WriteNode(MezzeNode intNode, MezzeNode valueNode) {
            this.intNode = intNode;
            this.valueNode = valueNode;
        }

        public static WriteNode create(MezzeNode intNode, MezzeNode valueNode) {
            return new WriteNode(intNode, valueNode);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object n = intNode.execute(frame);
            if (n instanceof MutableInt mutableInt) {
                Object v = valueNode.execute(frame);
                if (v instanceof Long longValue) {
                    mutableInt.set(longValue);
                    return MezzeRecord.empty();
                }
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeException("write_int value must be Int, got: " + v.getClass());
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("write_int expects MutableInt, got: " + n.getClass());
        }
    }

    /**
     * Increment MutableInt by delta.
     */
    @NodeInfo(shortName = "mut-int-incr")
    public static final class IncrNode extends MezzeNode {
        @Child private MezzeNode intNode;
        @Child private MezzeNode deltaNode;

        public IncrNode(MezzeNode intNode, MezzeNode deltaNode) {
            this.intNode = intNode;
            this.deltaNode = deltaNode;
        }

        public static IncrNode create(MezzeNode intNode, MezzeNode deltaNode) {
            return new IncrNode(intNode, deltaNode);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object n = intNode.execute(frame);
            if (n instanceof MutableInt mutableInt) {
                Object delta = deltaNode.execute(frame);
                if (delta instanceof Long longDelta) {
                    mutableInt.add(longDelta);
                    return MezzeRecord.empty();
                }
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeException("incr delta must be Int, got: " + delta.getClass());
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("incr expects MutableInt, got: " + n.getClass());
        }
    }

    /**
     * Decrement MutableInt by delta.
     */
    @NodeInfo(shortName = "mut-int-decr")
    public static final class DecrNode extends MezzeNode {
        @Child private MezzeNode intNode;
        @Child private MezzeNode deltaNode;

        public DecrNode(MezzeNode intNode, MezzeNode deltaNode) {
            this.intNode = intNode;
            this.deltaNode = deltaNode;
        }

        public static DecrNode create(MezzeNode intNode, MezzeNode deltaNode) {
            return new DecrNode(intNode, deltaNode);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object n = intNode.execute(frame);
            if (n instanceof MutableInt mutableInt) {
                Object delta = deltaNode.execute(frame);
                if (delta instanceof Long longDelta) {
                    mutableInt.subtract(longDelta);
                    return MezzeRecord.empty();
                }
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeException("decr delta must be Int, got: " + delta.getClass());
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("decr expects MutableInt, got: " + n.getClass());
        }
    }
}
