package com.mezze.interpreter.v2.nodes.effect;

import com.oracle.truffle.api.nodes.ControlFlowException;

/**
 * Exception thrown by finalize_scope to signal that the scoped block should be retried.
 *
 * This is used by STM to implement automatic retry on transaction conflicts.
 * When finalize_scope (commit) detects a conflict, it throws this exception.
 * The HandleNode catches it and re-executes the entire scoped block
 * (initialize_scope -> body -> finalize_scope).
 *
 * This extends ControlFlowException so Truffle can optimize it (no stack trace).
 */
public final class RetryingScopeException extends ControlFlowException {
    private static final long serialVersionUID = 1L;

    /** Maximum number of retries before giving up (0 = unlimited) */
    private final int maxRetries;

    /** Optional message describing why retry is needed */
    private final String reason;

    public RetryingScopeException() {
        this(0, null);
    }

    public RetryingScopeException(int maxRetries) {
        this(maxRetries, null);
    }

    public RetryingScopeException(int maxRetries, String reason) {
        this.maxRetries = maxRetries;
        this.reason = reason;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public String getMessage() {
        if (reason != null) {
            return "Scoped block needs retry: " + reason;
        }
        return "Scoped block needs retry";
    }
}
