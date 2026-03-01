package com.mezze.interpreter.v2.nodes;

import com.mezze.interpreter.v2.runtime.MezzeLanguage;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Root node for Mezze functions - OPTIMIZED FOR JIT.
 *
 * Key optimizations:
 * 1. Arguments are bound to typed frame slots on entry
 * 2. @ExplodeLoop unrolls the argument binding loop
 * 3. Slot types are @CompilationFinal for constant folding
 * 4. @ReportPolymorphism enables JIT feedback for hot paths
 *
 * The FrameDescriptor uses typed slots based on inferredType:
 * - FrameSlotKind.Long for Int parameters
 * - FrameSlotKind.Double for Float parameters
 * - FrameSlotKind.Boolean for Bool parameters
 * - FrameSlotKind.Object for everything else
 */
@NodeInfo(language = "mezze", description = "Mezze function root")
@ReportPolymorphism
public final class MezzeRootNode extends RootNode {

    private final String name;

    @Child private MezzeNode bodyNode;

    // Number of closure variables (prepended to arguments)
    @CompilationFinal
    private final int closureCount;

    // Number of function parameters
    @CompilationFinal
    private final int parameterCount;

    // Slot types for typed binding (0=Long, 1=Double, 2=Boolean, 3=Object)
    // Length = closureCount + parameterCount
    @CompilationFinal(dimensions = 1)
    private final int[] slotTypes;

    public MezzeRootNode(
            MezzeLanguage language,
            FrameDescriptor frameDescriptor,
            MezzeNode bodyNode,
            String name,
            int closureCount,
            int parameterCount,
            int[] slotTypes
    ) {
        super(language, frameDescriptor);
        this.bodyNode = bodyNode;
        this.name = name;
        this.closureCount = closureCount;
        this.parameterCount = parameterCount;
        this.slotTypes = slotTypes != null ? slotTypes : new int[0];
    }

    /**
     * Constructor with slot types auto-detected as Object.
     */
    public MezzeRootNode(
            MezzeLanguage language,
            FrameDescriptor frameDescriptor,
            MezzeNode bodyNode,
            String name,
            int closureCount,
            int parameterCount
    ) {
        this(language, frameDescriptor, bodyNode, name, closureCount, parameterCount,
             createDefaultSlotTypes(closureCount + parameterCount));
    }

    /**
     * Simplified constructor for testing.
     */
    public MezzeRootNode(
            MezzeLanguage language,
            FrameDescriptor frameDescriptor,
            MezzeNode bodyNode
    ) {
        this(language, frameDescriptor, bodyNode, "anonymous", 0, 0, new int[0]);
    }

    private static int[] createDefaultSlotTypes(int count) {
        int[] types = new int[count];
        for (int i = 0; i < count; i++) {
            types[i] = 3; // KIND_OBJECT
        }
        return types;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Only bind arguments to frame slots if we have slot types defined
        // This allows ReadArgumentNode to be used for direct argument access
        if (slotTypes.length > 0) {
            Object[] args = frame.getArguments();
            int totalSlots = closureCount + parameterCount;

            // @ExplodeLoop unrolls this - each slot binding becomes a single instruction
            for (int i = 0; i < slotTypes.length && i < args.length && i < totalSlots; i++) {
                Object arg = args[i];
                int slotType = slotTypes[i];

                // Switch on @CompilationFinal slotType - JIT eliminates the switch
                switch (slotType) {
                    case 0 -> { // KIND_LONG
                        if (arg instanceof Long l) {
                            frame.setLong(i, l);
                        } else {
                            CompilerDirectives.transferToInterpreterAndInvalidate();
                            frame.setObject(i, arg);
                        }
                    }
                    case 1 -> { // KIND_DOUBLE
                        if (arg instanceof Double d) {
                            frame.setDouble(i, d);
                        } else if (arg instanceof Long l) {
                            frame.setDouble(i, l.doubleValue());
                        } else {
                            CompilerDirectives.transferToInterpreterAndInvalidate();
                            frame.setObject(i, arg);
                        }
                    }
                    case 2 -> { // KIND_BOOLEAN
                        if (arg instanceof Boolean b) {
                            frame.setBoolean(i, b);
                        } else {
                            CompilerDirectives.transferToInterpreterAndInvalidate();
                            frame.setObject(i, arg);
                        }
                    }
                    default -> frame.setObject(i, arg); // KIND_OBJECT
                }
            }
        }

        Object result = bodyNode.execute(frame);

        // Clear frame slots to help escape analysis
        // This signals to the JIT that these values don't escape
        // Only clear if we actually have slots
        if (slotTypes.length > 0) {
            clearFrameSlots(frame);
        }

        return result;
    }

    /**
     * Clear frame slots after execution to help escape analysis.
     * This allows the JIT to determine that intermediate values
     * don't escape, enabling better optimizations like:
     * - Stack allocation instead of heap
     * - Value elimination
     * - Reduced GC pressure
     */
    @ExplodeLoop
    private void clearFrameSlots(VirtualFrame frame) {
        // Only clear slots that we actually bound
        for (int i = 0; i < slotTypes.length; i++) {
            frame.clear(i);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "MezzeRootNode[" + name + "]";
    }

    public int getClosureCount() {
        return closureCount;
    }

    public int getParameterCount() {
        return parameterCount;
    }

    /**
     * Get the body node for inlining.
     */
    public MezzeNode getBodyNode() {
        return bodyNode;
    }
}
