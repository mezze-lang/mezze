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

    /**
     * The operations this node actually implements — the primitive operators, and
     * nothing else.
     *
     * Declared because the node used to be created for ANY op name on request, which
     * turned "the call site failed to pass a dictionary" into a crash deep in here
     * reading an {@code it} field off a record that never had one. A receiver-less
     * ability operation ({@code arbitrary}, {@code default}, {@code decode}) has no
     * {@code it} by construction, so for those this node is not a fallback at all —
     * it is a wrong answer wearing a fallback's clothes.
     *
     * {@link #isSupported} is what the trampoline asks before resolving one, so a
     * missing dictionary now fails where it happened and says what is missing.
     */
    private static final java.util.Set<String> INTRINSIC_OPS = java.util.Set.of(
        "add", "sub", "mul", "div", "mod",
        "eq", "neq", "lt", "le", "gt", "ge",
        "neg", "not");

    /** True when this node can actually implement {@code opName}. */
    public static boolean isSupported(String opName) {
        return INTRINSIC_OPS.contains(opName);
    }

    /**
     * Why a non-intrinsic op reaching here is reported from HERE rather than
     * refused earlier.
     *
     * Leaving the dict slot empty instead was tried and is worse: calling a null
     * function surfaces as a Truffle {@code UnsupportedSpecializationException}
     * naming a bytecode node, which says nothing about dictionaries. Manufacturing
     * the node and having it explain itself keeps the failure at the operation that
     * needed the dictionary, with the operation's own name in the message.
     */
    private RuntimeException noDictionary() {
        return CompilerDirectives.shouldNotReachHere(
            "no dictionary was supplied for ability operation `" + opName + "`, and "
                + "there is no primitive intrinsic for it. The call site should have "
                + "passed one — for a receiver-less operation that means resolving it "
                + "from the type the call PRODUCES. This is a compiler defect, not a "
                + "problem with the program. See task S-3.");
    }

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
        if (!isSupported(opName)) throw noDictionary();
        MezzeRecord rec = (MezzeRecord) args[0];
        int itIdx = rec.getFieldIndex("it");
        if (itIdx < 0) {
            // A receiver-taking operator with no receiver in the record means the
            // record is not the shape this convention requires — report what is
            // missing rather than which field lookup failed.
            throw CompilerDirectives.shouldNotReachHere(
                "no dictionary was supplied for `" + opName + "`, and the intrinsic "
                    + "fallback needs a receiver this call has none of. The call site "
                    + "should have passed one; this is a compiler defect, not a "
                    + "problem with the program.");
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
