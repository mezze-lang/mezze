package com.mezze.interpreter.nodes.continuation;

/**
 * Exception thrown for fork operations.
 *
 * Used in two scenarios:
 * 1. Nested fork: values is MezzeList of input values to fork over
 * 2. Top-level fork: values is MezzeSeq of results from fork execution
 */
public class ForkedExecutionException extends RuntimeException {
    public final Object continuation;
    public final Object values;  // MezzeList for nested, MezzeSeq for top-level results

    public ForkedExecutionException(Object continuation, Object values) {
        super("Forked execution");
        this.continuation = continuation;
        this.values = values;
    }
}
