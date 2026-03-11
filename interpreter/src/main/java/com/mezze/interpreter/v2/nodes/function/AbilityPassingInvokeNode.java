package com.mezze.interpreter.v2.nodes.function;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * JIT-friendly invoke node that passes ability operation functions to polymorphic callees.
 *
 * When calling a function that has ability constraints, this node:
 * 1. Looks up resolved ability implementations by content hash
 * 2. Extracts the specific operation functions needed
 * 3. Passes them as additional arguments after the closure values and record argument
 *
 * The callee's MezzeFunctionRootNode writes these to the appropriate frame slots.
 *
 * Argument layout: [closure0, ..., closureN, recordArg, abilityOp0, ..., abilityOpM]
 */
@NodeInfo(shortName = "ability-invoke")
public final class AbilityPassingInvokeNode extends MezzeNode {

    @Child
    private MezzeNode functionNode;

    @Children
    private final MezzeNode[] argumentNodes;

    // Resolved ability content hashes for this call site
    @CompilationFinal(dimensions = 1)
    private final String[] abilityContentHashes;

    // Operation names to extract for each ability
    // Parallel array with abilityContentHashes
    @CompilationFinal(dimensions = 1)
    private final String[] operationNames;

    // Cached DirectCallNode for monomorphic sites
    @Child
    private DirectCallNode callNode;

    @CompilationFinal
    private MezzeFunction cachedFunction;

    // Cached operation field indices for faster repeated access
    @CompilationFinal(dimensions = 1)
    private int[] cachedOperationIndices;

    public AbilityPassingInvokeNode(
            MezzeNode functionNode,
            MezzeNode[] argumentNodes,
            String[] abilityContentHashes,
            String[] operationNames
    ) {
        this.functionNode = functionNode;
        this.argumentNodes = argumentNodes;
        this.abilityContentHashes = abilityContentHashes;
        this.operationNames = operationNames;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Evaluate function expression
        Object funcValue = functionNode.execute(frame);
        if (!(funcValue instanceof MezzeFunction function)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Expected function, got: " +
                (funcValue == null ? "null" : funcValue.getClass().getName()));
        }

        // Evaluate arguments
        int argCount = argumentNodes.length;
        Object[] argValues = new Object[argCount];
        for (int i = 0; i < argCount; i++) {
            argValues[i] = argumentNodes[i].execute(frame);
        }

        // Get closure values
        Object[] closureValues = function.getClosureValues();
        int closureLength = closureValues.length;

        // Look up ability operation functions
        int abilityCount = abilityContentHashes.length;
        Object[] abilityOps = null;

        if (abilityCount > 0) {
            abilityOps = resolveAbilityOperations();
        }

        // Build full argument array
        // Layout: [closure0, ..., closureN, arg0, ..., argM, abilityOp0, ..., abilityOpK]
        int totalArgs = closureLength + argCount + (abilityOps != null ? abilityOps.length : 0);
        Object[] fullArgs = new Object[totalArgs];

        // Copy closure values
        for (int i = 0; i < closureLength; i++) {
            fullArgs[i] = closureValues[i];
        }

        // Copy regular arguments
        for (int i = 0; i < argCount; i++) {
            fullArgs[closureLength + i] = argValues[i];
        }

        // Copy ability operation functions
        if (abilityOps != null) {
            for (int i = 0; i < abilityOps.length; i++) {
                fullArgs[closureLength + argCount + i] = abilityOps[i];
            }
        }

        // Use cached call node for monomorphic sites
        if (callNode != null && function == cachedFunction) {
            return callNode.call(fullArgs);
        }

        // First call or function changed - create new call node
        CompilerDirectives.transferToInterpreterAndInvalidate();
        cachedFunction = function;
        callNode = insert(DirectCallNode.create(function.getCallTarget()));

        return callNode.call(fullArgs);
    }

    @TruffleBoundary
    private Object[] resolveAbilityOperations() {
        MezzeContext context = MezzeContext.get(this);
        Object[] ops = new Object[abilityContentHashes.length];

        // Initialize cached indices array if needed
        if (cachedOperationIndices == null) {
            cachedOperationIndices = new int[abilityContentHashes.length];
            for (int i = 0; i < cachedOperationIndices.length; i++) {
                cachedOperationIndices[i] = -1;
            }
        }

        for (int i = 0; i < abilityContentHashes.length; i++) {
            String contentHash = abilityContentHashes[i];
            String operationName = operationNames[i];

            // Look up implementation by content hash
            Object implValue = context.getAbilityImplByHash(contentHash);
            if (!(implValue instanceof MezzeRecord impl)) {
                throw new RuntimeException(
                    "Could not find ability implementation for hash: " + contentHash);
            }

            // Use cached index if available
            int opIndex = cachedOperationIndices[i];
            if (opIndex < 0) {
                opIndex = impl.getFieldIndex(operationName);
                if (opIndex < 0) {
                    throw new RuntimeException(
                        "Ability implementation does not have operation: " + operationName);
                }
                cachedOperationIndices[i] = opIndex;
            }

            // Extract the operation function
            Object opValue;
            try {
                opValue = impl.getField(opIndex);
            } catch (Exception e) {
                throw new RuntimeException(
                    "Ability implementation does not have operation: " + operationName);
            }

            if (!(opValue instanceof MezzeFunction)) {
                throw new RuntimeException(
                    "Ability operation " + operationName + " is not a function: " + opValue);
            }

            ops[i] = opValue;
        }

        return ops;
    }

    public static AbilityPassingInvokeNode create(
            MezzeNode functionNode,
            MezzeNode[] argumentNodes,
            String[] abilityContentHashes,
            String[] operationNames
    ) {
        return new AbilityPassingInvokeNode(functionNode, argumentNodes, abilityContentHashes, operationNames);
    }
}
