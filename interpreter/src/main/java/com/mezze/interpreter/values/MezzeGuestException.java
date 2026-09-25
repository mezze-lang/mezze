package com.mezze.interpreter.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
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

    /**
     * The one spelling of "this arithmetic has no answer".
     *
     * `/` and `%` by zero are the same situation, so a program must not be
     * able to tell them apart by how they fail — and every route to the same
     * operation must agree. Both used to be open-coded: divide raised this
     * exception, modulo raised a bare JVM {@code ArithmeticException}, which
     * Truffle classifies as a HOST error and surfaces to the user as an
     * internal failure with a JVM stack trace.
     *
     * <p>{@code @TruffleBoundary} because the callers are the typed {@code
     * (long, long)} native ops, which are partially evaluated. The message is
     * built by string concatenation, and {@code StringConcatHelper.simpleConcat}
     * is on native-image's runtime-compilation BLOCKLIST — so without the
     * boundary, {@code native-image} fails the whole build with "blocklisted
     * methods are reachable", for any program whose closure contains a
     * division. Nothing is lost by the boundary: this is the path that is about
     * to throw.
     *
     * @param operation the failing operation, capitalised — "Division", "Modulo"
     */
    @TruffleBoundary
    public static MezzeGuestException dividingByZero(String operation) {
        return new MezzeGuestException(operation + " by zero");
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
