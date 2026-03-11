package com.mezze.interpreter.v2.nodes.ability;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Ability method call node that reads the ability implementation from a frame slot.
 *
 * This is used for polymorphic functions with ability constraints. Instead of
 * looking up the ability implementation globally at runtime, this node reads
 * the implementation from a frame slot where it was placed by the caller.
 *
 * This enables proper dictionary-passing semantics for type class-like abilities,
 * where the implementation is determined by the concrete type at the call site.
 *
 * Example:
 * <pre>
 * pub let encode_all: List 'a -> List Bytes with Encode Json 'a = xs ->
 *     map {f = {x} -> Encode.encode {val = x, config = default_config}, xs}
 * </pre>
 *
 * Here, the `Encode.encode` call needs to use the implementation for `'a` which
 * is passed from the caller when they instantiate `encode_all` with a concrete type.
 */
@NodeInfo(shortName = "dict-ability-call")
public final class DictionaryAbilityCallNode extends MezzeNode {

    // Frame slot where the ability dictionary (impl record) is stored
    @CompilationFinal
    private final int dictionarySlot;

    // Operation name to call on the dictionary
    @CompilationFinal
    private final String operationName;

    // Ability name for error messages
    @CompilationFinal
    private final String abilityName;

    @Child
    private MezzeNode argumentsNode;

    @Child
    private DirectCallNode callNode;

    @CompilationFinal
    private boolean initialized = false;

    // Cached operation field index for faster repeated access
    @CompilationFinal
    private int cachedOperationIndex = -1;

    public DictionaryAbilityCallNode(int dictionarySlot, String abilityName, String operationName, MezzeNode argumentsNode) {
        this.dictionarySlot = dictionarySlot;
        this.abilityName = abilityName;
        this.operationName = operationName;
        this.argumentsNode = argumentsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Evaluate arguments first
        Object argsValue = argumentsNode.execute(frame);

        // Read the ability implementation from the frame slot
        Object implValue = frame.getObject(dictionarySlot);

        if (!(implValue instanceof MezzeRecord impl)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException(
                "Ability dictionary slot " + dictionarySlot + " does not contain a record. " +
                "Expected implementation for " + abilityName + " but got: " +
                (implValue == null ? "null" : implValue.getClass().getName())
            );
        }

        // Get the operation function from the impl record using cached index
        int opIndex = cachedOperationIndex;
        if (opIndex < 0) {
            opIndex = impl.getFieldIndex(operationName);
            if (opIndex < 0) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeException(
                    "Ability " + abilityName + " implementation does not have operation: " + operationName
                );
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cachedOperationIndex = opIndex;
        }

        Object opValue;
        try {
            opValue = impl.getField(opIndex);
        } catch (Exception e) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException(
                "Ability " + abilityName + " implementation does not have operation: " + operationName
            );
        }

        if (!(opValue instanceof MezzeFunction operation)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException(
                "Ability " + abilityName + "." + operationName + " is not a function: " + opValue
            );
        }

        // Initialize call node on first execution
        if (!initialized) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            callNode = insert(DirectCallNode.create(operation.getCallTarget()));
            initialized = true;
        }

        // Build arguments array (closure values + operation argument)
        Object[] closureValues = operation.getClosureValues();
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

    public static DictionaryAbilityCallNode create(int dictionarySlot, String abilityName, String operationName, MezzeNode argumentsNode) {
        return new DictionaryAbilityCallNode(dictionarySlot, abilityName, operationName, argumentsNode);
    }
}
