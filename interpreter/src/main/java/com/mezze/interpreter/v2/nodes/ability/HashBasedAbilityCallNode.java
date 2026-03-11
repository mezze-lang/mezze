package com.mezze.interpreter.v2.nodes.ability;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * JIT-friendly ability method call node that looks up implementation by content hash.
 *
 * This node is used when the typechecker has resolved which ability implementation
 * to use and provided its content hash. The lookup is O(1) via the content hash,
 * and the result is cached for subsequent calls.
 *
 * Unlike RuntimeAbilityCallNode which searches by ability name (ambiguous when
 * multiple implementations exist), this node does a direct lookup by hash.
 *
 * Performance characteristics:
 * - First call: @TruffleBoundary lookup by hash, then cache
 * - Subsequent calls: DirectCallNode with no lookup overhead
 * - Zero allocation in hot path after warmup
 */
@NodeInfo(shortName = "hash-ability-call")
public final class HashBasedAbilityCallNode extends MezzeNode {

    // Content hash of the ability implementation to use
    @CompilationFinal
    private final String implementationHash;

    // Operation name to call on the implementation
    @CompilationFinal
    private final String operationName;

    // Ability name for error messages
    @CompilationFinal
    private final String abilityName;

    @Child
    private MezzeNode argumentsNode;

    // Cached operation function and DirectCallNode
    @Child
    private DirectCallNode callNode;

    @CompilationFinal
    private MezzeFunction cachedOperation;

    @CompilationFinal
    private boolean initialized = false;

    // Cached operation field index for faster repeated access
    @CompilationFinal
    private int cachedOperationIndex = -1;

    public HashBasedAbilityCallNode(
            String implementationHash,
            String abilityName,
            String operationName,
            MezzeNode argumentsNode
    ) {
        this.implementationHash = implementationHash;
        this.abilityName = abilityName;
        this.operationName = operationName;
        this.argumentsNode = argumentsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Evaluate arguments first
        Object argsValue = argumentsNode.execute(frame);

        // Initialize on first call (cached for subsequent calls)
        if (!initialized) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            initializeOperation();
        }

        // Build arguments array with closure values
        Object[] closureValues = cachedOperation.getClosureValues();
        int closureLength = closureValues.length;
        Object[] arguments;

        if (closureLength == 0) {
            arguments = new Object[] { argsValue };
        } else {
            arguments = new Object[closureLength + 1];
            System.arraycopy(closureValues, 0, arguments, 0, closureLength);
            arguments[closureLength] = argsValue;
        }

        return callNode.call(arguments);
    }

    @TruffleBoundary
    private void initializeOperation() {
        MezzeContext context = MezzeContext.get(this);

        // Look up implementation by content hash - O(1)
        Object implValue = context.getAbilityImplByHash(implementationHash);

        if (!(implValue instanceof MezzeRecord impl)) {
            throw new RuntimeException(
                "No implementation found for ability " + abilityName +
                " with hash: " + implementationHash +
                ". Got: " + (implValue == null ? "null" : implValue.getClass().getName())
            );
        }

        // Extract operation function using cached index
        int opIndex = cachedOperationIndex;
        if (opIndex < 0) {
            opIndex = impl.getFieldIndex(operationName);
            if (opIndex < 0) {
                throw new RuntimeException(
                    "Ability " + abilityName + " implementation does not have operation: " + operationName
                );
            }
            cachedOperationIndex = opIndex;
        }

        Object opValue;
        try {
            opValue = impl.getField(opIndex);
        } catch (Exception e) {
            throw new RuntimeException(
                "Ability " + abilityName + " implementation does not have operation: " + operationName
            );
        }

        if (!(opValue instanceof MezzeFunction operation)) {
            throw new RuntimeException(
                "Ability " + abilityName + "." + operationName + " is not a function: " + opValue
            );
        }

        cachedOperation = operation;
        callNode = insert(DirectCallNode.create(operation.getCallTarget()));
        initialized = true;
    }

    public static HashBasedAbilityCallNode create(
            String implementationHash,
            String abilityName,
            String operationName,
            MezzeNode argumentsNode
    ) {
        return new HashBasedAbilityCallNode(implementationHash, abilityName, operationName, argumentsNode);
    }
}
