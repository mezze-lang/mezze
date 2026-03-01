package com.mezze.interpreter.v2.nodes.variable;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Read a local variable from the frame - FULLY JIT-COMPILABLE.
 *
 * Uses typed frame slot access based on the expected type.
 * The slot index and kind are @CompilationFinal, so after JIT:
 * - No dispatch overhead
 * - Direct typed access (getLong/getDouble/etc.)
 * - Primitives stay unboxed
 *
 * Kind constants:
 * - 0 = Long (Int)
 * - 1 = Double (Float)
 * - 2 = Boolean (Bool)
 * - 3 = Object (everything else)
 */
@NodeInfo(shortName = "read")
public final class ReadLocalNode extends MezzeNode {

    public static final int KIND_LONG = 0;
    public static final int KIND_DOUBLE = 1;
    public static final int KIND_BOOLEAN = 2;
    public static final int KIND_OBJECT = 3;

    @CompilationFinal
    private final int slot;

    @CompilationFinal
    private final int kind;

    public ReadLocalNode(int slot, int kind) {
        this.slot = slot;
        this.kind = kind;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // The switch on kind is @CompilationFinal, so JIT will eliminate it
        return switch (kind) {
            case KIND_LONG -> frame.getLong(slot);
            case KIND_DOUBLE -> frame.getDouble(slot);
            case KIND_BOOLEAN -> frame.getBoolean(slot);
            default -> frame.getObject(slot);
        };
    }

    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        if (kind == KIND_LONG) {
            return frame.getLong(slot);
        }
        throw new UnexpectedResultException(execute(frame));
    }

    @Override
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        if (kind == KIND_DOUBLE) {
            return frame.getDouble(slot);
        }
        if (kind == KIND_LONG) {
            return frame.getLong(slot);
        }
        throw new UnexpectedResultException(execute(frame));
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        if (kind == KIND_BOOLEAN) {
            return frame.getBoolean(slot);
        }
        throw new UnexpectedResultException(execute(frame));
    }

    /**
     * Create a read node for Long (Int) slot.
     */
    public static ReadLocalNode createLong(int slot) {
        return new ReadLocalNode(slot, KIND_LONG);
    }

    /**
     * Create a read node for Double (Float) slot.
     */
    public static ReadLocalNode createDouble(int slot) {
        return new ReadLocalNode(slot, KIND_DOUBLE);
    }

    /**
     * Create a read node for Boolean (Bool) slot.
     */
    public static ReadLocalNode createBoolean(int slot) {
        return new ReadLocalNode(slot, KIND_BOOLEAN);
    }

    /**
     * Create a read node for Object slot.
     */
    public static ReadLocalNode createObject(int slot) {
        return new ReadLocalNode(slot, KIND_OBJECT);
    }
}
