package com.mezze.interpreter.values;

import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.nodes.Node;

import java.lang.reflect.Field;

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

    /**
     * Cause-carrying variant, for guest-fatal failures that already carry an
     * underlying {@link Throwable} (e.g. async task-failure propagation,
     * parallel-join interruption). A plain {@code (String)} relabel would drop
     * that cause, losing the real error for debugging.
     *
     * <p>{@link AbstractTruffleException} has no {@code (String, Throwable)}
     * constructor, and its {@code (String)} constructor delegates to
     * {@code RuntimeException(message, null)} — which fixes the JDK cause to
     * {@code null}, so a later {@link #initCause(Throwable)} would throw
     * {@code IllegalStateException}. Worse, {@code AbstractTruffleException}
     * overrides {@link #getCause()} {@code final} to return its own
     * {@code private final Throwable cause} field, which only its package
     * constructor sets — the JDK cause is never consulted. So the only way to
     * make {@link #getCause()} return our cause is to populate that private
     * field, which we do reflectively.
     *
     * <p>The interpreter runs as a classpath (unnamed-module) app, so the
     * reflective set succeeds. As a belt-and-suspenders guarantee that the
     * cause is never lost — even if a future module-strict or native-image
     * setup blocks the reflective write — we also {@link #addSuppressed} it,
     * so the underlying error still surfaces in stack traces and
     * {@link #getSuppressed()}.
     */
    public MezzeGuestException(String message, Throwable cause) {
        super(message);
        if (cause != null) {
            boolean causeFieldSet = false;
            try {
                Field f = AbstractTruffleException.class.getDeclaredField("cause");
                f.setAccessible(true);
                f.set(this, cause);
                causeFieldSet = true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Reflective write blocked (e.g. strict module boundary):
                // fall through to addSuppressed so the cause is still kept.
            }
            if (!causeFieldSet) {
                addSuppressed(cause);
            }
        }
    }
}
