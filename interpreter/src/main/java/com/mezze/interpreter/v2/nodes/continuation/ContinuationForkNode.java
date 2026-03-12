package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.interpreter.v2.nodes.MezzeNode;

import com.mezze.interpreter.v2.values.MezzeFunction;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.datastructures.seq.MezzeSeq;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;


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

    // Pre-computed field name array for value records - shared across all instances
    // Avoids repeated array allocation for every resume call
    private static final String[] VALUE_FIELD_NAMES = new String[]{"value"};

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

        // Handle both MezzeList and Object[] (raw array)
        MezzeList values;
        if (valuesObj instanceof MezzeList ml) {
            values = ml;
        } else if (valuesObj instanceof MezzeSeq seq) {
            MezzeList result = MezzeList.empty();
            for (int i = seq.size() - 1; i >= 0; i--) {
                result = result.prepend(seq.get(i));
            }
            values = result;
        } else if (valuesObj instanceof Object[] arr) {
            values = MezzeList.of(arr);
        } else {
            errorProfile.enter(this);
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("__native_continuation_fork expects 'values' field as array, got: " +
                (valuesObj != null ? valuesObj.getClass().getSimpleName() : "null"));
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
        // After collecting all results, throw ForkedExecutionException so the effect handler
        // can catch it and return the results. We don't return directly because that would
        // assign the result list to the variable at the fork call site.
        MezzeSeq results = executeFork(contObj, values);
        throw new ForkedExecutionException(contObj, results);
    }

    /**
     * Execute the fork operation - OPTIMIZED.
     *
     * Uses pre-allocated result array instead of ArrayList to reduce allocations.
     * Array grows 2x when needed (amortized O(1) append).
     */
    @TruffleBoundary
    private MezzeSeq executeFork(Object cont, MezzeList values) {
        // Pre-allocate with extra space for nested forks (common in Choice effect)
        Object[] results = new Object[values.size() * 2];
        int resultCount = 0;
        boolean wasInsideFork = DelimiterStack.isInsideFork();

        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);

            try {
                DelimiterStack.setInsideFork(true);
                Object result = resumeContinuation(cont, value);
                // Ensure capacity
                if (resultCount >= results.length) {
                    results = growArray(results);
                }
                results[resultCount++] = result;
            } catch (ForkedExecutionException e) {
                // Nested fork - recursively fork with nested values
                // e.values is MezzeList for nested forks (input values)
                MezzeSeq nestedResults = executeFork(e.continuation, (MezzeList) e.values);
                // Add all nested results (cartesian product)
                for (int j = 0; j < nestedResults.size(); j++) {
                    if (resultCount >= results.length) {
                        results = growArray(results);
                    }
                    results[resultCount++] = nestedResults.get(j);
                }
            } catch (ContinuationAbortedException e) {
                // This path aborted - skip it (no result added)
            } finally {
                DelimiterStack.setInsideFork(wasInsideFork);
            }
        }

        // Trim to actual size if needed
        if (resultCount < results.length) {
            Object[] trimmed = new Object[resultCount];
            System.arraycopy(results, 0, trimmed, 0, resultCount);
            return MezzeSeq.fromArray(trimmed);
        }
        return MezzeSeq.fromArray(results);
    }

    /**
     * Grow array by 2x - standard doubling strategy.
     */
    private static Object[] growArray(Object[] arr) {
        Object[] newArr = new Object[arr.length * 2];
        System.arraycopy(arr, 0, newArr, 0, arr.length);
        return newArr;
    }

    /**
     * Resume a continuation with a value - OPTIMIZED.
     *
     * Uses cached field names and specialized call paths for small closures.
     */
    @TruffleBoundary
    private Object resumeContinuation(Object cont, Object value) {
        // Use cached field names - avoids repeated array allocation
        MezzeRecord valueRecord = MezzeRecord.fromFields(
                VALUE_FIELD_NAMES,
                new Object[]{value}
        );

        if (cont instanceof MezzeFunction func) {
            return callFunctionFast(func, valueRecord);
        }

        if (cont instanceof MezzeContinuation c) {
            return c.resume(valueRecord);
        }

        throw new RuntimeException("Invalid continuation type: " + cont.getClass().getName());
    }

    /**
     * Call function with closure values + argument - OPTIMIZED.
     *
     * Specialized paths for common closure sizes (0, 1, 2, 3) avoid
     * array allocation overhead. JIT can inline these hot paths.
     */
    private Object callFunctionFast(MezzeFunction func, MezzeRecord arg) {
        CallTarget callTarget = func.getCallTarget();
        Object[] closureValues = func.getClosureValues();
        int closureLen = closureValues.length;

        // Specialized paths for common closure sizes - avoids array allocation
        return switch (closureLen) {
            case 0 -> callTarget.call(arg);
            case 1 -> callTarget.call(closureValues[0], arg);
            case 2 -> callTarget.call(closureValues[0], closureValues[1], arg);
            case 3 -> callTarget.call(closureValues[0], closureValues[1], closureValues[2], arg);
            default -> {
                // General case: allocate array
                Object[] callArgs = new Object[closureLen + 1];
                System.arraycopy(closureValues, 0, callArgs, 0, closureLen);
                callArgs[closureLen] = arg;
                yield callTarget.call(callArgs);
            }
        };
    }

    public static ContinuationForkNode create(MezzeNode argsNode) {
        return new ContinuationForkNode(argsNode);
    }
}

