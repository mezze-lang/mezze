package com.mezze.interpreter.v2.nodes.ability;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * JIT-friendly ability method call node that reads a CallTarget directly from a frame slot.
 *
 * This is the optimized version of DictionaryAbilityCallNode. Instead of reading an
 * impl record and looking up the operation by name (which prevents JIT optimization),
 * this node reads the specific operation's CallTarget directly from the frame.
 *
 * Performance characteristics:
 * - Zero runtime field lookups (CallTarget is passed directly)
 * - DirectCallNode for inlining
 * - All metadata is @CompilationFinal (compiles away in hot paths)
 * - No @TruffleBoundary in execute path
 *
 * Layout:
 * - Each (constraint, operation) pair gets its own frame slot
 * - The caller extracts the CallTarget from the resolved impl and passes it
 * - This node just calls it directly
 *
 * Example:
 * <pre>
 * pub let encode_all: List 'a -> List Bytes with Encode Json 'a = xs ->
 *     map {f = {x} -> Encode.encode {val = x, config = default_config}, xs}
 * </pre>
 *
 * When `encode_all` is called with a concrete type like `String`, the caller:
 * 1. Looks up the `Encode Json String` impl by content hash
 * 2. Extracts the `encode` operation's CallTarget
 * 3. Passes it in the closure (captured in a frame slot)
 *
 * Inside the function, `Encode.encode` uses this node to:
 * 1. Read the CallTarget from the frame slot (single object read)
 * 2. Call it directly via DirectCallNode (enables inlining)
 */
@NodeInfo(shortName = "direct-dict-call")
public final class DirectDictionaryCallNode extends MezzeNode {

    // Frame slot where the CallTarget is stored (passed via closure)
    @CompilationFinal
    private final int callTargetSlot;

    // For debugging/error messages only - not used in hot path
    @CompilationFinal
    private final String abilityName;

    @CompilationFinal
    private final String operationName;

    @Child
    private MezzeNode argumentsNode;

    // DirectCallNode for JIT optimization - initialized on first call
    @Child
    private DirectCallNode callNode;

    // Track if we've seen a stable CallTarget
    @CompilationFinal
    private CallTarget cachedTarget;

    public DirectDictionaryCallNode(int callTargetSlot, String abilityName, String operationName, MezzeNode argumentsNode) {
        this.callTargetSlot = callTargetSlot;
        this.abilityName = abilityName;
        this.operationName = operationName;
        this.argumentsNode = argumentsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Evaluate arguments first (may have side effects)
        Object argsValue = argumentsNode.execute(frame);

        // Read the operation function directly from the frame slot
        // This is a MezzeFunction with its CallTarget
        Object funcValue = frame.getObject(callTargetSlot);

        if (!(funcValue instanceof MezzeFunction function)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException(
                "Dictionary slot " + callTargetSlot + " does not contain a function for " +
                abilityName + "." + operationName + ". Got: " +
                (funcValue == null ? "null" : funcValue.getClass().getName())
            );
        }

        CallTarget target = function.getCallTarget();

        // Fast path: if CallTarget is stable (same as last time), use cached DirectCallNode
        if (callNode != null && target == cachedTarget) {
            // Build arguments: closure values + our argument
            Object[] closureValues = function.getClosureValues();
            return callWithClosure(closureValues, argsValue);
        }

        // Slow path: first call or CallTarget changed (megamorphic case)
        CompilerDirectives.transferToInterpreterAndInvalidate();

        // Cache the new target and create a DirectCallNode
        cachedTarget = target;
        callNode = insert(DirectCallNode.create(target));

        Object[] closureValues = function.getClosureValues();
        return callWithClosure(closureValues, argsValue);
    }

    private Object callWithClosure(Object[] closureValues, Object argsValue) {
        int closureLength = closureValues.length;

        if (closureLength == 0) {
            // No closure - just pass the argument
            return callNode.call(argsValue);
        }

        // Build arguments array: [closure0, closure1, ..., arg]
        Object[] arguments = new Object[closureLength + 1];
        CompilerAsserts.partialEvaluationConstant(closureLength);

        for (int i = 0; i < closureLength; i++) {
            arguments[i] = closureValues[i];
        }
        arguments[closureLength] = argsValue;

        return callNode.call(arguments);
    }

    public static DirectDictionaryCallNode create(int callTargetSlot, String abilityName, String operationName, MezzeNode argumentsNode) {
        return new DirectDictionaryCallNode(callTargetSlot, abilityName, operationName, argumentsNode);
    }
}
