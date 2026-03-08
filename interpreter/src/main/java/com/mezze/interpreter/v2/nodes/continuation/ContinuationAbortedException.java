package com.mezze.interpreter.v2.nodes.continuation;

/**
 * Exception thrown when a continuation is aborted.
 */
public class ContinuationAbortedException extends RuntimeException {
    public ContinuationAbortedException(String message) {
        super(message);
    }
}
