package com.mezze.interpreter.v2.nodes.variable;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.values.MezzeSuspended;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
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

    // For forcing MezzeSuspended values (only used for KIND_OBJECT)
    @Child
    private IndirectCallNode suspendedCallNode;

    public ReadLocalNode(int slot, int kind) {
        this.slot = slot;
        this.kind = kind;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // The switch on kind is @CompilationFinal, so JIT will eliminate it
        // Use try-catch for typed reads to handle slot type mismatches that can
        // occur in concurrent scenarios where the slot type may not match expected
        try {
            Object rawValue = switch (kind) {
                case KIND_LONG -> frame.getLong(slot);
                case KIND_DOUBLE -> frame.getDouble(slot);
                case KIND_BOOLEAN -> frame.getBoolean(slot);
                default -> readObjectOrPrimitive(frame);
            };
            // Check for MezzeSuspended regardless of kind
            // This handles the case where a capture's static type is Int but at runtime
            // the value is a MezzeSuspended (from lazy effects)
            if (rawValue instanceof MezzeSuspended suspended) {
                return forceSuspended(suspended);
            }
            return rawValue;
        } catch (FrameSlotTypeException e) {
            // Slot type mismatch - fall back to generic read
            Object value = frame.getValue(slot);
            if (value instanceof MezzeSuspended suspended) {
                return forceSuspended(suspended);
            }
            return value;
        }
    }

    /**
     * Read as Object, with fallback to primitives if slot type doesn't match.
     * Uses frame.getValue() which returns the value regardless of slot type.
     *
     * Also handles lazy effect forcing: if the value is a MezzeSuspended and
     * there are active handlers in scope, force (evaluate) the suspended
     * computation so that any effect exceptions are raised here.
     */
    private Object readObjectOrPrimitive(VirtualFrame frame) {
        // getValue() returns the value regardless of type (Object, Long, Double, Boolean)
        // It handles all slot types without throwing exceptions
        Object value = frame.getValue(slot);
        if (value instanceof MezzeSuspended suspended) {
            return forceSuspended(suspended);
        }
        return value;
    }

    @TruffleBoundary
    private Object forceSuspended(MezzeSuspended suspended) {
        MezzeContext context;
        try {
            context = MezzeContext.get(this);
        } catch (AssertionError e) {
            return suspended;
        }
        if (!context.hasHandlers()) {
            return suspended;
        }
        if (suspendedCallNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            suspendedCallNode = insert(IndirectCallNode.create());
        }
        return suspended.executeWith(context.getCurrentHandlers(), context, suspendedCallNode);
    }

    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        if (kind == KIND_LONG) {
            try {
                return frame.getLong(slot);
            } catch (FrameSlotTypeException e) {
                // Fall through to unbox from generic read
            }
        }
        Object value = execute(frame);
        if (value instanceof Long l) {
            return l;
        }
        throw new UnexpectedResultException(value);
    }

    @Override
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        try {
            if (kind == KIND_DOUBLE) {
                return frame.getDouble(slot);
            }
            if (kind == KIND_LONG) {
                return frame.getLong(slot);
            }
        } catch (FrameSlotTypeException e) {
            // Fall through to unbox from generic read
        }
        Object value = execute(frame);
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof Long l) {
            return l.doubleValue();
        }
        throw new UnexpectedResultException(value);
    }

    @Override
    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        if (kind == KIND_BOOLEAN) {
            try {
                return frame.getBoolean(slot);
            } catch (FrameSlotTypeException e) {
                // Fall through to unbox from generic read
            }
        }
        Object value = execute(frame);
        if (value instanceof Boolean b) {
            return b;
        }
        throw new UnexpectedResultException(value);
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
