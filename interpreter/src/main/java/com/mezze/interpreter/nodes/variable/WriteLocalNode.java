package com.mezze.interpreter.nodes.variable;

import com.mezze.interpreter.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Write a value to a local variable in the frame.
 *
 * Uses typed frame slot access based on the expected type.
 * Kind constants:
 * - 0 = Long (Int)
 * - 1 = Double (Float)
 * - 2 = Boolean (Bool)
 * - 3 = Object (everything else)
 */
@NodeChild("valueNode")
@NodeField(name = "slot", type = int.class)
@NodeField(name = "kind", type = int.class)
@NodeInfo(shortName = "write")
public abstract class WriteLocalNode extends MezzeNode {

    public static final int KIND_LONG = 0;
    public static final int KIND_DOUBLE = 1;
    public static final int KIND_BOOLEAN = 2;
    public static final int KIND_OBJECT = 3;

    protected abstract int getSlot();
    protected abstract int getKind();

    /**
     * Fast path: Write Long (Int type).
     */
    @Specialization(guards = "kind == 0")
    protected long writeLong(VirtualFrame frame, long value) {
        frame.setLong(getSlot(), value);
        return value;
    }

    /**
     * Fast path: Write Double (Float type).
     */
    @Specialization(guards = "kind == 1")
    protected double writeDouble(VirtualFrame frame, double value) {
        frame.setDouble(getSlot(), value);
        return value;
    }

    /**
     * Fast path: Write Boolean (Bool type).
     */
    @Specialization(guards = "kind == 2")
    protected boolean writeBoolean(VirtualFrame frame, boolean value) {
        frame.setBoolean(getSlot(), value);
        return value;
    }

    /**
     * General path: Write Object (any other type).
     * Returns MezzeUnit for MezzeContinuationSuspended to prevent propagation as early return.
     * This allows suspended continuations to be stored in variables without triggering
     * BlockNode's early return mechanism.
     */
    @Specialization(guards = "kind == 3")
    protected Object writeObject(VirtualFrame frame, Object value) {
        frame.setObject(getSlot(), value);
        return value;
    }

    /**
     * Fallback: When a typed slot (Long/Double/Boolean) receives an unexpected type
     * (e.g., MezzeSuspended from lazy effects), store as Object to prevent ClassCastException.
     */
    @Specialization(replaces = {"writeLong", "writeDouble", "writeBoolean", "writeObject"})
    protected Object writeFallback(VirtualFrame frame, Object value) {
        frame.setObject(getSlot(), value);
        return value;
    }

    /**
     * Create a write node with the appropriate kind.
     */
    public static WriteLocalNode create(MezzeNode valueNode, int slot, int kind) {
        return WriteLocalNodeGen.create(valueNode, slot, kind);
    }

    /**
     * Create a write node for Object slot (default).
     */
    public static WriteLocalNode createObject(MezzeNode valueNode, int slot) {
        return create(valueNode, slot, KIND_OBJECT);
    }

    // ContinuationSuspendedException MUST propagate to HandleNode.
    // Do NOT catch it — it's the mechanism for suspend_with to escape.
    @Override
    public Object execute(VirtualFrame frame) {
        return executeGeneric(frame);
    }

    /**
     * Execute the specialization - implemented by generated code.
     */
    protected abstract Object executeGeneric(VirtualFrame frame);
}
