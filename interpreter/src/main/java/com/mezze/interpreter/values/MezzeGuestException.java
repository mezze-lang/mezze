package com.mezze.interpreter.values;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

/**
 * Guest-language runtime error (divide-by-zero, type mismatch on arithmetic,
 * etc.) — extends {@link AbstractTruffleException} so Truffle's debugger
 * sees it as a language-level exception. Without this, the DAP exception
 * breakpoint filter never fires because the JVM {@code ArithmeticException}
 * / {@code RuntimeException} isn't recognized as a guest exception.
 */
public final class MezzeGuestException extends AbstractTruffleException {

    public MezzeGuestException(String message) {
        super(message);
    }

    public MezzeGuestException(String message, Node location) {
        super(message, location);
    }
}
