package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * JIT-optimized invoke node for dictionary-passing polymorphic function calls.
 *
 * This is the full dictionary-passing implementation for polymorphic functions
 * with ability constraints. The key insight is that:
 *
 * 1. At compile time (AST conversion), we know the resolved ability implementations
 *    via FunctionCall.resolvedAbilities (mapping ability names to content hashes)
 *
 * 2. At runtime, when we call a polymorphic function, we get the callee's
 *    MezzeFunction which contains abilitySlotRequirements - the specific
 *    (abilityName, operationName, slotIndex) tuples the callee needs
 *
 * 3. We match caller's resolved abilities by ability name to callee's requirements,
 *    extract the operation functions from the implementation records, and pass
 *    them as extra arguments after the record argument
 *
 * Argument layout: [closure0, ..., closureN, recordArg, abilityOp0, ..., abilityOpM]
 *
 * Performance characteristics:
 * - Ability implementations cached by content hash (O(1) lookup)
 * - Operation functions extracted and cached per call-site shape
 * - DirectCallNode enables JIT inlining after warmup
 * - @CompilationFinal arrays enable constant folding
 *
 * This is the "full best-in-class" implementation - no fallbacks, no runtime
 * string matching in hot paths after JIT compilation.
 */
@NodeInfo(shortName = "dict-invoke")
public final class DictionaryPassingInvokeNode extends MezzeNode {

    @Child
    private MezzeNode functionNode;

    @Child
    private MezzeNode argumentsNode;

    /**
     * Resolved ability bindings for this call site: paired (abilityName, implHash).
     * Replaces the previous parallel `String[] abilityNames` + `Hash[] implementationHashes`
     * arrays — the typed pairing prevents an index-drift bug between the two.
     */
    @CompilationFinal(dimensions = 1)
    private final AbilityBinding[] bindings;

    /** A single ability/impl pair as resolved by the typechecker. */
    public static final class AbilityBinding {
        public final String abilityName;
        public final Hash implHash;
        public AbilityBinding(String abilityName, Hash implHash) {
            this.abilityName = abilityName;
            this.implHash = implHash;
        }
    }

    private static AbilityBinding[] zip(String[] abilityNames, Hash[] implementationHashes) {
        if (abilityNames == null || implementationHashes == null) return new AbilityBinding[0];
        if (abilityNames.length != implementationHashes.length) {
            throw new IllegalArgumentException(
                "abilityNames.length (" + abilityNames.length + ") != implementationHashes.length (" + implementationHashes.length + ")"
            );
        }
        AbilityBinding[] out = new AbilityBinding[abilityNames.length];
        for (int i = 0; i < abilityNames.length; i++) {
            out[i] = new AbilityBinding(abilityNames[i], implementationHashes[i]);
        }
        return out;
    }

    // Cached DirectCallNode for monomorphic call sites
    @Child
    private DirectCallNode callNode;

    @CompilationFinal
    private MezzeFunction cachedFunction;

    // Cached operation functions indexed by the callee's slot requirements
    // This is populated on first call when we know the callee's requirements
    @CompilationFinal(dimensions = 1)
    private MezzeFunction[] cachedOperations;

    // Number of ability operations the callee needs
    @CompilationFinal
    private int abilityOperationCount = -1;

    // Cached implementation records (by content hash)
    // Resolved once and reused for extracting operations
    @CompilationFinal(dimensions = 1)
    private MezzeRecord[] cachedImplementations;

    // Cached operation field indices for faster field access
    @CompilationFinal(dimensions = 1)
    private int[] cachedOperationIndices;

    public DictionaryPassingInvokeNode(
            MezzeNode functionNode,
            MezzeNode argumentsNode,
            String[] abilityNames,
            Hash[] implementationHashes
    ) {
        this.functionNode = functionNode;
        this.argumentsNode = argumentsNode;
        this.bindings = zip(abilityNames, implementationHashes);
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Evaluate function and arguments
        Object funcValue = functionNode.execute(frame);
        Object argValue = argumentsNode.execute(frame);

        if (!(funcValue instanceof MezzeFunction function)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Expected function, got: " +
                (funcValue == null ? "null" : funcValue.getClass().getName()));
        }

        // Check if callee has ability requirements
        MezzeFunction.AbilitySlotRequirement[] requirements = function.getAbilitySlotRequirements();

        // Fast path: no ability requirements - just call normally
        if (requirements == null || requirements.length == 0) {
            return invokeWithoutAbilities(function, argValue);
        }

        // Resolve ability operations on first execution or when function changes
        if (abilityOperationCount < 0 || function != cachedFunction) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            resolveAbilityOperations(function, requirements);
        }

        // Build full argument array with ability operations
        Object[] closureValues = function.getClosureValues();
        int closureLength = closureValues.length;
        int totalArgs = closureLength + 1 + abilityOperationCount;
        Object[] fullArgs = new Object[totalArgs];

        // Copy closure values
        for (int i = 0; i < closureLength; i++) {
            fullArgs[i] = closureValues[i];
        }

        // Add record argument
        fullArgs[closureLength] = argValue;

        // Add ability operation functions
        for (int i = 0; i < abilityOperationCount; i++) {
            fullArgs[closureLength + 1 + i] = cachedOperations[i];
        }

        // Use cached call node
        if (callNode != null) {
            return callNode.call(fullArgs);
        }

        // First call - create call node
        CompilerDirectives.transferToInterpreterAndInvalidate();
        callNode = insert(DirectCallNode.create(function.getCallTarget()));
        return callNode.call(fullArgs);
    }

    /**
     * Simple invocation when callee has no ability requirements.
     */
    private Object invokeWithoutAbilities(MezzeFunction function, Object argValue) {
        Object[] closureValues = function.getClosureValues();
        int closureLength = closureValues.length;

        Object[] fullArgs = new Object[closureLength + 1];
        for (int i = 0; i < closureLength; i++) {
            fullArgs[i] = closureValues[i];
        }
        fullArgs[closureLength] = argValue;

        if (callNode != null && function == cachedFunction) {
            return callNode.call(fullArgs);
        }

        CompilerDirectives.transferToInterpreterAndInvalidate();
        cachedFunction = function;
        callNode = insert(DirectCallNode.create(function.getCallTarget()));
        return callNode.call(fullArgs);
    }

    /**
     * Resolve ability operations based on the callee's requirements.
     * This matches caller's resolved abilities (by ability name) to callee's
     * slot requirements, extracts the operation functions, and caches them.
     */
    @TruffleBoundary
    private void resolveAbilityOperations(
            MezzeFunction function,
            MezzeFunction.AbilitySlotRequirement[] requirements
    ) {
        cachedFunction = function;
        abilityOperationCount = requirements.length;

        // Build map of ability name -> implementation hash for fast lookup
        Map<String, Hash> abilityToHash = new HashMap<>();
        for (AbilityBinding b : bindings) {
            abilityToHash.put(b.abilityName, b.implHash);
        }

        // Resolve implementation records
        MezzeContext context = MezzeContext.get(this);
        Map<Hash, MezzeRecord> hashToImpl = new HashMap<>();

        for (AbilityBinding b : bindings) {
            Hash hash = b.implHash;
            if (hash != null && !hashToImpl.containsKey(hash)) {
                Object implValue = context.getAbilityImplByHash(hash);
                if (implValue instanceof MezzeRecord impl) {
                    hashToImpl.put(hash, impl);
                }
            }
        }

        // Extract operation functions in the order the callee needs them
        cachedOperations = new MezzeFunction[requirements.length];

        // Initialize cached indices
        if (cachedOperationIndices == null || cachedOperationIndices.length != requirements.length) {
            cachedOperationIndices = new int[requirements.length];
            for (int i = 0; i < cachedOperationIndices.length; i++) {
                cachedOperationIndices[i] = -1;
            }
        }

        for (int i = 0; i < requirements.length; i++) {
            MezzeFunction.AbilitySlotRequirement req = requirements[i];
            Hash hash = abilityToHash.get(req.abilityName);

            if (hash == null) {
                throw new RuntimeException(
                    "No resolved ability for constraint: " + req.abilityName +
                    ". Available: " + abilityToHash.keySet());
            }

            MezzeRecord impl = hashToImpl.get(hash);
            if (impl == null) {
                throw new RuntimeException(
                    "Implementation not found for ability " + req.abilityName +
                    " with hash: " + hash.getHex());
            }

            // Use cached field index if available
            int opIndex = cachedOperationIndices[i];
            if (opIndex < 0) {
                opIndex = impl.getFieldIndex(req.operationName);
                if (opIndex < 0) {
                    throw new RuntimeException(
                        "Ability implementation " + req.abilityName +
                        " does not have operation: " + req.operationName);
                }
                cachedOperationIndices[i] = opIndex;
            }

            // Extract the operation function
            Object opValue;
            try {
                opValue = impl.getField(opIndex);
            } catch (Exception e) {
                throw new RuntimeException(
                    "Ability implementation " + req.abilityName +
                    " does not have operation: " + req.operationName);
            }

            if (!(opValue instanceof MezzeFunction func)) {
                throw new RuntimeException(
                    "Ability operation " + req.abilityName + "." + req.operationName +
                    " is not a function: " + opValue);
            }

            cachedOperations[i] = func;
        }
    }

    public static DictionaryPassingInvokeNode create(
            MezzeNode functionNode,
            MezzeNode argumentsNode,
            String[] abilityNames,
            Hash[] implementationHashes
    ) {
        return new DictionaryPassingInvokeNode(functionNode, argumentsNode, abilityNames, implementationHashes);
    }
}
