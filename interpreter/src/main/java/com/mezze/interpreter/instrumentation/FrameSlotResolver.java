package com.mezze.interpreter.instrumentation;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;

/**
 * Resolves the small expression subset used by debugger watch expressions,
 * conditional breakpoints, and logpoint interpolation — against a suspended
 * FRAME's named slots.
 *
 * The full parser can't help here: inline eval compiles a standalone module that
 * doesn't see the frame's locals. We handle what watch/conditions actually use:
 *
 *   <expr> ::= <atom>
 *            | <atom> <op> <atom>
 *   <atom> ::= IDENT            (read the frame slot of that name)
 *            | INT              (integer literal)
 *   <op>   ::= > | < | >= | <= | == | != | + | - | *
 *
 * Anything outside this grammar → parse() returns null, and MezzeLanguage's
 * inline-parse override falls through to the regular module parse (which still
 * handles literals + globals), so nothing regresses.
 *
 * Comparisons return a Boolean (so conditional breakpoints work); arithmetic
 * returns a Long. Values read from the frame are coerced to long where numeric.
 */
public final class FrameSlotResolver {

    private FrameSlotResolver() {}

    /** Parsed expression: either a single atom or `lhs op rhs`. */
    public static final class Expr {
        final Atom lhs;
        final String op;   // null for a bare atom
        final Atom rhs;    // null for a bare atom
        Expr(Atom lhs, String op, Atom rhs) { this.lhs = lhs; this.op = op; this.rhs = rhs; }
    }

    private static final class Atom {
        final String ident;    // non-null → read this frame slot
        final long literal;    // used when ident == null
        Atom(String ident, long literal) { this.ident = ident; this.literal = literal; }
    }

    private static final String[] OPS = { ">=", "<=", "==", "!=", ">", "<", "+", "-", "*" };

    /** Parse the expression subset, or null if it doesn't match. */
    @TruffleBoundary
    public static Expr parse(String s) {
        s = s.trim();
        if (s.isEmpty()) return null;
        // Try binary: find a top-level operator (no parens/precedence — the
        // grammar is atom op atom, so the first matching op splits it).
        for (String op : OPS) {
            int idx = findOp(s, op);
            if (idx > 0) {
                Atom lhs = parseAtom(s.substring(0, idx));
                Atom rhs = parseAtom(s.substring(idx + op.length()));
                if (lhs != null && rhs != null) return new Expr(lhs, op, rhs);
            }
        }
        // Bare atom.
        Atom atom = parseAtom(s);
        return atom == null ? null : new Expr(atom, null, null);
    }

    /** First index of `op` that isn't at position 0 (avoid a leading unary). */
    private static int findOp(String s, String op) {
        int from = 1;
        while (true) {
            int i = s.indexOf(op, from);
            if (i < 0) return -1;
            // For "-" don't split a negative literal like "x - 5" vs "-5":
            // require a non-space char before it (an atom must precede).
            if (!s.substring(0, i).trim().isEmpty()) return i;
            from = i + 1;
        }
    }

    private static Atom parseAtom(String s) {
        s = s.trim();
        if (s.isEmpty()) return null;
        // Integer literal (optionally negative).
        try {
            return new Atom(null, Long.parseLong(s));
        } catch (NumberFormatException ignored) {}
        // Identifier: a simple name (letters, digits, _; not starting with digit).
        if (isIdentifier(s)) return new Atom(s, 0);
        return null;
    }

    private static boolean isIdentifier(String s) {
        if (s.isEmpty()) return false;
        char c0 = s.charAt(0);
        if (!(Character.isLetter(c0) || c0 == '_')) return false;
        for (int i = 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) return false;
        }
        return true;
    }

    /**
     * Evaluate against the frame. Idents resolve to their named frame slot;
     * unknown idents / non-numeric values throw so the caller reports an error.
     * Called with the debugger's MATERIALIZED frame (already on a boundary), so
     * no @TruffleBoundary here.
     */
    public static Object evaluate(Expr expr, Frame frame) {
        if (expr.op == null) {
            // Bare atom — return the raw value (so watching a Str/record shows it).
            return atomValue(expr.lhs, frame);
        }
        long l = toLong(atomValue(expr.lhs, frame));
        long r = toLong(atomValue(expr.rhs, frame));
        switch (expr.op) {
            case ">":  return l > r;
            case "<":  return l < r;
            case ">=": return l >= r;
            case "<=": return l <= r;
            case "==": return l == r;
            case "!=": return l != r;
            case "+":  return l + r;
            case "-":  return l - r;
            case "*":  return l * r;
            default:   throw new IllegalStateException("unsupported op " + expr.op);
        }
    }

    private static Object atomValue(Atom a, Frame frame) {
        if (a.ident == null) return a.literal;
        FrameDescriptor desc = frame.getFrameDescriptor();
        for (int i = 0; i < desc.getNumberOfSlots(); i++) {
            if (a.ident.equals(desc.getSlotName(i))) {
                return readSlot(frame, i);
            }
        }
        throw new IllegalArgumentException("Undefined variable: " + a.ident);
    }

    private static Object readSlot(Frame frame, int slot) {
        FrameSlotKind kind = frame.getFrameDescriptor().getSlotKind(slot);
        try {
            switch (kind) {
                case Long:    return frame.getLong(slot);
                case Double:  return frame.getDouble(slot);
                case Boolean: return frame.getBoolean(slot);
                case Object:  return frame.getObject(slot);
                default:      return frame.getValue(slot);
            }
        } catch (Throwable t) {
            return frame.getValue(slot);
        }
    }

    private static long toLong(Object v) {
        if (v instanceof Long l) return l;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Double d) return d.longValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        throw new IllegalArgumentException("not a number: " + v);
    }
}
