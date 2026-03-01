package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeCont;
import com.mezze.interpreter.v2.values.MezzeContinuation;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.interpreter.v2.values.MezzeList;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * __native_continuation_fork - Multi-shot continuation resume.
 *
 * Resumes the same continuation multiple times with different values,
 * collecting all results. This enables nondeterministic choice (amb/backtracking).
 *
 * Nested Fork Handling:
 * When fork is called from within a forked continuation, it throws
 * ForkedExecutionException. The top-level fork catches this and
 * recursively forks, properly handling the cartesian product.
 *
 * Takes: { delim: MezzeCont, cont: ({ value: 'a } -> 'b), values: ['a] }
 * Returns: ['b] - array of results from each fork
 */
@NodeInfo(shortName = "cont-fork")
public final class ContinuationForkNode extends MezzeNode {

    @Child
    private MezzeNode argsNode;

    private final InlinedBranchProfile errorProfile = InlinedBranchProfile.getUncached();
    private final InlinedBranchProfile emptyProfile = InlinedBranchProfile.getUncached();
    private final InlinedBranchProfile nestedForkProfile = InlinedBranchProfile.getUncached();

    public ContinuationForkNode(MezzeNode argsNode) {
        this.argsNode = argsNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object args = argsNode.execute(frame);

        if (!(args instanceof MezzeRecord record)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_fork expects record argument");
        }

        Object delimObj = record.getField("delim");
        if (!(delimObj instanceof MezzeCont)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_fork expects 'delim' field as MezzeCont");
        }

        Object contObj = record.getField("cont");
        Object valuesObj = record.getField("values");

        if (!(valuesObj instanceof MezzeList values)) {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_fork expects 'values' field as array");
        }

        // Empty values - abort
        if (values.size() == 0) {
            emptyProfile.enter(this);
            throw new ContinuationAbortedException("Cannot fork with empty values array");
        }

        // Check for nested fork
        if (DelimiterStack.isInsideFork()) {
            nestedForkProfile.enter(this);
            // Nested fork - throw exception to be caught by outer fork
            throw new ForkedExecutionException(contObj, values);
        }

        // Top-level fork - execute continuation with each value
        return executeFork(contObj, values);
    }

    /**
     * Execute the fork operation.
     */
    @TruffleBoundary
    private MezzeList executeFork(Object cont, MezzeList values) {
        List<Object> results = new ArrayList<>();
        boolean wasInsideFork = DelimiterStack.isInsideFork();

        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);

            try {
                DelimiterStack.setInsideFork(true);
                Object result = resumeContinuation(cont, value);
                results.add(result);
            } catch (ForkedExecutionException e) {
                // Nested fork - recursively fork with nested values
                MezzeList nestedResults = executeFork(e.continuation, e.values);
                // Add all nested results (cartesian product)
                for (int j = 0; j < nestedResults.size(); j++) {
                    results.add(nestedResults.get(j));
                }
            } catch (ContinuationAbortedException e) {
                // This path aborted - skip it
            } finally {
                DelimiterStack.setInsideFork(wasInsideFork);
            }
        }

        return new MezzeList(results.toArray());
    }

    /**
     * Resume a continuation with a value.
     */
    @TruffleBoundary
    private Object resumeContinuation(Object cont, Object value) {
        MezzeRecord valueRecord = MezzeRecord.fromFields(
                new String[]{"value"},
                new Object[]{value}
        );

        if (cont instanceof MezzeFunction func) {
            CallTarget callTarget = func.getCallTarget();
            Object[] closureValues = func.getClosureValues();
            int closureLen = closureValues.length;
            Object[] callArgs = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, callArgs, 0, closureLen);
            callArgs[closureLen] = valueRecord;
            return callTarget.call(callArgs);
        }

        if (cont instanceof MezzeContinuation c) {
            return c.resume(valueRecord);
        }

        throw new RuntimeException("Invalid continuation type: " + cont.getClass().getName());
    }

    public static ContinuationForkNode create(MezzeNode argsNode) {
        return new ContinuationForkNode(argsNode);
    }
}

/**
 * Exception thrown for nested fork operations.
 */
class ForkedExecutionException extends RuntimeException {
    final Object continuation;
    final MezzeList values;

    ForkedExecutionException(Object continuation, MezzeList values) {
        super("Forked execution");
        this.continuation = continuation;
        this.values = values;
    }
}

/**
 * Exception thrown when a continuation is aborted.
 */
class ContinuationAbortedException extends RuntimeException {
    ContinuationAbortedException(String message) {
        super(message);
    }
}
