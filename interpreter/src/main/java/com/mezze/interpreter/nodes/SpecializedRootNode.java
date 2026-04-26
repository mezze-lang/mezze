package com.mezze.interpreter.nodes;

import com.mezze.interpreter.runtime.MezzeLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * SPECIALIZED root nodes for common function signatures.
 *
 * These eliminate the argument binding loop in MezzeRootNode by
 * generating fixed-arity, fixed-type root nodes.
 *
 * Benefits:
 * 1. No loop unrolling needed - arguments are bound directly
 * 2. No switch on slot type - types are compile-time constants
 * 3. Fewer instructions in the hot path
 *
 * After JIT: Direct slot assignment, fully constant-folded.
 */
public final class SpecializedRootNode {

    private SpecializedRootNode() {} // Utility class

    /**
     * Zero-argument function root.
     */
    @NodeInfo(language = "mezze", description = "Zero-arg root")
    public static final class ZeroArg extends RootNode {
        private final String name;
        @Child private MezzeNode bodyNode;

        public ZeroArg(MezzeLanguage language, FrameDescriptor frameDescriptor,
                       MezzeNode bodyNode, String name) {
            super(language, frameDescriptor);
            this.bodyNode = bodyNode;
            this.name = name;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return bodyNode.execute(frame);
        }

        @Override
        public String getName() { return name; }
    }

    /**
     * Single Int argument root.
     */
    @NodeInfo(language = "mezze", description = "One-Int root")
    public static final class OneInt extends RootNode {
        private final String name;
        @Child private MezzeNode bodyNode;

        public OneInt(MezzeLanguage language, FrameDescriptor frameDescriptor,
                      MezzeNode bodyNode, String name) {
            super(language, frameDescriptor);
            this.bodyNode = bodyNode;
            this.name = name;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            frame.setLong(0, (long) args[0]);
            return bodyNode.execute(frame);
        }

        @Override
        public String getName() { return name; }
    }

    /**
     * Two Int arguments root.
     */
    @NodeInfo(language = "mezze", description = "Two-Int root")
    public static final class TwoInt extends RootNode {
        private final String name;
        @Child private MezzeNode bodyNode;

        public TwoInt(MezzeLanguage language, FrameDescriptor frameDescriptor,
                      MezzeNode bodyNode, String name) {
            super(language, frameDescriptor);
            this.bodyNode = bodyNode;
            this.name = name;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            frame.setLong(0, (long) args[0]);
            frame.setLong(1, (long) args[1]);
            return bodyNode.execute(frame);
        }

        @Override
        public String getName() { return name; }
    }

    /**
     * Three Int arguments root.
     */
    @NodeInfo(language = "mezze", description = "Three-Int root")
    public static final class ThreeInt extends RootNode {
        private final String name;
        @Child private MezzeNode bodyNode;

        public ThreeInt(MezzeLanguage language, FrameDescriptor frameDescriptor,
                        MezzeNode bodyNode, String name) {
            super(language, frameDescriptor);
            this.bodyNode = bodyNode;
            this.name = name;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            frame.setLong(0, (long) args[0]);
            frame.setLong(1, (long) args[1]);
            frame.setLong(2, (long) args[2]);
            return bodyNode.execute(frame);
        }

        @Override
        public String getName() { return name; }
    }

    /**
     * Single Object argument root.
     */
    @NodeInfo(language = "mezze", description = "One-Object root")
    public static final class OneObject extends RootNode {
        private final String name;
        @Child private MezzeNode bodyNode;

        public OneObject(MezzeLanguage language, FrameDescriptor frameDescriptor,
                         MezzeNode bodyNode, String name) {
            super(language, frameDescriptor);
            this.bodyNode = bodyNode;
            this.name = name;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            frame.setObject(0, args[0]);
            return bodyNode.execute(frame);
        }

        @Override
        public String getName() { return name; }
    }

    /**
     * Two Object arguments root.
     */
    @NodeInfo(language = "mezze", description = "Two-Object root")
    public static final class TwoObject extends RootNode {
        private final String name;
        @Child private MezzeNode bodyNode;

        public TwoObject(MezzeLanguage language, FrameDescriptor frameDescriptor,
                         MezzeNode bodyNode, String name) {
            super(language, frameDescriptor);
            this.bodyNode = bodyNode;
            this.name = name;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            frame.setObject(0, args[0]);
            frame.setObject(1, args[1]);
            return bodyNode.execute(frame);
        }

        @Override
        public String getName() { return name; }
    }

    /**
     * Int + Object arguments root (common for list operations).
     */
    @NodeInfo(language = "mezze", description = "Int-Object root")
    public static final class IntObject extends RootNode {
        private final String name;
        @Child private MezzeNode bodyNode;

        public IntObject(MezzeLanguage language, FrameDescriptor frameDescriptor,
                         MezzeNode bodyNode, String name) {
            super(language, frameDescriptor);
            this.bodyNode = bodyNode;
            this.name = name;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            frame.setLong(0, (long) args[0]);
            frame.setObject(1, args[1]);
            return bodyNode.execute(frame);
        }

        @Override
        public String getName() { return name; }
    }

    /**
     * Object + Int arguments root.
     */
    @NodeInfo(language = "mezze", description = "Object-Int root")
    public static final class ObjectInt extends RootNode {
        private final String name;
        @Child private MezzeNode bodyNode;

        public ObjectInt(MezzeLanguage language, FrameDescriptor frameDescriptor,
                         MezzeNode bodyNode, String name) {
            super(language, frameDescriptor);
            this.bodyNode = bodyNode;
            this.name = name;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            frame.setObject(0, args[0]);
            frame.setLong(1, (long) args[1]);
            return bodyNode.execute(frame);
        }

        @Override
        public String getName() { return name; }
    }

    /**
     * Two Double arguments root.
     */
    @NodeInfo(language = "mezze", description = "Two-Double root")
    public static final class TwoDouble extends RootNode {
        private final String name;
        @Child private MezzeNode bodyNode;

        public TwoDouble(MezzeLanguage language, FrameDescriptor frameDescriptor,
                         MezzeNode bodyNode, String name) {
            super(language, frameDescriptor);
            this.bodyNode = bodyNode;
            this.name = name;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            frame.setDouble(0, (double) args[0]);
            frame.setDouble(1, (double) args[1]);
            return bodyNode.execute(frame);
        }

        @Override
        public String getName() { return name; }
    }

    /**
     * Factory method to create a specialized root node based on slot types.
     *
     * @param slotTypes Array of slot types (0=Long, 1=Double, 2=Boolean, 3=Object)
     * @return A specialized root node, or null if no specialization exists
     */
    public static RootNode createSpecialized(
            MezzeLanguage language,
            FrameDescriptor frameDescriptor,
            MezzeNode bodyNode,
            String name,
            int[] slotTypes
    ) {
        if (slotTypes == null || slotTypes.length == 0) {
            return new ZeroArg(language, frameDescriptor, bodyNode, name);
        }

        // Check for common patterns
        if (slotTypes.length == 1) {
            return switch (slotTypes[0]) {
                case 0 -> new OneInt(language, frameDescriptor, bodyNode, name);
                default -> new OneObject(language, frameDescriptor, bodyNode, name);
            };
        }

        if (slotTypes.length == 2) {
            if (slotTypes[0] == 0 && slotTypes[1] == 0) {
                return new TwoInt(language, frameDescriptor, bodyNode, name);
            }
            if (slotTypes[0] == 1 && slotTypes[1] == 1) {
                return new TwoDouble(language, frameDescriptor, bodyNode, name);
            }
            if (slotTypes[0] == 0 && slotTypes[1] == 3) {
                return new IntObject(language, frameDescriptor, bodyNode, name);
            }
            if (slotTypes[0] == 3 && slotTypes[1] == 0) {
                return new ObjectInt(language, frameDescriptor, bodyNode, name);
            }
            if (slotTypes[0] == 3 && slotTypes[1] == 3) {
                return new TwoObject(language, frameDescriptor, bodyNode, name);
            }
        }

        if (slotTypes.length == 3 && slotTypes[0] == 0 && slotTypes[1] == 0 && slotTypes[2] == 0) {
            return new ThreeInt(language, frameDescriptor, bodyNode, name);
        }

        // No specialization - return null to use generic MezzeRootNode
        return null;
    }
}
