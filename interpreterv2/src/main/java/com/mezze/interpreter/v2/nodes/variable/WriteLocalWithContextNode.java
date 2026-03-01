package com.mezze.interpreter.v2.nodes.variable;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
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
 * This mirrors V1's SimpleWriteVariableNode behavior.
 */
@NodeInfo(shortName = "write-ctx")
public final class WriteLocalWithContextNode extends MezzeNode {

    @CompilationFinal
    private final int slot;

    @CompilationFinal
    private final String variableName;

    // Content hash for content-addressed global registration (null = use variableName only)
    @CompilationFinal
    private final String contentHash;

    @Child
    private MezzeNode valueNode;

    public WriteLocalWithContextNode(MezzeNode valueNode, int slot, String variableName) {
        this(valueNode, slot, variableName, null);
    }

    public WriteLocalWithContextNode(MezzeNode valueNode, int slot, String variableName, String contentHash) {
        this.valueNode = valueNode;
        this.slot = slot;
        this.variableName = variableName;
        this.contentHash = contentHash;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = valueNode.execute(frame);

        // Write to frame slot (fast path)
        frame.setObject(slot, value);

        // Write to global context for recursive lookups
        defineGlobal(value);

        return value;
    }

    @TruffleBoundary
    private void defineGlobal(Object value) {
        MezzeContext context = MezzeContext.get(this);
        // Register by content hash for precise lookup (if available)
        if (contentHash != null) {
            context.defineGlobal(contentHash, value);
        }
        // Also register by variable name for backward compatibility
        context.defineGlobal(variableName, value);
    }

    /**
     * Create a write-with-context node.
     */
    public static WriteLocalWithContextNode create(MezzeNode valueNode, int slot, String variableName) {
        return new WriteLocalWithContextNode(valueNode, slot, variableName, null);
    }

    /**
     * Create a write-with-context node with content hash for precise registration.
     */
    public static WriteLocalWithContextNode create(MezzeNode valueNode, int slot, String variableName, String contentHash) {
        return new WriteLocalWithContextNode(valueNode, slot, variableName, contentHash);
    }
}
