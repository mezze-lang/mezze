package com.mezze.interpreter.v2.nodes.variable;

import com.mezze.interpreter.v2.nodes.MezzeNode;
// ContinuationSuspendedException is NOT caught here — must propagate to HandleNode
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.values.MezzeSuspended;
import com.mezze.interpreter.v2.values.MezzeUnit;
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

    // Content hash for content-addressed global registration (null = use variableName only)
    @CompilationFinal
    private final String contentHash;

    @Child
    private MezzeNode valueNode;

    // Track if we've already registered with the global context.
    // This only needs to happen once - after that, just write to frame slot.
    @CompilationFinal
    private boolean globalRegistered = false;

    public WriteLocalWithContextNode(MezzeNode valueNode, int slot, String variableName) {
        this(valueNode, slot, KIND_OBJECT, variableName, null);
    }

    public WriteLocalWithContextNode(MezzeNode valueNode, int slot, String variableName, String contentHash) {
        this(valueNode, slot, KIND_OBJECT, variableName, contentHash);
    }

    public WriteLocalWithContextNode(MezzeNode valueNode, int slot, int kind, String variableName, String contentHash) {
        this.valueNode = valueNode;
        this.slot = slot;
        this.kind = kind;
        this.variableName = variableName;
        this.contentHash = contentHash;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // ContinuationSuspendedException MUST propagate to HandleNode.
        // Do NOT catch it here — it's the mechanism for suspend_with to escape
        // back to the perform boundary (e.g., Exception.throw, Return.return).
        Object value = valueNode.execute(frame);

        // Write to frame slot with correct type (fast path)
        // Switch order matches ReadLocalNode: 0=Long, 1=Double, 2=Boolean, 3=Object
        switch (kind) {
            case KIND_LONG:
                frame.setLong(slot, (long) value);
                break;
            case KIND_DOUBLE:
                frame.setDouble(slot, (double) value);
                break;
            case KIND_BOOLEAN:
                frame.setBoolean(slot, (boolean) value);
                break;
            case KIND_OBJECT:
            default:
                frame.setObject(slot, value);
                break;
        }

        // Register with global context only once (for recursive/nested access)
        // After JIT, this check becomes a constant false and is eliminated
        if (!globalRegistered) {
            defineGlobalOnce(value);
        }

        // Return MezzeUnit for lazy suspended effects to prevent early return in BlockNode
        if (value instanceof MezzeSuspended) {
            return MezzeUnit.INSTANCE;
        }
        return value;
    }

    @TruffleBoundary
    private void defineGlobalOnce(Object value) {
        // Double-check to handle concurrent first execution
        if (globalRegistered) return;

        MezzeContext context = MezzeContext.get(this);
        // Register by content hash for precise lookup (if available)
        if (contentHash != null) {
            context.defineGlobal(contentHash, value);
        }
        // Also register by variable name for backward compatibility
        context.defineGlobal(variableName, value);

        // Mark as registered - after JIT, this makes the if-check a constant false
        com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
        globalRegistered = true;
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
    public static WriteLocalWithContextNode create(MezzeNode valueNode, int slot, String variableName, String contentHash) {
        return new WriteLocalWithContextNode(valueNode, slot, KIND_OBJECT, variableName, contentHash);
    }

    /**
     * Create a write-with-context node with explicit kind for typed slots.
     */
    public static WriteLocalWithContextNode create(MezzeNode valueNode, int slot, int kind, String variableName, String contentHash) {
        return new WriteLocalWithContextNode(valueNode, slot, kind, variableName, contentHash);
    }
}
