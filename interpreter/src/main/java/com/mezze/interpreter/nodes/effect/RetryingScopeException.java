package com.mezze.interpreter.nodes.effect;

import com.oracle.truffle.api.exception.AbstractTruffleException;

/**
 * Thrown by STM transactions on optimistic-concurrency conflict to trigger
 * automatic retry of the surrounding {@code perform} block.
 *
 * <p>Caught by {@link HandleNode} so the body re-executes against fresh
 * scope state. Not related to the effect-handler op-call dispatch path —
 * this is STM-specific retry signaling.
 */
public final class RetryingScopeException extends AbstractTruffleException {

    private final int maxRetries;
    private final String reason;

    public RetryingScopeException(int maxRetries, String reason) {
        super(reason);
        this.maxRetries = maxRetries;
        this.reason = reason;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public String getReason() {
        return reason;
    }
}
