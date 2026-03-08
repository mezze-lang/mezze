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

    // Cached frame descriptor for resume targets - captured lazily on first execution
    @CompilationFinal
    private FrameDescriptor cachedFrameDescriptor;

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
        // Capture frame descriptor on first execution for use in resume targets
        if (cachedFrameDescriptor == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cachedFrameDescriptor = frame.getFrameDescriptor();
        }

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

            // CRITICAL: Set context BEFORE executing state so capture can find us
            int nextState = nextStateIndices[currentState];
            setExecutionContext(frame, nextState);

            // Execute current state
            MezzeNode stateNode = stateNodes[currentState];
            Object result = stateNode.execute(frame);

            int stateType = stateTypes[currentState];
            // nextState already computed above for context setting

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
     *
     * OPTIMIZATION: Try typed reads first to preserve unboxed primitives.
     * This avoids boxing overhead when capturing Long/Double/Boolean values.
     * The try-catch pattern is JIT-friendly as Truffle eliminates the exception
     * handling overhead after profiling shows which types are used.
     */
    @ExplodeLoop
    private Object[] captureLocals(VirtualFrame frame, int resumeState) {
        Object[] capturedValues = new Object[capturedVariableSlots.length + 1];

        // First element is the resume state
        capturedValues[0] = (long) resumeState;

        // Capture variable values - try typed reads first for unboxed primitives
        for (int i = 0; i < capturedVariableSlots.length; i++) {
            int slot = capturedVariableSlots[i];
            if (slot >= 0) {
                capturedValues[i + 1] = captureSlotValue(frame, slot);
            } else {
                capturedValues[i + 1] = MezzeUnit.INSTANCE;
            }
        }

        return capturedValues;
    }

    /**
     * Capture a single slot value using getValue() which handles all types.
     */
    private Object captureSlotValue(VirtualFrame frame, int slot) {
        // getValue() returns the value regardless of type (Object, Long, Double, Boolean)
        Object value = frame.getValue(slot);
        return value != null ? value : MezzeUnit.INSTANCE;
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
     * Public so ContinuationCaptureNode can access it.
     */
    @TruffleBoundary
    public CallTarget createResumeCallTarget() {
        // Use the cached frame descriptor from the original execution.
        // This ensures the resume frame has all the slots that the state machine's
        // internal nodes expect (not just captured variables).
        FrameDescriptor fd = cachedFrameDescriptor;

        if (fd == null) {
            // Fallback: build a frame descriptor with enough slots.
            // This shouldn't normally happen since execute() captures it first.
            int numSlots = Math.max(
                    Math.max(inputSlot + 1, stateIndexSlot + 1),
                    capturedVariableSlots.length > 0
                            ? java.util.Arrays.stream(capturedVariableSlots).max().getAsInt() + 1
                            : 0
            );

            FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
            for (int i = 0; i < numSlots; i++) {
                builder.addSlot(com.oracle.truffle.api.frame.FrameSlotKind.Object, "slot_" + i, null);
            }
            fd = builder.build();
        }

        // Create a root node that resumes this state machine
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
     * Set the execution context so capture can find us.
     * This captures the state machine and next state index.
     * Split into JIT-safe capture + boundary-safe context set.
     */
    private void setExecutionContext(VirtualFrame frame, int nextStateIndex) {
        // Capture locals before boundary (JIT-safe)
        Object[] capturedLocals = captureLocals(frame, nextStateIndex);
        // Then set context (boundary)
        setExecutionContextBoundary(nextStateIndex, capturedLocals);
    }

    @TruffleBoundary
    private void setExecutionContextBoundary(int nextStateIndex, Object[] capturedLocals) {
        StateMachineContext.ExecutionSnapshot snapshot = new StateMachineContext.ExecutionSnapshot(
                this, null, nextStateIndex, capturedLocals
        );
        StateMachineContext.setCurrentContext(snapshot);
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

        // Restore captured variables with correct types
        // captureSlotValue preserves primitives, so we need to restore them as primitives
        for (int i = 0; i < capturedVariableSlots.length && i + 1 < capturedValues.length; i++) {
            int slot = capturedVariableSlots[i];
            if (slot >= 0) {
                restoreSlotValue(frame, slot, capturedValues[i + 1]);
            }
        }

        // Execute from resume state with resume value as input
        return executeFromState(frame, resumeState, resumeValue);
    }

    /**
     * Restore a slot value with correct type to match how it was captured.
     * This ensures that variables captured as Long/Double/Boolean are restored
     * as primitives so typed reads will work correctly.
     */
    private void restoreSlotValue(VirtualFrame frame, int slot, Object value) {
        if (value instanceof Long l) {
            frame.setLong(slot, l);
        } else if (value instanceof Double d) {
            frame.setDouble(slot, d);
        } else if (value instanceof Boolean b) {
            frame.setBoolean(slot, b);
        } else {
            frame.setObject(slot, value);
        }
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
