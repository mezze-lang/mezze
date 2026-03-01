package com.mezze.interpreter.v2.nodes.function;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * INLINED ability call node - MAXIMUM OPTIMIZATION.
 *
 * This is the ultimate optimization for monomorphic ability calls:
 * Instead of calling through a function, we INLINE the operation's
 * body directly into the call site.
 *
 * Architecture:
 * - The operation's body AST is cloned and embedded as a @Child
 * - Arguments are bound to local frame slots before executing the body
 * - No function call overhead at all
 *
 * This is used when:
 * 1. The impl is fully monomorphized (content-hash resolved)
 * 2. The operation's body is small enough to inline
 * 3. The operation has no closures (pure function of arguments)
 *
 * After JIT: The operation executes as if it were written inline at the call site.
 *
 * Example transformation:
 *   // Before: Num.add(a, b)
 *   AbilityCallNode → getField → DirectCallNode → RootNode → body
 *
 *   // After: InlinedAbilityCallNode
 *   bind(slot[0], a); bind(slot[1], b); execute(AddNode)
 */
@NodeInfo(shortName = "inlined-ability")
public final class InlinedAbilityCallNode extends MezzeNode {

    // The inlined operation body
    @Child
    private MezzeNode bodyNode;

    // Argument expression nodes
    @Children
    private final MezzeNode[] argumentNodes;

    @CompilationFinal
    private final int argumentCount;

    // Frame slots where arguments should be bound
    @CompilationFinal(dimensions = 1)
    private final int[] argumentSlots;

    // Slot types for typed binding (0=Long, 1=Double, 2=Boolean, 3=Object)
    @CompilationFinal(dimensions = 1)
    private final int[] slotTypes;

    /**
     * Create an inlined ability call.
     *
     * @param bodyNode The cloned operation body node
     * @param argumentNodes Argument expression nodes
     * @param argumentSlots Frame slots to bind arguments to
     * @param slotTypes Type of each slot for typed binding
     */
    public InlinedAbilityCallNode(
            MezzeNode bodyNode,
            MezzeNode[] argumentNodes,
            int[] argumentSlots,
            int[] slotTypes
    ) {
        this.bodyNode = bodyNode;
        this.argumentNodes = argumentNodes;
        this.argumentCount = argumentNodes.length;
        this.argumentSlots = argumentSlots;
        this.slotTypes = slotTypes;
    }

    /**
     * Create with all Object slots (default).
     */
    public static InlinedAbilityCallNode createAllObjects(
            MezzeNode bodyNode,
            MezzeNode[] argumentNodes,
            int[] argumentSlots
    ) {
        int[] types = new int[argumentSlots.length];
        for (int i = 0; i < types.length; i++) {
            types[i] = 3; // KIND_OBJECT
        }
        return new InlinedAbilityCallNode(bodyNode, argumentNodes, argumentSlots, types);
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Evaluate and bind arguments to frame slots
        for (int i = 0; i < argumentCount; i++) {
            Object value = argumentNodes[i].execute(frame);
            int slot = argumentSlots[i];
            int slotType = slotTypes[i];

            // Typed binding - switch eliminated by JIT
            switch (slotType) {
                case 0 -> frame.setLong(slot, (long) value);
                case 1 -> frame.setDouble(slot, (double) value);
                case 2 -> frame.setBoolean(slot, (boolean) value);
                default -> frame.setObject(slot, value);
            }
        }

        // Execute the inlined body
        return bodyNode.execute(frame);
    }
}
