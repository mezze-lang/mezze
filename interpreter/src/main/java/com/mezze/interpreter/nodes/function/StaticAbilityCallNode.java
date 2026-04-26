package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.runtime.ImplRegistry;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.InvalidAssumptionException;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * STATIC ability call node - ZERO RUNTIME OVERHEAD WITH ASSUMPTION GUARD.
 *
 * This is used when the impl is fully monomorphized and resolved at compile time
 * via content-hash addressing. Unlike AbilityCallNode which resolves the impl
 * dynamically, this node has:
 *
 * 1. Pre-resolved CallTarget (no impl record lookup)
 * 2. Pre-created DirectCallNode (no lazy initialization)
 * 3. Pre-computed closure values (captured at node creation)
 * 4. Assumption guard for deoptimization if impl changes
 * 5. NO transferToInterpreterAndInvalidate() calls in hot path
 *
 * Assumption Guard:
 * - The impl is assumed to be stable (not redefined)
 * - If ImplRegistry.invalidate(contentHash) is called, code deoptimizes
 * - This enables aggressive inlining without runtime checks
 *
 * After JIT: This compiles to a direct function call with inlined arguments.
 */
@NodeInfo(shortName = "static-ability-call")
public final class StaticAbilityCallNode extends MezzeNode {

    // Pre-created at AST conversion time - NO lazy initialization
    @Child
    private DirectCallNode callNode;

    @Children
    private final MezzeNode[] argumentNodes;

    @CompilationFinal
    private final int argumentCount;

    // Pre-captured closure values from the monomorphic impl's operation
    @CompilationFinal(dimensions = 1)
    private final Object[] closureValues;

    @CompilationFinal
    private final int closureLength;

    // Assumption that the impl hasn't been redefined
    @CompilationFinal
    private final Assumption implStableAssumption;

    // Content hash for deoptimization recovery
    @CompilationFinal
    private final String contentHash;

    /**
     * Create a static ability call node with pre-resolved call target and closure.
     *
     * @param callTarget Pre-resolved CallTarget from the monomorphic impl's operation
     * @param closureValues Pre-captured closure values (may be empty array)
     * @param argumentNodes Argument expression nodes
     * @param contentHash Content hash of the impl (for assumption tracking)
     */
    public StaticAbilityCallNode(
            CallTarget callTarget,
            Object[] closureValues,
            MezzeNode[] argumentNodes,
            String contentHash
    ) {
        this.callNode = DirectCallNode.create(callTarget);
        this.closureValues = closureValues != null ? closureValues : EMPTY_CLOSURE;
        this.closureLength = this.closureValues.length;
        this.argumentNodes = argumentNodes;
        this.argumentCount = argumentNodes.length;
        this.contentHash = contentHash;

        // Register and get assumption for this impl
        this.implStableAssumption = contentHash != null
            ? ImplRegistry.getInstance().getAssumption(contentHash)
            : Assumption.ALWAYS_VALID;
    }


    private static final Object[] EMPTY_CLOSURE = new Object[0];

    /**
     * Create with no closure (most common case for static impls).
     */
    public static StaticAbilityCallNode create(
            CallTarget callTarget,
            MezzeNode[] argumentNodes
    ) {
        return new StaticAbilityCallNode(callTarget, EMPTY_CLOSURE, argumentNodes, null);
    }

    /**
     * Create with content hash for assumption tracking.
     */
    public static StaticAbilityCallNode createWithAssumption(
            CallTarget callTarget,
            MezzeNode[] argumentNodes,
            String contentHash
    ) {
        return new StaticAbilityCallNode(callTarget, EMPTY_CLOSURE, argumentNodes, contentHash);
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Check assumption - this is a no-op in compiled code when valid
        try {
            implStableAssumption.check();
        } catch (InvalidAssumptionException e) {
            // Impl was redefined - this triggers deoptimization
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Impl " + contentHash + " was redefined, recompilation needed");
        }

        // FAST PATH: Zero closure - no closure handling
        if (closureLength == 0) {
            Object[] arguments = new Object[argumentCount];
            for (int i = 0; i < argumentCount; i++) {
                arguments[i] = argumentNodes[i].execute(frame);
            }
            return callNode.call(arguments);
        }

        // Build arguments array - closure values are compile-time constants
        Object[] arguments = new Object[closureLength + argumentCount];

        // Copy pre-captured closure values (compile-time constant after JIT)
        for (int i = 0; i < closureLength; i++) {
            arguments[i] = closureValues[i];
        }

        // Evaluate and copy argument values - @ExplodeLoop unrolls this
        for (int i = 0; i < argumentCount; i++) {
            arguments[closureLength + i] = argumentNodes[i].execute(frame);
        }

        // Direct call - NO lazy initialization, NO type checks
        return callNode.call(arguments);
    }
}
