package com.mezze.interpreter.v2.nodes.statemachine;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.nodes.MezzeRootNode;
import com.mezze.interpreter.v2.nodes.continuation.DelimiterStack;
import com.mezze.interpreter.v2.runtime.MezzeLanguage;
import com.mezze.interpreter.v2.nodes.continuation.MezzeContinuation;
import com.mezze.interpreter.v2.nodes.continuation.MezzeContinuationSuspended;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeUnit;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * State machine node for continuation-based effects - V2 OPTIMIZED.
 *
 * A state machine represents a computation that can be suspended and resumed.
 * Each state is a node that executes and produces:
 * - A result value (for COMPUTE/COMPLETE states)
 * - A suspended continuation (for YIELD states)
 *
 * Key optimizations:
 * - All state nodes are pre-converted at parse time
 * - Uses @ExplodeLoop for state dispatch
 * - Caches captured locals for resumption
 * - Direct array indexing for state transitions
 *
 * State types:
 * - COMPUTE: Execute and transition to next state
 * - YIELD: Suspend with value, resumable continuation
 * - COMPLETE: Terminal state, returns final result
 */
@NodeInfo(shortName = "state-machine")
public final class StateMachineNode extends MezzeNode {

    public static final int STATE_COMPUTE = 0;
    public static final int STATE_YIELD = 1;
    public static final int STATE_COMPLETE = 2;

    @Children
    private final MezzeNode[] stateNodes;

    @CompilationFinal(dimensions = 1)
    private final int[] stateTypes;

    @CompilationFinal(dimensions = 1)
    private final int[] nextStateIndices;

    @CompilationFinal
    private final int inputSlot;

    @CompilationFinal
    private final int stateIndexSlot;

    @CompilationFinal(dimensions = 1)
    private final int[] capturedVariableSlots;

    @CompilationFinal(dimensions = 1)
    private final String[] capturedVariableNames;

    @CompilationFinal
    private final int initialStateIndex;

    @CompilationFinal
    private final int stateCount;

    // Branch profile for early return path - rarely taken, JIT optimizes for normal path
    private final InlinedBranchProfile earlyReturnProfile = InlinedBranchProfile.getUncached();

    public StateMachineNode(
            MezzeNode[] stateNodes,
            int[] stateTypes,
            int[] nextStateIndices,
            int inputSlot,
            int stateIndexSlot,
            int[] capturedVariableSlots,
            String[] capturedVariableNames,
            int initialStateIndex
    ) {
        this.stateNodes = stateNodes;
        this.stateTypes = stateTypes;
        this.nextStateIndices = nextStateIndices;
        this.inputSlot = inputSlot;
        this.stateIndexSlot = stateIndexSlot;
        this.capturedVariableSlots = capturedVariableSlots;
        this.capturedVariableNames = capturedVariableNames;
        this.initialStateIndex = initialStateIndex;
        this.stateCount = stateNodes.length;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Set initial state index
        if (stateIndexSlot >= 0) {
            frame.setLong(stateIndexSlot, initialStateIndex);
        }

        // Execute state machine starting from initial state
        return executeFromState(frame, initialStateIndex, MezzeUnit.INSTANCE);
    }

    /**
     * Execute the state machine starting from a given state with an input value.
     */
    @ExplodeLoop
    private Object executeFromState(VirtualFrame frame, int startState, Object input) {
        int currentState = startState;
        Object currentInput = input;

        // Maximum iterations to prevent infinite loops in JIT compilation
        // Actual state machines should complete or yield before this
        for (int iteration = 0; iteration < 1000; iteration++) {
            if (currentState < 0 || currentState >= stateCount) {
                // Invalid state - return current input as result
                return currentInput;
            }

            // Set input for current state
            if (inputSlot >= 0) {
                frame.setObject(inputSlot, currentInput);
            }

            // Execute current state
            MezzeNode stateNode = stateNodes[currentState];
            Object result = stateNode.execute(frame);

            int stateType = stateTypes[currentState];
            int nextState = nextStateIndices[currentState];

            switch (stateType) {
                case STATE_COMPUTE -> {
                    // CRITICAL: Check for early return/exception effects
                    // MezzeContinuationSuspended signals that execution should abort
                    // Uses branch profile - JIT assumes this is rarely taken (cold path)
                    if (result instanceof MezzeContinuationSuspended) {
                        earlyReturnProfile.enter(this);
                        return result;
                    }
                    // COMPUTE: Transition to next state with result as input
                    if (nextState < 0) {
                        return result;
                    }
                    currentState = nextState;
                    currentInput = result;
                }
                case STATE_YIELD -> {
                    // YIELD: Create suspended continuation and return it
                    // First capture locals from frame (JIT-safe)
                    Object[] capturedValues = captureLocals(frame, nextState);
                    // Then create the continuation (boundary)
                    return createSuspendedContinuation(result, capturedValues);
                }
                case STATE_COMPLETE -> {
                    // CRITICAL: Check for early return/exception effects
                    // Uses branch profile - JIT assumes this is rarely taken (cold path)
                    if (result instanceof MezzeContinuationSuspended) {
                        earlyReturnProfile.enter(this);
                        return result;
                    }
                    // COMPLETE: Terminal state, return result
                    return result;
                }
                default -> {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    throw new RuntimeException("Unknown state type: " + stateType);
                }
            }
        }

        // Exceeded max iterations - this shouldn't happen in normal execution
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("State machine exceeded maximum iterations");
    }

    /**
     * Capture locals from frame - JIT-safe, called before TruffleBoundary.
     */
    @ExplodeLoop
    private Object[] captureLocals(VirtualFrame frame, int resumeState) {
        Object[] capturedValues = new Object[capturedVariableSlots.length + 1];

        // First element is the resume state
        capturedValues[0] = (long) resumeState;

        // Capture variable values
        for (int i = 0; i < capturedVariableSlots.length; i++) {
            int slot = capturedVariableSlots[i];
            if (slot >= 0) {
                try {
                    capturedValues[i + 1] = frame.getObject(slot);
                } catch (Exception e) {
                    capturedValues[i + 1] = MezzeUnit.INSTANCE;
                }
            } else {
                capturedValues[i + 1] = MezzeUnit.INSTANCE;
            }
        }

        return capturedValues;
    }

    /**
     * Create a suspended continuation that can be resumed.
     */
    @TruffleBoundary
    private MezzeContinuationSuspended createSuspendedContinuation(
            Object yieldValue,
            Object[] capturedValues
    ) {
        // Create a continuation that resumes this state machine
        CallTarget resumeTarget = createResumeCallTarget();
        MezzeContinuation continuation = MezzeContinuation.create(
                resumeTarget,
                capturedValues,
                0  // delimiter ID - will be set by context
        );

        return new MezzeContinuationSuspended(yieldValue, continuation);
    }

    /**
     * Create a CallTarget for resuming the state machine.
     */
    @TruffleBoundary
    private CallTarget createResumeCallTarget() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        FrameDescriptor fd = builder.build();

        // Create a root node that resumes this state machine
        // We pass null for language since MezzeRootNode handles this gracefully
        // and it allows tests to run without a full polyglot context
        MezzeRootNode rootNode = new MezzeRootNode(
                null,
                fd,
                new ResumeNode(this),
                "state_machine_resume",
                0,
                0
        );

        return rootNode.getCallTarget();
    }

    /**
     * Resume execution from a saved state.
     * Called when a captured continuation is invoked.
     */
    Object resumeExecution(VirtualFrame frame, Object[] capturedValues, Object resumeValue) {
        if (capturedValues.length < 1) {
            return resumeValue;
        }

        // Extract resume state
        int resumeState = ((Number) capturedValues[0]).intValue();

        // Restore captured variables
        for (int i = 0; i < capturedVariableSlots.length && i + 1 < capturedValues.length; i++) {
            int slot = capturedVariableSlots[i];
            if (slot >= 0) {
                frame.setObject(slot, capturedValues[i + 1]);
            }
        }

        // Execute from resume state with resume value as input
        return executeFromState(frame, resumeState, resumeValue);
    }

    /**
     * Node for resuming a state machine from a continuation.
     */
    private static final class ResumeNode extends MezzeNode {
        private final StateMachineNode stateMachine;

        ResumeNode(StateMachineNode stateMachine) {
            this.stateMachine = stateMachine;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();

            // Args layout: [capturedValues...] [resumeValue]
            if (args.length < 2) {
                return MezzeUnit.INSTANCE;
            }

            // Last argument is the resume value (wrapped in { value: x })
            Object resumeValueArg = args[args.length - 1];
            Object resumeValue = resumeValueArg;
            if (resumeValueArg instanceof MezzeRecord record) {
                Object val = record.getField("value");
                if (val != null) {
                    resumeValue = val;
                }
            }

            // All other arguments are captured values
            Object[] capturedValues = new Object[args.length - 1];
            System.arraycopy(args, 0, capturedValues, 0, args.length - 1);

            return stateMachine.resumeExecution(frame, capturedValues, resumeValue);
        }
    }
}
