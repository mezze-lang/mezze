package com.mezze.interpreter.nodes.continuation;

/**
 * Exception thrown when a continuation is aborted.
 */
public class ContinuationAbortedException extends RuntimeException {
    public ContinuationAbortedException(String message) {
        super(message);
    }
}
