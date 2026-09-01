package com.mezze.interpreter.runtime;

/**
 * INTERNAL type-guard for the native layer. Thrown when a runtime value
 * reaches a builtin native with a shape the typechecker should have
 * prevented — e.g. a non-record where a record is required, a missing
 * field, an unknown native function, or a primitive-bridge argument of
 * the wrong Java type.
 *
 * <p>This signals a <em>compiler / native-binding bug</em>, NOT user error:
 * in a well-typed program these guards are unreachable. It exists so the
 * previously-anonymous {@code RuntimeException} assertions all carry one
 * clear, labeled type.
 *
 * <p>It extends {@link RuntimeException} (not a checked exception, not an
 * {@link Error}) so every existing {@code catch (RuntimeException ...)} and
 * {@code catch (Exception ...)} site — notably the IONatives / codec
 * try/catch→createErr blocks — keeps catching a guard exactly as it caught
 * the old {@code RuntimeException}.
 */
@SuppressWarnings("serial")
public final class NativeTypeMismatchException extends RuntimeException {

    public NativeTypeMismatchException(String message) {
        super(message);
    }

    public NativeTypeMismatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
