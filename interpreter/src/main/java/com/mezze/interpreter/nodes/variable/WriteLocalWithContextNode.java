package com.mezze.interpreter.nodes.variable;

import com.mezze.interpreter.nodes.MezzeNode;
// ContinuationSuspendedException is NOT caught here — must propagate to HandleNode
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeSuspended;
import com.mezze.interpreter.values.MezzeUnit;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Write a value to both a local frame slot AND the global context.
 *
 * This is needed for recursive function bindings:
 * - The function is first written to the global context
 * - When the function's body captures its own name as a closure variable,
 *   the frame slot may be null (not written yet)
 * - The ReadLocalWithFallbackNode falls back to global lookup and finds it
 *
 * Also used for ALL top-level bindings so nested functions can access
 * top-level variables via fallback global lookup.
 *
 * This mirrors V1's SimpleWriteVariableNode behavior.
 */
@NodeInfo(shortName = "write-ctx")
public final class WriteLocalWithContextNode extends MezzeNode {

    // Kind constants matching ReadLocalNode exactly
    public static final int KIND_LONG = 0;
    public static final int KIND_DOUBLE = 1;
    public static final int KIND_BOOLEAN = 2;
    public static final int KIND_OBJECT = 3;

    @CompilationFinal
    private final int slot;

    @CompilationFinal
    private final int kind;

    @CompilationFinal
    private final String variableName;

    // Content hash for content-addressed global registration (null = no global registration)
    @CompilationFinal
    private final Hash contentHash;

    @Child
    private MezzeNode valueNode;

    public WriteLocalWithContextNode(MezzeNode valueNode, int slot, String variableName) {
        this(valueNode, slot, KIND_OBJECT, variableName, null);
    }

    public WriteLocalWithContextNode(MezzeNode valueNode, int slot, String variableName, Hash contentHash) {
        this(valueNode, slot, KIND_OBJECT, variableName, contentHash);
    }

    public WriteLocalWithContextNode(MezzeNode valueNode, int slot, int kind, String variableName, Hash contentHash) {
        this.valueNode = valueNode;
        this.slot = slot;
        this.kind = kind;
        this.variableName = variableName;
        this.contentHash = contentHash;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = valueNode.execute(frame);

        // If value is a MezzeSuspended and we have handlers, try to force it first.
        // This prevents ClassCastException when storing suspended values in typed slots.
        if (value instanceof MezzeSuspended && kind != KIND_OBJECT) {
            value = forceSuspendedValue((MezzeSuspended) value);
        }

        // Write to frame slot with correct type (fast path)
        // Switch order matches ReadLocalNode: 0=Long, 1=Double, 2=Boolean, 3=Object
        // If value is still MezzeSuspended after forcing attempt, store as Object
        // to prevent ClassCastException on typed slots.
        if (value instanceof MezzeSuspended) {
            frame.setObject(slot, value);
        } else {
            switch (kind) {
                case KIND_LONG:
                    if (value instanceof Long l) {
                        frame.setLong(slot, l);
                    } else {
                        frame.setObject(slot, value);
                    }
                    break;
                case KIND_DOUBLE:
                    if (value instanceof Double d) {
                        frame.setDouble(slot, d);
                    } else if (value instanceof Long l) {
                        frame.setDouble(slot, l.doubleValue());
                    } else {
                        frame.setObject(slot, value);
                    }
                    break;
                case KIND_BOOLEAN:
                    if (value instanceof Boolean b) {
                        frame.setBoolean(slot, b);
                    } else {
                        frame.setObject(slot, value);
                    }
                    break;
                case KIND_OBJECT:
                default:
                    frame.setObject(slot, value);
                    break;
            }
        }

        // Register with the *current* context's globals. The CallTarget
        // holding this node is shared across MezzeContexts on a shared
        // Engine; we must (re-)populate `globals` every time the
        // top-level let runs so a new Context (e.g. a second
        // MezzeTestHelper.run) sees the binding. defineGlobal is an
        // idempotent HashMap.put — cheap, and top-level lets run once
        // per program execution.
        registerGlobal(value);

        // Return MezzeUnit for lazy suspended effects to prevent early return in BlockNode
        if (value instanceof MezzeSuspended) {
            return MezzeUnit.INSTANCE;
        }
        return value;
    }

    /**
     * Try to force a suspended value by executing it with current handlers.
     * Returns the forced result, or the original MezzeSuspended if forcing fails.
     */
    @TruffleBoundary
    private Object forceSuspendedValue(MezzeSuspended suspended) {
        // Under the new dispatch design, forcing routes through the current
        // fiber rather than a context-wide handler map. force() returns the
        // suspension unchanged when no fiber is current.
        return suspended.force(com.oracle.truffle.api.nodes.IndirectCallNode.getUncached());
    }

    @TruffleBoundary
    private void registerGlobal(Object value) {
        MezzeContext context = MezzeContext.get(this);
        if (contentHash != null) {
            context.defineGlobal(contentHash, value);
        }
        // Also register by variable name in the test-inspection map so
        // getLastExecutionContext() (and any test that reads top-level bindings
        // by name) can find this value. Strictly separate from the Hash-keyed
        // globals map — name vs hash can never collide.
        context.recordTestInspectionVar(variableName, value);
    }

    /**
     * Create a write-with-context node (Object kind).
     */
    public static WriteLocalWithContextNode create(MezzeNode valueNode, int slot, String variableName) {
        return new WriteLocalWithContextNode(valueNode, slot, KIND_OBJECT, variableName, null);
    }

    /**
     * Create a write-with-context node with content hash for precise registration (Object kind).
     */
    public static WriteLocalWithContextNode create(MezzeNode valueNode, int slot, String variableName, Hash contentHash) {
        return new WriteLocalWithContextNode(valueNode, slot, KIND_OBJECT, variableName, contentHash);
    }

    /**
     * Create a write-with-context node with explicit kind for typed slots.
     */
    public static WriteLocalWithContextNode create(MezzeNode valueNode, int slot, int kind, String variableName, Hash contentHash) {
        return new WriteLocalWithContextNode(valueNode, slot, kind, variableName, contentHash);
    }
}
