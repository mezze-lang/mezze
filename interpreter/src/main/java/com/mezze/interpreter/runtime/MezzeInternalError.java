package com.mezze.interpreter.runtime;

/**
 * A runtime guard that is UNREACHABLE in a well-typed program was reached.
 *
 * <p>An operator met an operand of the wrong type, a field read met a
 * non-record, a match found no arm, a global hash resolved to nothing. Type
 * checking rules every one of those out, so reaching one means the typechecker
 * accepted a program it should have refused: a <em>compiler bug</em>, never
 * user error. {@link com.mezze.interpreter.runtime.NativeTypeMismatchException}
 * is the same idea one layer down, for the native bindings.
 *
 * <p>Why this type exists rather than a bare {@code RuntimeException}: the
 * message a bare one carries reaches the user as
 * {@code java.lang.RuntimeException: Cannot multiply String and Long} — a Java
 * class name and two JVM type names, for a defect in Mezze. Only a labeled type
 * lets the runtime/CLI boundary tell "the compiler is broken" apart from "the
 * program failed", and render it as {@code I0004} with the report hint. The
 * message itself is written in MEZZE's types ({@code Str}, {@code Int}) —
 * {@code OperandTypeError} does that naming for the operator nodes.
 *
 * <p>It extends {@link RuntimeException} so the existing
 * {@code catch (RuntimeException ...)} / {@code catch (Exception ...)} sites —
 * notably the IONatives and codec {@code try/catch}→{@code createErr} blocks —
 * keep catching it exactly as they caught the bare one. That is deliberate: a
 * change of control flow here would be a behaviour change smuggled in with a
 * message fix.
 */
@SuppressWarnings("serial")
public final class MezzeInternalError extends RuntimeException {

    /** The diagnostic code this renders as. Kept as a string so the interpreter
     *  does not have to reach into the ast module's enum from a hot node. */
    public static final String CODE = "I0004";

    public MezzeInternalError(String message) {
        super(message);
    }

    public MezzeInternalError(String message, Throwable cause) {
        super(message, cause);
    }
}
