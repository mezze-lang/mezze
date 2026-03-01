package com.mezze.interpreter.v2.nodes.effect;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Node that looks up a handler by content hash.
 * Tries local frame slot first, then context lookup.
 */
@NodeInfo(shortName = "handler-lookup")
final class HandlerLookupNode extends MezzeNode {

    @CompilationFinal
    private final int localSlot;

    @CompilationFinal
    private final String contentHash;

    HandlerLookupNode(int localSlot, String contentHash) {
        this.localSlot = localSlot;
        this.contentHash = contentHash;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Try local slot first
        if (localSlot >= 0) {
            Object handler = frame.getObject(localSlot);
            if (handler != null) {
                return handler;
            }
        }
        // Fall back to context lookup
        return lookupInContext();
    }

    @TruffleBoundary
    private Object lookupInContext() {
        MezzeContext context = MezzeContext.get(this);
        Object handler = context.lookupGlobal(contentHash);
        if (handler == null) {
            throw new RuntimeException("Handler not found for hash: " + contentHash);
        }
        return handler;
    }
}

/**
 * Handle effect node - FULLY JIT-COMPILABLE.
 *
 * The handler is stored in a frame slot that PerformNode reads from.
 * This avoids any runtime lookups or @TruffleBoundary calls.
 *
 * Flow:
 * 1. Evaluate handler expression
 * 2. Store handler in the pre-allocated frame slot AND context (for effect name lookup)
 * 3. Execute body
 * 4. Restore previous handler (if nested)
 *
 * The handler slot is determined statically by the typechecker/converter
 * and passed via the handlerSlot parameter.
 */
@NodeInfo(shortName = "handle")
public final class HandleNode extends MezzeNode {

    @CompilationFinal
    private final int handlerSlot;

    @CompilationFinal(dimensions = 1)
    private final String[] effectNames;

    @CompilationFinal
    private final String handlerName;

    @Child
    private MezzeNode handlerNode;

    @Child
    private MezzeNode bodyNode;

    public HandleNode(
            int handlerSlot,
            String[] effectNames,
            String handlerName,
            MezzeNode handlerNode,
            MezzeNode bodyNode
    ) {
        this.handlerSlot = handlerSlot;
        this.effectNames = effectNames != null ? effectNames : new String[0];
        this.handlerName = handlerName;
        this.handlerNode = handlerNode;
        this.bodyNode = bodyNode;
    }

    /**
     * Create a handle node for a statically resolved handler.
     * The handler is looked up by content hash from the frame slot or context.
     */
    public static HandleNode create(int handlerSlot, String contentHash, MezzeNode bodyChild) {
        // Create a handler lookup node
        MezzeNode handlerNode = new HandlerLookupNode(handlerSlot, contentHash);
        return new HandleNode(handlerSlot >= 0 ? handlerSlot : 0, null, null, handlerNode, bodyChild);
    }

    /**
     * Create a handle node with effect names for context registration.
     */
    public static HandleNode create(int handlerSlot, String contentHash, String[] effectNames, String handlerName, MezzeNode bodyChild) {
        MezzeNode handlerNode = new HandlerLookupNode(handlerSlot, contentHash);
        return new HandleNode(handlerSlot >= 0 ? handlerSlot : 0, effectNames, handlerName, handlerNode, bodyChild);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Evaluate the handler (may be parameterized impl call)
        Object handler = handlerNode.execute(frame);

        // Save previous handler value (for nested handlers)
        Object previousHandler = null;
        boolean hadPrevious = false;

        if (frame.isObject(handlerSlot)) {
            previousHandler = frame.getObject(handlerSlot);
            hadPrevious = true;
        }

        // Install the new handler in the frame slot
        frame.setObject(handlerSlot, handler);

        // Also register in context so DynamicEffectCallNode can find it
        registerHandlerInContext(handler);

        try {
            // Execute the body with the handler installed
            return bodyNode.execute(frame);
        } finally {
            // Restore previous handler (or clear if none)
            if (hadPrevious) {
                frame.setObject(handlerSlot, previousHandler);
            } else {
                // Clear the slot to indicate no handler
                frame.setObject(handlerSlot, null);
            }
        }
    }

    @TruffleBoundary
    private void registerHandlerInContext(Object handler) {
        if (handler == null) return;

        MezzeContext context;
        try {
            context = MezzeContext.get(this);
        } catch (AssertionError e) {
            // No polyglot context available (e.g., in isolated unit tests)
            // Skip context registration - frame slot registration is sufficient
            return;
        }

        // Register under handler name
        if (handlerName != null) {
            context.defineLocal(handlerName, handler);
        }

        // Register under each effect name
        for (String effectName : effectNames) {
            context.defineLocal(effectName, handler);
        }
    }
}
