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

    /**
     * A fraction built with a zero denominator — `n//0`, which has no value.
     *
     * Separate from [dividingByZero] because what fails is a CONSTRUCTOR rather than
     * an operator, so there is no operation to name; and the numerator belongs in the
     * message for the same reason [arithmeticOverflow] shows its operands — a domain
     * error whose input you cannot see says little about which call went wrong.
     *
     * S-9: that site threw a bare {@code ArithmeticException} until now, which is the
     * exact HOST-error leak [dividingByZero]'s comment above describes for modulo.
     * `FracNatives.divide` had always raised correctly, so the two routes to "a
     * fraction over zero" failed in two different ways.
     *
     * @param numerator the numerator the caller passed, rendered against `//`
     */
    @TruffleBoundary
    public static MezzeGuestException zeroDenominator(long numerator) {
        return new MezzeGuestException(
            "A fraction cannot have a zero denominator: " + numerator + "//0 has no value"
                + " (guard the denominator before constructing — there is no Frac to return)");
    }

    /**
     * The one spelling of "this arithmetic does not fit in an `Int`".
     *
     * `Int` IS a 64-bit signed long, so a result outside that range is not a
     * representable `Int` — and the previous answer, widening to a `double`, made a
     * function declared `-> Int` return a Double. That is unsound rather than
     * imprecise: the Double then flowed through comparisons unnoticed and only blew
     * up where something cast it back, so the failure surfaced at an unrelated
     * place or not at all. See S-6.
     *
     * Fatal and uncatchable, exactly as `/ 0` is. The two are the same kind of
     * situation — an operation with no answer in the type — so they fail the same
     * way, and a program cannot tell them apart by how they fail.
     *
     * The operands are in the message because an overflow you cannot see the inputs
     * of tells you almost nothing about which computation went wrong.
     *
     * <p>{@code @TruffleBoundary} for the same reason as [dividingByZero]: the
     * callers are partially-evaluated typed {@code (long, long)} ops, and the string
     * concatenation here is on native-image's runtime-compilation blocklist.
     *
     * @param operation the failing operation, capitalised — "Addition", "Multiplication"
     */
    @TruffleBoundary
    public static MezzeGuestException arithmeticOverflow(String operation, long left, long right) {
        return new MezzeGuestException(
            operation + " overflowed Int: " + left + " and " + right
                + " (Int is 64-bit signed, so the result has no representable value)");
    }

    /**
     * [arithmeticOverflow] for `Nat`. Named separately so the message names the type
     * the author wrote: `Nat` erases to the same long as `Int`, so the bound is the
     * same, but "overflowed Int" on a `Nat` expression would send them looking for
     * an `Int` that is not in their code.
     */
    @TruffleBoundary
    public static MezzeGuestException natOverflow(String operation, long left, long right) {
        return new MezzeGuestException(
            operation + " overflowed Nat: " + left + " and " + right
                + " (Nat is stored as a 64-bit signed Int, so the result has no representable value)");
    }

    /** Unary overflow — only `negate` of `Int`'s minimum, which has no positive. */
    @TruffleBoundary
    public static MezzeGuestException arithmeticOverflow(String operation, long operand) {
        return new MezzeGuestException(
            operation + " overflowed Int: " + operand
                + " (Int is 64-bit signed, so the result has no representable value)");
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
