package com.mezze.interpreter.v2.nodes.arithmetic;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

/**
 * Exception for arithmetic errors in Mezze.
 */
public final class MezzeArithmeticException extends AbstractTruffleException {

    public MezzeArithmeticException(String message) {
        super(message);
    }

    public MezzeArithmeticException(String message, Node location) {
        super(message, location);
    }
}
