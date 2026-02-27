package com.mezze.interpreter.v2.nodes.variable;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Read a local variable from the frame.
 *
 * Uses typed frame slot access based on the expected type.
 * Kind constants:
 * - 0 = Long (Int)
 * - 1 = Double (Float)
 * - 2 = Boolean (Bool)
 * - 3 = Object (everything else)
 */
@NodeField(name = "slot", type = int.class)
@NodeField(name = "kind", type = int.class)
@NodeInfo(shortName = "read")
public abstract class ReadLocalNode extends MezzeNode {

    public static final int KIND_LONG = 0;
    public static final int KIND_DOUBLE = 1;
    public static final int KIND_BOOLEAN = 2;
    public static final int KIND_OBJECT = 3;

    protected abstract int getSlot();
    protected abstract int getKind();

    /**
     * Fast path: Read Long (Int type).
     */
    @Specialization(guards = "kind == 0")
    protected long readLong(VirtualFrame frame) {
        return frame.getLong(getSlot());
    }

    /**
     * Fast path: Read Double (Float type).
     */
    @Specialization(guards = "kind == 1")
    protected double readDouble(VirtualFrame frame) {
        return frame.getDouble(getSlot());
    }

    /**
     * Fast path: Read Boolean (Bool type).
     */
    @Specialization(guards = "kind == 2")
    protected boolean readBoolean(VirtualFrame frame) {
        return frame.getBoolean(getSlot());
    }

    /**
     * General path: Read Object (any other type).
     */
    @Specialization(guards = "kind == 3")
    protected Object readObject(VirtualFrame frame) {
        return frame.getObject(getSlot());
    }

    /**
     * Create a read node with the appropriate kind.
     */
    public static ReadLocalNode create(int slot, int kind) {
        return ReadLocalNodeGen.create(slot, kind);
    }

    /**
     * Create a read node for Object slot (default).
     */
    public static ReadLocalNode createObject(int slot) {
        return create(slot, KIND_OBJECT);
    }
}
