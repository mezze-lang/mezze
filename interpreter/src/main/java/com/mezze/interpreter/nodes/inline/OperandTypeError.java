package com.mezze.interpreter.nodes.inline;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.runtime.MezzeInternalError;
import com.mezze.interpreter.values.MezzeBytes;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeNull;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * The `@Fallback` of every `Direct*Node`: an operator reached an operand whose
 * type its specializations do not cover.
 *
 * <p>That is unreachable in a well-typed program — the typechecker resolves
 * both operand types before lowering emits a `Direct*Node` at all — so getting
 * here is a {@link MezzeInternalError}, not user error. The soundness holes
 * recorded in `planned_features/known-bugs.md` are how it happens: BUG 35 (a
 * `perform` not checking the performer's `having` binding) lands a `Str` in an
 * `Int`-typed local and the next arithmetic op falls through to here.
 *
 * <p>The naming matters. These messages used to read
 * {@code Cannot multiply String and Long} — JVM class names for a user who
 * writes {@code Str} and {@code Int}, under a {@code java.lang.RuntimeException}
 * header. A value whose Mezze type we know is named in Mezze; one we do not is
 * marked plainly as internal rather than dressed up as a type, so a bug report
 * still carries the class that showed up.
 */
public final class OperandTypeError {

    private OperandTypeError() {}

    /**
     * A binary operator that no specialization covered.
     *
     * <p>[joiner] keeps each operator's own phrasing — `and` for arithmetic,
     * `by` for modulo, the symbol itself for a comparison — so the message
     * still says WHICH operator failed, which the old `Cannot compare X > Y`
     * form got right and a uniform "and" would lose.
     */
    @TruffleBoundary
    public static MezzeInternalError binary(String what, Object left, String joiner, Object right) {
        return new MezzeInternalError(
            "cannot " + what + " " + describe(left) + " " + joiner + " " + describe(right));
    }

    /** A unary operator (`negate`, `not`) that no specialization covered. */
    @TruffleBoundary
    public static MezzeInternalError unary(String what, Object operand) {
        return new MezzeInternalError("cannot " + what + " " + describe(operand));
    }

    /**
     * A runtime value in MEZZE's types, with an article so it reads in a
     * sentence.
     *
     * <p>{@code Char} is deliberately absent: a Char VALUE is a one-character
     * String at runtime, so it is indistinguishable from a {@code Str} here.
     */
    @TruffleBoundary
    public static String describe(Object value) {
        if (value == null || value instanceof MezzeNull) return "null";
        if (value instanceof Long) return "an Int";
        if (value instanceof Double) return "a Float";
        if (value instanceof String) return "a Str";
        if (value instanceof Boolean) return "a Bool";
        if (value instanceof MezzeBytes) return "a Bytes";
        if (value instanceof MezzeSeq) return "a seq";
        if (value instanceof MezzeFunction) return "a function";
        if (value instanceof MezzeVariant variant) {
            String name = variant.getConstructorName();
            return name == null ? "a variant" : "the variant `" + name + "`";
        }
        if (value instanceof MezzeRecord) {
            // A Frac IS a record — {numerator, denominator} — so ask
            // FractionHelper, the one authority on that shape, before
            // reporting the general case.
            return FractionHelper.isFraction(value) ? "a Frac" : "a record";
        }
        // Not a shape with a Mezze name. Say so rather than printing a JVM
        // class name as if it were a type — but keep the class, because this
        // message is what a bug report carries.
        return "an internal value (" + value.getClass().getSimpleName() + ")";
    }
}
