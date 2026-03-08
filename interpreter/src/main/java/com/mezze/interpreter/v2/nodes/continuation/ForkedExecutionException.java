package com.mezze.interpreter.v2.nodes.continuation;

import com.mezze.interpreter.v2.values.MezzeList;

/**
 * Exception thrown for nested fork operations.
 */
public class ForkedExecutionException extends RuntimeException {
    public final Object continuation;
    public final MezzeList values;

    public ForkedExecutionException(Object continuation, MezzeList values) {
        super("Forked execution");
        this.continuation = continuation;
        this.values = values;
    }
}
