package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * Ability method call node - FULLY JIT-COMPILABLE.
 *
 * Key optimizations:
 * 1. Pre-computed operation index for O(1) field access
 * 2. DirectCallNode for inlined calls
 * 3. Pre-computed argument count for @ExplodeLoop
 * 4. NO @TruffleBoundary, NO runtime hash lookups
 *
 * The impl is resolved STATICALLY by the typechecker and passed as
 * a node child.
 */
@NodeInfo(shortName = "ability-call")
public final class AbilityCallNode extends MezzeNode {

    @CompilationFinal
    private final String operationName;  // For error messages only

    @CompilationFinal
    private final int operationIndex;  // Pre-computed field index

    @Child
    private MezzeNode implNode;  // Evaluates to the impl record

    @Children
    private final MezzeNode[] argumentNodes;

    @CompilationFinal
    private final int argumentCount;

    @Child
    private DirectCallNode callNode;

    @CompilationFinal
    private boolean initialized = false;

    // Error branch profiles - tell JIT these paths are cold
    private final InlinedBranchProfile notRecordProfile = InlinedBranchProfile.getUncached();
    private final InlinedBranchProfile notFunctionProfile = InlinedBranchProfile.getUncached();

    public AbilityCallNode(
            String operationName,
            int operationIndex,
            MezzeNode implNode,
            MezzeNode[] argumentNodes
    ) {
        this.operationName = operationName;
        this.operationIndex = operationIndex;
        this.implNode = implNode;
        this.argumentNodes = argumentNodes;
        this.argumentCount = argumentNodes.length;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Get the impl record
        Object implValue = implNode.execute(frame);

        if (!(implValue instanceof MezzeRecord impl)) {
            notRecordProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Impl must be a record");
        }

        // Get operation function by pre-computed index (O(1))
        Object opValue = impl.getField(operationIndex);

        if (!(opValue instanceof MezzeFunction operation)) {
            notFunctionProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Operation " + operationName + " is not a function");
        }

        // Initialize DirectCallNode on first execution
        if (!initialized) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            callNode = insert(DirectCallNode.create(operation.getCallTarget()));
            initialized = true;
        }

        // Get closure info
        Object[] closureValues = operation.getClosureValues();
        int closureLength = closureValues.length;

        // FAST PATH: No closure - skip closure handling
        if (closureLength == 0) {
            Object[] arguments = new Object[argumentCount];
            // @ExplodeLoop unrolls this
            for (int i = 0; i < argumentCount; i++) {
                arguments[i] = argumentNodes[i].execute(frame);
            }
            return callNode.call(arguments);
        }

        // Build arguments array
        Object[] arguments = new Object[closureLength + argumentCount];

        // Copy closure values with inline loop (JIT unrolls better than arraycopy)
        for (int i = 0; i < closureLength; i++) {
            arguments[i] = closureValues[i];
        }

        // Evaluate and copy argument values - @ExplodeLoop unrolls this
        for (int i = 0; i < argumentCount; i++) {
            arguments[closureLength + i] = argumentNodes[i].execute(frame);
        }

        // Call via DirectCallNode - INLINED BY JIT!
        return callNode.call(arguments);
    }
}
