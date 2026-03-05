package com.mezze.interpreter.v2.nodes.effect;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.ImplSignature;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.nodes.continuation.ForkedExecutionException;
import com.mezze.interpreter.v2.nodes.continuation.MezzeContinuationSuspended;
import com.mezze.interpreter.v2.values.MezzeList;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeSuspended;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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

    // SCOPED EFFECTS: Slot for storing the scope record in the frame
    // Operations access scope via this slot
    @CompilationFinal
    private final int scopeSlot;

    // SCOPED EFFECTS: Node to execute initialize_scope (may be null)
    @Child
    private MezzeNode initializeScopeNode;

    // SCOPED EFFECTS: Node to execute finalize_scope (may be null)
    @Child
    private MezzeNode finalizeScopeNode;

    // SCOPED EFFECTS: Direct call node for initialize_scope (for parameterized impls)
    @Child
    private DirectCallNode initializeScopeCallNode;

    // SCOPED EFFECTS: Direct call node for finalize_scope (for parameterized impls)
    @Child
    private DirectCallNode finalizeScopeCallNode;

    // SCOPED EFFECTS: Flags for compile-time optimization
    @CompilationFinal
    private final boolean hasInitializeScope;

    @CompilationFinal
    private final boolean hasFinalizeScope;

    // Cached slot validity - set on first execution, then constant for JIT
    @CompilationFinal
    private boolean slotValidityChecked = false;

    @CompilationFinal
    private boolean slotIsValid = false;

    @CompilationFinal
    private boolean scopeSlotIsValid = false;

    // For executing suspended computations
    @Child
    private IndirectCallNode indirectCallNode;

    /**
     * Standard constructor for non-scoped effects (backward compatible).
     */
    public HandleNode(
            int handlerSlot,
            String[] effectNames,
            String handlerName,
            MezzeNode handlerNode,
            MezzeNode bodyNode
    ) {
        this(handlerSlot, effectNames, handlerName, handlerNode, bodyNode,
             -1, null, null, false, false);
    }

    /**
     * Full constructor with scoped effect support.
     */
    public HandleNode(
            int handlerSlot,
            String[] effectNames,
            String handlerName,
            MezzeNode handlerNode,
            MezzeNode bodyNode,
            int scopeSlot,
            MezzeNode initializeScopeNode,
            MezzeNode finalizeScopeNode,
            boolean hasInitializeScope,
            boolean hasFinalizeScope
    ) {
        this.handlerSlot = handlerSlot;
        this.effectNames = effectNames != null ? effectNames : new String[0];
        this.handlerName = handlerName;
        this.handlerNode = handlerNode;
        this.bodyNode = bodyNode;
        this.scopeSlot = scopeSlot;
        this.initializeScopeNode = initializeScopeNode;
        this.finalizeScopeNode = finalizeScopeNode;
        this.hasInitializeScope = hasInitializeScope;
        this.hasFinalizeScope = hasFinalizeScope;
    }

    /**
     * Create a handle node for a statically resolved handler.
     * The handler is looked up by content hash from the frame slot or context.
     */
    public static HandleNode create(int handlerSlot, String contentHash, MezzeNode bodyChild) {
        // Create a handler lookup node
        MezzeNode handlerNode = new HandlerLookupNode(handlerSlot, contentHash);
        // Pass handlerSlot as-is (including -1) - execute() will skip invalid slots
        return new HandleNode(handlerSlot, null, null, handlerNode, bodyChild);
    }

    /**
     * Create a handle node with effect names for context registration.
     */
    public static HandleNode create(int handlerSlot, String contentHash, String[] effectNames, String handlerName, MezzeNode bodyChild) {
        MezzeNode handlerNode = new HandlerLookupNode(handlerSlot, contentHash);
        // Pass handlerSlot as-is (including -1) - execute() will skip invalid slots
        return new HandleNode(handlerSlot, effectNames, handlerName, handlerNode, bodyChild);
    }

    /**
     * Create a handle node for scoped effects.
     *
     * @param handlerSlot Frame slot for storing the handler
     * @param contentHash Content hash for handler lookup
     * @param effectNames Effect names this handler handles
     * @param handlerName The handler/performer name
     * @param bodyChild The body to execute
     * @param scopeSlot Frame slot for storing the scope record
     * @param initializeScopeNode Node to execute initialize_scope
     * @param finalizeScopeNode Node to execute finalize_scope
     */
    public static HandleNode createScoped(
            int handlerSlot,
            String contentHash,
            String[] effectNames,
            String handlerName,
            MezzeNode bodyChild,
            int scopeSlot,
            MezzeNode initializeScopeNode,
            MezzeNode finalizeScopeNode
    ) {
        MezzeNode handlerNode = new HandlerLookupNode(handlerSlot, contentHash);
        return new HandleNode(
                handlerSlot,
                effectNames,
                handlerName,
                handlerNode,
                bodyChild,
                scopeSlot,
                initializeScopeNode,
                finalizeScopeNode,
                initializeScopeNode != null,
                finalizeScopeNode != null
        );
    }

    /**
     * Check if this handle node uses scoped effects.
     */
    public boolean isScopedEffect() {
        return hasInitializeScope;
    }

    /**
     * Get the scope slot (for operations to access).
     */
    public int getScopeSlot() {
        return scopeSlot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Evaluate the handler (may be parameterized impl call)
        Object handler = handlerNode.execute(frame);

        // Cache slot validity on first execution - becomes constant for JIT
        if (!slotValidityChecked) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            int frameSlotCount = frame.getFrameDescriptor().getNumberOfSlots();
            slotIsValid = handlerSlot >= 0 && handlerSlot < frameSlotCount;
            scopeSlotIsValid = scopeSlot >= 0 && scopeSlot < frameSlotCount;
            slotValidityChecked = true;
        }

        // Save previous handler value (for nested handlers)
        Object previousHandler = null;
        boolean hadPrevious = false;

        // Fast path: slot validity is now a compile-time constant
        if (slotIsValid && frame.isObject(handlerSlot)) {
            previousHandler = frame.getObject(handlerSlot);
            hadPrevious = true;
        }

        // Only set frame slot if valid
        if (slotIsValid) {
            frame.setObject(handlerSlot, handler);
        }

        // Also register in context so DynamicEffectCallNode can find it
        // AND push onto handler stack for lazy effects
        registerHandlerInContext(handler);
        pushHandlersToStack(handler);

        // SCOPED EFFECTS: Execute initialize_scope if present
        Object scope = null;
        if (hasInitializeScope && initializeScopeNode != null) {
            scope = initializeScopeNode.execute(frame);

            // Store scope in frame slot so operations can access it
            if (scopeSlotIsValid) {
                frame.setObject(scopeSlot, scope);
            }

            // Also register scope in context under the name "scope"
            registerScopeInContext(scope);
        }

        Throwable bodyException = null;
        Object result = null;
        MezzeList forkedResults = null;

        try {
            // Execute the body with the handler (and scope if scoped effect) installed
            result = bodyNode.execute(frame);
        } catch (ForkedExecutionException e) {
            // Fork completed - capture results, run finalize_scope, then return values
            forkedResults = e.values;
        } catch (Throwable t) {
            // Capture exception for later - finalize_scope must still run
            bodyException = t;
        } finally {
            // SCOPED EFFECTS: Execute finalize_scope in finally block
            // This ensures cleanup even if body throws
            Throwable finalizeException = null;
            if (hasFinalizeScope && finalizeScopeNode != null) {
                try {
                    finalizeScopeNode.execute(frame);
                } catch (Throwable t) {
                    finalizeException = t;
                }
            }

            // Clear scope from frame
            if (scopeSlotIsValid) {
                frame.setObject(scopeSlot, null);
            }

            // Pop handler from stack (for lazy effects)
            popHandlersFromStack();

            // Restore previous handler (or clear if none) - only if slot is valid
            if (slotIsValid) {
                if (hadPrevious) {
                    frame.setObject(handlerSlot, previousHandler);
                } else {
                    // Clear the slot to indicate no handler
                    frame.setObject(handlerSlot, null);
                }
            }

            // Handle exceptions: body exception takes priority, but log finalize exception
            // This matches try-with-resources semantics in Java
            if (bodyException != null) {
                if (finalizeException != null && finalizeException != bodyException) {
                    // Add finalize exception as suppressed
                    bodyException.addSuppressed(finalizeException);
                }
                // Re-throw body exception
                if (bodyException instanceof RuntimeException) {
                    throw (RuntimeException) bodyException;
                } else if (bodyException instanceof Error) {
                    throw (Error) bodyException;
                } else {
                    throw new RuntimeException(bodyException);
                }
            } else if (finalizeException != null) {
                // Only finalize failed - throw it
                if (finalizeException instanceof RuntimeException) {
                    throw (RuntimeException) finalizeException;
                } else if (finalizeException instanceof Error) {
                    throw (Error) finalizeException;
                } else {
                    throw new RuntimeException(finalizeException);
                }
            }
        }

        // If fork completed successfully, return the forked results
        if (forkedResults != null) {
            return forkedResults;
        }

        return processResult(result);
    }

    /**
     * Process the result:
     * - For MezzeSuspended (lazy effects), try to execute with our handlers
     * - For MezzeContinuationSuspended, extract the suspended value (this is how
     *   continuation-based effects like Exception.throw and Return.return work)
     *
     * NOTE: We do NOT wrap results in Ok - the user code is responsible for
     * wrapping success values explicitly. This keeps effects as pure userland
     * constructs without hardcoded effect names in the interpreter.
     */
    @TruffleBoundary
    private Object processResult(Object result) {
        // Handle lazy effects: if result is MezzeSuspended, try to execute with our handlers
        if (result instanceof MezzeSuspended suspended) {
            return processSuspended(suspended);
        }

        // Handle suspended continuations - extract the suspended value.
        // This is how continuation-based effects (Exception, Return, etc.) work:
        // the handler calls suspend_with {value = ...} and we extract that value.
        if (result instanceof MezzeContinuationSuspended suspended) {
            return suspended.getSuspendedValue();
        }

        return result;
    }

    /**
     * Process a MezzeSuspended result by executing it with our handlers.
     */
    @TruffleBoundary
    private Object processSuspended(MezzeSuspended suspended) {
        MezzeContext context;
        try {
            context = MezzeContext.get(this);
        } catch (AssertionError e) {
            // No context - return suspended as-is
            return suspended;
        }

        // Build handler map from our effect names
        Map<String, Object> handlers = new HashMap<>();
        if (handlerName != null) {
            Object handler = context.lookupLocal(handlerName);
            if (handler == null) {
                handler = context.lookupGlobal(handlerName);
            }
            if (handler != null) {
                handlers.put(handlerName, handler);
                // Also map effect names to this handler
                for (String effectName : effectNames) {
                    handlers.put(effectName, handler);
                }
            }
        }

        // Execute the suspended computation with our handlers
        if (indirectCallNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            indirectCallNode = insert(IndirectCallNode.create());
        }

        Object executedResult = suspended.executeWith(handlers, context, indirectCallNode);

        // Return the executed result (may still be suspended or a final value)
        return executedResult;
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

    /**
     * Push handlers onto the context's handler stack for lazy effects.
     */
    @TruffleBoundary
    private void pushHandlersToStack(Object handler) {
        if (handler == null) return;

        MezzeContext context;
        try {
            context = MezzeContext.get(this);
        } catch (AssertionError e) {
            return;
        }

        // Build handler map
        Map<String, Object> handlers = new HashMap<>();
        if (handlerName != null) {
            handlers.put(handlerName, handler);
        }
        for (String effectName : effectNames) {
            handlers.put(effectName, handler);
        }

        context.pushHandlers(handlers);
    }

    /**
     * Pop handlers from the context's handler stack.
     */
    @TruffleBoundary
    private void popHandlersFromStack() {
        MezzeContext context;
        try {
            context = MezzeContext.get(this);
        } catch (AssertionError e) {
            return;
        }

        context.popHandlers();
    }

    /**
     * Register scope in context so operations can access it.
     * The scope is registered under the name "scope".
     */
    @TruffleBoundary
    private void registerScopeInContext(Object scope) {
        if (scope == null) return;

        MezzeContext context;
        try {
            context = MezzeContext.get(this);
        } catch (AssertionError e) {
            // No polyglot context available (e.g., in isolated unit tests)
            return;
        }

        // Register scope under the reserved name "scope"
        context.defineLocal("scope", scope);
    }
}
