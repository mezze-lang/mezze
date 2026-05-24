package com.mezze.interpreter.runtime;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.interop.ExceptionType;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * Sentinel thrown by {@code Environment.exit { code = N }} to unwind the
 * running Mezze program with a requested exit code. Replaces direct
 * {@code System.exit(N)} so callers can decide what to do:
 *
 * <ul>
 *   <li><b>Wire protocol</b> (RuntimeServer): catch, send
 *       {@code EXITED { code = N, reason = "exit_called" }} over the wire,
 *       stay alive for the next LOAD.</li>
 *   <li><b>Standalone app jar / native</b> (MezzeAppMain): catch at top
 *       level, propagate via {@code System.exit(N)}.</li>
 * </ul>
 *
 * Why an {@code AbstractTruffleException}: Truffle treats it as a guest-
 * language exception and propagates it cleanly through the polyglot
 * boundary as a {@link org.graalvm.polyglot.PolyglotException} the host
 * can introspect via {@code isExit() / getExitStatus()}. A plain Java
 * {@code RuntimeException} would surface as an internal error.
 *
 * Type chosen as {@link ExceptionType#EXIT} so polyglot tooling
 * (debuggers, profilers) treats this as a normal termination rather
 * than a fault.
 */
@ExportLibrary(InteropLibrary.class)
@SuppressWarnings("serial")
public final class MezzeExitException extends AbstractTruffleException {

    private final int exitStatus;

    public MezzeExitException(int exitStatus) {
        super("Mezze program called Environment.exit(" + exitStatus + ")");
        this.exitStatus = exitStatus;
    }

    public int getExitStatus() {
        return exitStatus;
    }

    @ExportMessage
    boolean isException() {
        return true;
    }

    @ExportMessage
    ExceptionType getExceptionType() {
        return ExceptionType.EXIT;
    }

    @ExportMessage
    int getExceptionExitStatus() {
        return exitStatus;
    }

    @ExportMessage
    RuntimeException throwException() {
        throw this;
    }
}
