package com.mezze.interpreter.v2.nodes.control;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.nodes.continuation.MezzeContinuationSuspended;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeSuspended;
import com.mezze.interpreter.v2.values.MezzeThunk;
import com.mezze.interpreter.v2.values.MezzeUnit;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.Arrays;

/**
 * Block node - executes a sequence of statements.
 *
 * Returns the value of the last statement, or Unit if empty.
 *
 * NOTE: MezzeContinuationSuspended is treated as a NORMAL VALUE, not as early return.
 * Effect handlers (HandleNode) are responsible for detecting and handling suspensions.
 * This allows suspended continuations to be stored in variables and passed around.
 *
 * JIT Optimizations:
 * - @ExplodeLoop unrolls the loop at compile time
 * - Hot path (normal execution) has zero overhead
 */
@NodeInfo(shortName = "block")
public final class BlockNode extends MezzeNode {

    @Children private final MezzeNode[] statements;

    public BlockNode(MezzeNode[] statements) {
        this.statements = statements;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        if (statements.length == 0) {
            return MezzeUnit.INSTANCE;
        }

        // Execute all but the last statement
        for (int i = 0; i < statements.length - 1; i++) {
            Object result = statements[i].execute(frame);

            // LAZY EFFECTS: If a statement returns MezzeSuspended, compose a continuation
            // with the remaining statements and return a new MezzeSuspended
            if (result instanceof MezzeSuspended suspended) {
                return handleSuspendedEffect(suspended, i, frame.materialize());
            }

            // EARLY RETURN: MezzeContinuationSuspended causes early return from block.
            // This is essential for Exception.throw and Return.return to work correctly.
            // When throw/return is NOT the last statement, remaining statements must be skipped.
            // The handler (perform ToResult...) will detect the suspended continuation and extract the value.
            if (result instanceof MezzeContinuationSuspended) {
                return result;
            }
        }

        // Return the value of the last statement
        Object lastResult = statements[statements.length - 1].execute(frame);

        // If the last statement is suspended, return it as-is
        return lastResult;
    }

    /**
     * Handle suspended effect computations with continuation composition.
     * When an effect call returns MezzeSuspended, we create a composed
     * continuation that will execute the remaining statements when
     * the effect is resolved.
     */
    @TruffleBoundary
    private MezzeSuspended handleSuspendedEffect(
            MezzeSuspended suspended,
            int index,
            MaterializedFrame materializedFrame
    ) {
        // Capture remaining statements for the composed continuation
        MezzeNode[] remainingStatements = Arrays.copyOfRange(
            statements, index + 1, statements.length
        );

        // Create a composed thunk that will:
        // 1. Execute the original suspended effect
        // 2. Execute remaining statements
        // 3. Return the final result
        ComposedBlockThunk composedThunk = new ComposedBlockThunk(
            suspended.getComputation(),
            remainingStatements,
            materializedFrame
        );

        // Return a new MezzeSuspended with the composed thunk
        return new MezzeSuspended(
            composedThunk,
            suspended.getUnhandledEffects(),
            suspended.getCapturedHandlers()
        );
    }

    /**
     * A thunk that composes the original suspended computation with remaining statements.
     * This is a public static class so MezzeSuspended can detect and handle it.
     */
    public static final class ComposedBlockThunk extends MezzeThunk {
        private final MezzeThunk originalThunk;
        private final MezzeNode[] remainingStatements;
        private final MaterializedFrame materializedFrame;

        public ComposedBlockThunk(
                MezzeThunk originalThunk,
                MezzeNode[] remainingStatements,
                MaterializedFrame materializedFrame
        ) {
            super();  // Use protected constructor
            this.originalThunk = originalThunk;
            this.remainingStatements = remainingStatements;
            this.materializedFrame = materializedFrame;
        }

        @Override
        public boolean isDeferredEffectCall() {
            // Check the original thunk
            return originalThunk.isDeferredEffectCall();
        }

        @Override
        public String getEffectName() {
            return originalThunk.getEffectName();
        }

        @Override
        public String getOperationName() {
            return originalThunk.getOperationName();
        }

        @Override
        public Object getArguments() {
            return originalThunk.getArguments();
        }

        public MezzeThunk getOriginalThunk() {
            return originalThunk;
        }

        public MezzeNode[] getRemainingStatements() {
            return remainingStatements;
        }

        public MaterializedFrame getMaterializedFrame() {
            return materializedFrame;
        }

        /**
         * Check if this is a composed block thunk.
         */
        public boolean isComposedBlockThunk() {
            return true;
        }
    }
}
