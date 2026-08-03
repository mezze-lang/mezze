package com.mezze.interpreter.instrumentation;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates the expression subset used by debugger watch expressions,
 * conditional breakpoints, and logpoint interpolation — against a suspended
 * FRAME's named slots.
 *
 * The full parser can't help: inline eval compiles a standalone module that
 * can't see the frame's locals. This handles a useful arithmetic/comparison
 * subset over frame variables + integer literals via a small recursive-descent
 * parser:
 *
 *   comparison ::= sum ( (> | < | >= | <= | == | !=) sum )?
 *   sum        ::= product ( (+ | -) product )*
 *   product    ::= unary ( (* | /) unary )*
 *   unary      ::= - unary | atom
 *   atom       ::= IDENT | INT | ( comparison )
 *
 * IDENT reads the frame slot of that name. Anything outside this grammar (field
 * access, calls, strings, …) → parse() returns null, and MezzeLanguage's
 * inline-parse override falls through to the module parse, so globals + literals
 * still work and nothing regresses.
 *
 * Comparisons yield Boolean (conditional breakpoints work); a bare atom yields
 * its raw value (so watching a Str/record shows it); arithmetic yields Long.
 */
public final class FrameSlotResolver {

    private FrameSlotResolver() {}

    // ─── AST ────────────────────────────────────────────────────────────

    public interface Expr {}
    private record Ident(String name) implements Expr {}
    private record IntLit(long value) implements Expr {}
    private record Bin(String op, Expr lhs, Expr rhs) implements Expr {}
    private record Neg(Expr inner) implements Expr {}

    // ─── Tokenizer ──────────────────────────────────────────────────────

    private record Tok(String kind, String text) {}

    @TruffleBoundary
    private static List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            // Multi-char operators first.
            if (i + 1 < n) {
                String two = s.substring(i, i + 2);
                if (two.equals(">=") || two.equals("<=") || two.equals("==") || two.equals("!=")) {
                    out.add(new Tok("op", two)); i += 2; continue;
                }
            }
            if ("+-*/<>()".indexOf(c) >= 0) {
                out.add(new Tok(c == '(' ? "(" : c == ')' ? ")" : "op", String.valueOf(c))); i++; continue;
            }
            if (Character.isDigit(c)) {
                int j = i; while (j < n && Character.isDigit(s.charAt(j))) j++;
                out.add(new Tok("int", s.substring(i, j))); i = j; continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int j = i; while (j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_')) j++;
                out.add(new Tok("ident", s.substring(i, j))); i = j; continue;
            }
            return null; // unrecognized char → not our subset
        }
        return out;
    }

    // ─── Parser (recursive descent) ─────────────────────────────────────

    @TruffleBoundary
    public static Expr parse(String s) {
        List<Tok> toks = tokenize(s == null ? "" : s.trim());
        if (toks == null || toks.isEmpty()) return null;
        Parser p = new Parser(toks);
        Expr e = p.comparison();
        if (e == null || !p.atEnd()) return null;   // trailing junk → reject
        return e;
    }

    private static final class Parser {
        private final List<Tok> toks;
        private int pos = 0;
        Parser(List<Tok> toks) { this.toks = toks; }

        boolean atEnd() { return pos >= toks.size(); }
        private Tok peek() { return pos < toks.size() ? toks.get(pos) : null; }
        private Tok next() { return toks.get(pos++); }
        private boolean isOp(String op) { Tok t = peek(); return t != null && t.kind.equals("op") && t.text.equals(op); }

        Expr comparison() {
            Expr lhs = sum();
            if (lhs == null) return null;
            Tok t = peek();
            if (t != null && t.kind.equals("op") &&
                    (">".equals(t.text) || "<".equals(t.text) || ">=".equals(t.text)
                            || "<=".equals(t.text) || "==".equals(t.text) || "!=".equals(t.text))) {
                next();
                Expr rhs = sum();
                if (rhs == null) return null;
                return new Bin(t.text, lhs, rhs);
            }
            return lhs;
        }

        Expr sum() {
            Expr lhs = product();
            if (lhs == null) return null;
            while (isOp("+") || isOp("-")) {
                String op = next().text;
                Expr rhs = product();
                if (rhs == null) return null;
                lhs = new Bin(op, lhs, rhs);
            }
            return lhs;
        }

        Expr product() {
            Expr lhs = unary();
            if (lhs == null) return null;
            while (isOp("*") || isOp("/")) {
                String op = next().text;
                Expr rhs = unary();
                if (rhs == null) return null;
                lhs = new Bin(op, lhs, rhs);
            }
            return lhs;
        }

        Expr unary() {
            if (isOp("-")) { next(); Expr e = unary(); return e == null ? null : new Neg(e); }
            return atom();
        }

        Expr atom() {
            Tok t = peek();
            if (t == null) return null;
            switch (t.kind) {
                case "int": next(); return new IntLit(Long.parseLong(t.text));
                case "ident": next(); return new Ident(t.text);
                case "(": {
                    next();
                    Expr e = comparison();
                    if (e == null || peek() == null || !peek().kind.equals(")")) return null;
                    next(); // consume ')'
                    return e;
                }
                default: return null;
            }
        }
    }

    // ─── Evaluation (on the debugger's materialized frame) ──────────────

    /**
     * Called with the debugger's MATERIALIZED frame (already on a boundary), so
     * NOT @TruffleBoundary (the DSL processor rejects a Frame param with it).
     *
     * <p>{@code bytecodeNode} + {@code bci} are non-null when the enclosing
     * fn was compiled via BDSL — those are the ONLY source of local + param
     * shadow names under BDSL (the FrameDescriptor has slot indices only).
     * Null under AST — the FrameDescriptor's slot names are authoritative
     * there. See MezzeDebugScope for the analogous scope-enumeration split.
     */
    public static Object evaluate(Expr e, Frame frame,
                                  com.oracle.truffle.api.bytecode.BytecodeNode bytecodeNode,
                                  int bci) {
        if (e instanceof IntLit il) return il.value();
        if (e instanceof Ident id) return slotValue(id.name(), frame, bytecodeNode, bci);
        if (e instanceof Neg neg) return -toLong(evaluate(neg.inner(), frame, bytecodeNode, bci));
        if (e instanceof Bin bin) {
            long l = toLong(evaluate(bin.lhs(), frame, bytecodeNode, bci));
            long r = toLong(evaluate(bin.rhs(), frame, bytecodeNode, bci));
            switch (bin.op()) {
                case ">":  return l > r;
                case "<":  return l < r;
                case ">=": return l >= r;
                case "<=": return l <= r;
                case "==": return l == r;
                case "!=": return l != r;
                case "+":  return l + r;
                case "-":  return l - r;
                case "*":  return l * r;
                case "/":  if (r == 0) throw new IllegalArgumentException("division by zero"); return l / r;
                default:   throw new IllegalStateException("unsupported op " + bin.op());
            }
        }
        throw new IllegalStateException("unknown node");
    }

    /** Two-argument overload for AST callers (no BDSL context). */
    public static Object evaluate(Expr e, Frame frame) {
        return evaluate(e, frame, null, -1);
    }

    private static Object slotValue(String name, Frame frame,
                                    com.oracle.truffle.api.bytecode.BytecodeNode bytecodeNode,
                                    int bci) {
        // #359 — under BDSL, local names live in the bytecode's own local
        // table (both real locals and the param-shadow entries emitted by
        // BytecodeCompiler in debug mode). Consult it before falling back
        // to the FrameDescriptor scan (AST path).
        if (bytecodeNode != null) {
            // #359 — Use the STATIC local list (getLocals()); each entry
            // reports the (start, end) bci range where it is live. Read
            // the value at ANY bci within that range — the frame slot
            // holds the same value regardless of which live bci we read
            // from, and the debugger's paused frame keeps it valid.
            // We can't rely on `bci` being valid (the stored-bci frame
            // slot is often uninitialized when the debugger materializes
            // the frame), so we pick a bci from the local's own range.
            var locals = bytecodeNode.getLocals();
            if (locals != null) {
                for (int i = 0; i < locals.size(); i++) {
                    var lv = locals.get(i);
                    Object nm = lv.getName();
                    if (nm instanceof String s && name.equals(s)) {
                        int offset = lv.getLocalOffset();
                        int start = lv.getStartIndex();
                        int end = lv.getEndIndex();
                        // Use the caller's bci if it's inside this local's
                        // live range; otherwise fall back to the local's
                        // own start bci (frame slot holds the same value
                        // regardless of which live bci we read from).
                        int useBci = (bci >= start && bci < end) ? bci : start;
                        try {
                            Object v = bytecodeNode.getLocalValue(useBci, frame, offset);
                            if (v != null) return v;
                        } catch (Throwable t) {
                            // Try other entries with the same name (shadowed).
                        }
                    }
                }
            }
        }
        FrameDescriptor desc = frame.getFrameDescriptor();
        for (int i = 0; i < desc.getNumberOfSlots(); i++) {
            if (name.equals(desc.getSlotName(i))) return readSlot(frame, i);
        }
        throw new IllegalArgumentException("Undefined variable: " + name);
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
