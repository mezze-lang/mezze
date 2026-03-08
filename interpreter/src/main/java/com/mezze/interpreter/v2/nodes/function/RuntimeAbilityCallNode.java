package com.mezze.interpreter.v2.nodes.function;

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
 * Runtime ability method call node for auto-generated codec implementations.
 *
 * Unlike AbilityCallNode which has a statically-resolved impl, this node
 * looks up the ability impl at runtime by ability name. This is needed for
 * codec abilities (Encode, Decode, EncodeFormat, DecodeFormat) where the
 * typechecker generates implementations dynamically and may not populate
 * resolvedAbilities for all calls.
 *
 * The node caches the impl after first lookup for subsequent calls.
 */
@NodeInfo(shortName = "runtime-ability-call")
public final class RuntimeAbilityCallNode extends MezzeNode {

    @CompilationFinal
    private final String abilityName;

    @CompilationFinal
    private final String operationName;

    // Content hash for O(1) lookup when available
    @CompilationFinal
    private final String contentHash;

    @Child
    private MezzeNode argumentsNode;

    @Child
    private DirectCallNode callNode;

    @CompilationFinal
    private MezzeFunction cachedOperation;

    @CompilationFinal
    private boolean initialized = false;

    // Cached operation field index for faster repeated access
    @CompilationFinal
    private int cachedOperationIndex = -1;

    public RuntimeAbilityCallNode(String abilityName, String operationName, MezzeNode argumentsNode) {
        this(abilityName, operationName, argumentsNode, null);
    }

    public RuntimeAbilityCallNode(String abilityName, String operationName, MezzeNode argumentsNode, String contentHash) {
        this.abilityName = abilityName;
        this.operationName = operationName;
        this.argumentsNode = argumentsNode;
        this.contentHash = contentHash;
    }

    public static RuntimeAbilityCallNode create(String abilityName, String operationName, MezzeNode argumentsNode) {
        return new RuntimeAbilityCallNode(abilityName, operationName, argumentsNode, null);
    }

    public static RuntimeAbilityCallNode create(String abilityName, String operationName, MezzeNode argumentsNode, String contentHash) {
        return new RuntimeAbilityCallNode(abilityName, operationName, argumentsNode, contentHash);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Evaluate arguments first
        Object argsValue = argumentsNode.execute(frame);

        // Look up and cache the operation on first execution
        if (!initialized) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            MezzeFunction operation = lookupOperation();
            if (operation == null) {
                // Get list of registered ability impls for debugging
                MezzeContext context = MezzeContext.get(this);
                String registeredImpls = context.getRegisteredAbilityNames();
                throw new RuntimeException(
                    "No implementation found for ability " + abilityName + "." + operationName +
                    ". Registered ability impls: " + registeredImpls
                );
            }
            cachedOperation = operation;
            callNode = insert(DirectCallNode.create(operation.getCallTarget()));
            initialized = true;
        }

        // Get closure values from cached operation
        Object[] closureValues = cachedOperation.getClosureValues();
        int closureLength = closureValues.length;

        // Build arguments array
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
    private MezzeFunction lookupOperation() {
        MezzeContext context = MezzeContext.get(this);

        // Prefer content hash lookup (O(1), collision-free)
        Object implValue = null;
        if (contentHash != null) {
            implValue = context.lookupGlobal(contentHash);
        }

        // Fall back to ability name lookup if content hash didn't work
        if (implValue == null) {
            implValue = context.lookupAbilityImpl(abilityName);
        }

        if (!(implValue instanceof MezzeRecord impl)) {
            return null;
        }

        // Use cached operation index if available, otherwise compute and cache it
        int opIndex = cachedOperationIndex;
        if (opIndex < 0) {
            opIndex = impl.getFieldIndex(operationName);
            if (opIndex >= 0) {
                cachedOperationIndex = opIndex;
            }
        }

        // Get the operation function (using index when available)
        Object opValue;
        try {
            opValue = opIndex >= 0 ? impl.getField(opIndex) : impl.getFieldByName(operationName);
            if (opValue instanceof MezzeFunction func) {
                return func;
            }
        } catch (com.oracle.truffle.api.interop.UnknownIdentifierException e) {
            // Field not found
            return null;
        }

        return null;
    }
}
