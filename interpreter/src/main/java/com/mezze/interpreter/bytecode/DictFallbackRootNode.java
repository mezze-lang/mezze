package com.mezze.interpreter.bytecode;

import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Intrinsic dict-fallback function for primitive types.
 *
 * <p>When a polymorphic function with dict params (e.g. {@code {x} -> x + 1}) is
 * passed as a first-class value to a higher-order function, the caller can't supply
 * the dict because the call is indirect (through a captured closure). The trampoline's
 * {@code normalizeArgs} fills the missing dict slot with one of these fallback
 * functions, which perform the operation directly on primitive types.
 *
 * <p>Each instance is specialized on an operation name (e.g. "add", "mul") and
 * dispatches on the runtime argument types. Created once per op via
 * {@link MezzeLanguage#getDictFallback} and cached for the language instance lifetime.
 *
 * <p>The calling convention matches ability-op implementations: receives a single
 * {@link MezzeRecord} arg with fields {@code it} (receiver) and optionally {@code r}
 * (right operand for binary ops).
 */
public final class DictFallbackRootNode extends RootNode {
    private final String opName;

    public DictFallbackRootNode(MezzeLanguage lang, String opName) {
        super(lang, FrameDescriptor.newBuilder().build());
        this.opName = opName;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return executeBoundary(frame.getArguments());
    }

    @CompilerDirectives.TruffleBoundary
    private Object executeBoundary(Object[] args) {
        // CallIndirect passes [closures..., record]. Fallback functions have
        // no closures, so args = [record].
        MezzeRecord rec = (MezzeRecord) args[0];
        int itIdx = rec.getFieldIndex("it");
        if (itIdx < 0) {
            throw CompilerDirectives.shouldNotReachHere(
                "DictFallback[" + opName + "]: record has no 'it' field");
        }
        Object it = rec.getField(itIdx);

        // Binary ops: receiver + right operand
        int rIdx = rec.getFieldIndex("r");
        if (rIdx >= 0) {
            Object r = rec.getField(rIdx);
            return binaryOp(it, r);
        }
        // Unary ops: receiver only
        return unaryOp(it);
    }

    private Object binaryOp(Object it, Object r) {
        // Long (Int) operations
        if (it instanceof Long a && r instanceof Long b) {
            return switch (opName) {
                case "add" -> a + b;
                case "sub" -> a - b;
                case "mul" -> a * b;
                case "div" -> a / b;
                case "mod" -> a % b;
                case "eq"  -> a.longValue() == b.longValue();
                case "neq" -> a.longValue() != b.longValue();
                case "lt"  -> a < b;
                case "le"  -> a <= b;
                case "gt"  -> a > b;
                case "ge"  -> a >= b;
                default -> throw unsupported("Long", "Long");
            };
        }
        // Double (Float) operations
        if (it instanceof Double a && r instanceof Double b) {
            return switch (opName) {
                case "add" -> a + b;
                case "sub" -> a - b;
                case "mul" -> a * b;
                case "div" -> a / b;
                case "mod" -> a % b;
                case "eq"  -> a.doubleValue() == b.doubleValue();
                case "neq" -> a.doubleValue() != b.doubleValue();
                case "lt"  -> a < b;
                case "le"  -> a <= b;
                case "gt"  -> a > b;
                case "ge"  -> a >= b;
                default -> throw unsupported("Double", "Double");
            };
        }
        // String operations
        if (it instanceof String a && r instanceof String b) {
            return switch (opName) {
                case "add" -> a + b;
                case "eq"  -> a.equals(b);
                case "neq" -> !a.equals(b);
                case "lt"  -> a.compareTo(b) < 0;
                case "le"  -> a.compareTo(b) <= 0;
                case "gt"  -> a.compareTo(b) > 0;
                case "ge"  -> a.compareTo(b) >= 0;
                default -> throw unsupported("String", "String");
            };
        }
        // Mixed Long/Double (Int+Float or Float+Int)
        if ((it instanceof Long || it instanceof Double)
                && (r instanceof Long || r instanceof Double)) {
            double a = it instanceof Long l ? l.doubleValue() : (Double) it;
            double b = r instanceof Long l ? l.doubleValue() : (Double) r;
            return switch (opName) {
                case "add" -> a + b;
                case "sub" -> a - b;
                case "mul" -> a * b;
                case "div" -> a / b;
                case "mod" -> a % b;
                case "eq"  -> a == b;
                case "neq" -> a != b;
                case "lt"  -> a < b;
                case "le"  -> a <= b;
                case "gt"  -> a > b;
                case "ge"  -> a >= b;
                default -> throw unsupported(it.getClass().getSimpleName(), r.getClass().getSimpleName());
            };
        }
        throw unsupported(
            it == null ? "null" : it.getClass().getSimpleName(),
            r == null ? "null" : r.getClass().getSimpleName());
    }

    private Object unaryOp(Object it) {
        if (it instanceof Long a) {
            return switch (opName) {
                case "neg" -> -a;
                default -> throw unsupported("Long");
            };
        }
        if (it instanceof Double a) {
            return switch (opName) {
                case "neg" -> -a;
                default -> throw unsupported("Double");
            };
        }
        if (it instanceof Boolean a) {
            return switch (opName) {
                case "not" -> !a;
                default -> throw unsupported("Boolean");
            };
        }
        throw unsupported(it == null ? "null" : it.getClass().getSimpleName());
    }

    private RuntimeException unsupported(String type) {
        throw CompilerDirectives.shouldNotReachHere(
            "DictFallback[" + opName + "]: unsupported unary type " + type);
    }

    private RuntimeException unsupported(String typeL, String typeR) {
        throw CompilerDirectives.shouldNotReachHere(
            "DictFallback[" + opName + "]: unsupported types " + typeL + " × " + typeR);
    }

    @Override
    public String getName() {
        return "__dict_fallback_" + opName;
    }
}
