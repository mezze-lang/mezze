package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * General-purpose scalar callback invocation for native HOF methods.
 *
 * Resolves {@link ScalarBypass} once, pre-fills closure values into a reusable
 * args array, and provides {@code call1/call2/call3} methods that avoid
 * {@link MezzeRecord} allocation on the scalar fast path.
 *
 * <p>Usage pattern in native HOFs:
 * <pre>{@code
 *   var ctx = ScalarCall.prepare1(predicate, "val");
 *   for (Object element : set) {
 *       Object result = ctx.call1(callNode, element);
 *       ...
 *   }
 * }</pre>
 *
 * For 2-arg callbacks (fold, reduce, merge):
 * <pre>{@code
 *   var ctx = ScalarCall.prepare2(folder, "acc", "x");
 *   for (Object element : set) {
 *       acc = ctx.call2(callNode, acc, element);
 *   }
 * }</pre>
 *
 * For 3-arg callbacks (map fold: acc, k, v):
 * <pre>{@code
 *   var ctx = ScalarCall.prepare3(folder, "acc", "k", "v");
 *   for (var entry : map) {
 *       acc = ctx.call3(callNode, acc, entry.key(), entry.value());
 *   }
 * }</pre>
 *
 * <p><b>Scalar path</b>: When {@link ScalarBypass#tryResolve} succeeds,
 * arguments are passed positionally (closures first, then user args) directly
 * to the inner BDSL CallTarget. The args array is allocated once and reused
 * across loop iterations — zero allocation per call.
 *
 * <p><b>Record path</b>: Falls back to wrapping user args in a
 * {@link MezzeRecord} with the provided field names, prepending closure
 * values. This path always prepends closures correctly, fixing latent
 * closure bugs in older native HOF code that omitted them.
 *
 * <p>For 2+ arg scalar paths, the prepare methods inspect the function's
 * {@link MezzeFunction#getParameterNames()} to compute the positional
 * permutation — handles both {@code {acc, x}} and {@code {x, acc}} orderings.
 */
public final class ScalarCall {

    private final CallTarget target;
    private final Object[] args;
    private final int offset;       // index of first user arg (= closure count)
    private final boolean scalar;
    private final String[] fieldNames;  // for record fallback (null if scalar)
    // Positional slots for scalar path (avoid branch in hot loop)
    private final int slot0, slot1, slot2;

    private ScalarCall(CallTarget target, Object[] args, int offset, boolean scalar,
                       String[] fieldNames, int slot0, int slot1, int slot2) {
        this.target = target;
        this.args = args;
        this.offset = offset;
        this.scalar = scalar;
        this.fieldNames = fieldNames;
        this.slot0 = slot0;
        this.slot1 = slot1;
        this.slot2 = slot2;
    }

    // ==================== Factory Methods ====================

    /**
     * Prepare a 1-arg callback context.
     * Used for: map, filter, any, all, find, count, forEach, flatMap, etc.
     *
     * @param fn        the callback function
     * @param fieldName the record field name for the fallback path (e.g. "val", "x")
     */
    public static ScalarCall prepare1(MezzeFunction fn, String fieldName) {
        Object[] closures = fn.getClosureValues();
        int cLen = closures.length;
        CallTarget inner = ScalarBypass.tryResolve(fn);

        if (inner != null) {
            Object[] a = new Object[cLen + 1];
            System.arraycopy(closures, 0, a, 0, cLen);
            return new ScalarCall(inner, a, cLen, true, null, cLen, 0, 0);
        } else {
            Object[] a = new Object[cLen + 1];
            System.arraycopy(closures, 0, a, 0, cLen);
            return new ScalarCall(fn.getCallTarget(), a, cLen, false,
                    new String[]{fieldName}, cLen, 0, 0);
        }
    }

    /**
     * Prepare a 2-arg callback context.
     * Used for: fold ({acc, x}), merge ({l, r}), map entry callbacks ({k, v}).
     *
     * <p>The prepare step inspects the function's parameter names to determine
     * positional permutation for the scalar path (handles {acc, x} vs {x, acc}).
     *
     * @param fn    the callback function
     * @param name0 first arg's field name (e.g. "acc" for fold, "k" for map entry)
     * @param name1 second arg's field name (e.g. "x" for fold, "v" for map entry)
     */
    public static ScalarCall prepare2(MezzeFunction fn, String name0, String name1) {
        Object[] closures = fn.getClosureValues();
        int cLen = closures.length;
        CallTarget inner = ScalarBypass.tryResolve(fn);
        String[] names = {name0, name1};

        if (inner != null) {
            Object[] a = new Object[cLen + 2];
            System.arraycopy(closures, 0, a, 0, cLen);
            // Determine positional slots: check if function declares args in swapped order
            String[] params = fn.getParameterNames();
            int s0 = cLen, s1 = cLen + 1;
            if (params != null && params.length >= 2 && name1.equals(params[0])) {
                s0 = cLen + 1;
                s1 = cLen;
            }
            return new ScalarCall(inner, a, cLen, true, null, s0, s1, 0);
        } else {
            Object[] a = new Object[cLen + 1];
            System.arraycopy(closures, 0, a, 0, cLen);
            return new ScalarCall(fn.getCallTarget(), a, cLen, false, names, cLen, 0, 0);
        }
    }

    /**
     * Prepare a 3-arg callback context.
     * Used for: map fold ({acc, k, v}).
     *
     * @param fn    the callback function
     * @param name0 first arg's field name (e.g. "acc")
     * @param name1 second arg's field name (e.g. "k")
     * @param name2 third arg's field name (e.g. "v")
     */
    public static ScalarCall prepare3(MezzeFunction fn, String name0, String name1, String name2) {
        Object[] closures = fn.getClosureValues();
        int cLen = closures.length;
        CallTarget inner = ScalarBypass.tryResolve(fn);
        String[] names = {name0, name1, name2};

        if (inner != null) {
            Object[] a = new Object[cLen + 3];
            System.arraycopy(closures, 0, a, 0, cLen);
            // Compute full permutation from function's parameter declaration order
            String[] params = fn.getParameterNames();
            int s0 = cLen, s1 = cLen + 1, s2 = cLen + 2;
            if (params != null && params.length >= 3) {
                s0 = cLen + indexOf(params, name0);
                s1 = cLen + indexOf(params, name1);
                s2 = cLen + indexOf(params, name2);
            }
            return new ScalarCall(inner, a, cLen, true, null, s0, s1, s2);
        } else {
            Object[] a = new Object[cLen + 1];
            System.arraycopy(closures, 0, a, 0, cLen);
            return new ScalarCall(fn.getCallTarget(), a, cLen, false, names, cLen, 0, 0);
        }
    }

    private static int indexOf(String[] arr, String name) {
        for (int i = 0; i < arr.length; i++) {
            if (name.equals(arr[i])) return i;
        }
        return 0; // default to first position
    }

    // ==================== Call Methods ====================

    /** Call with 1 arg. Zero-alloc on the scalar path. */
    public Object call1(IndirectCallNode callNode, Object arg) {
        if (scalar) {
            args[slot0] = arg;
        } else {
            args[offset] = MezzeRecord.fromFields(fieldNames, new Object[]{arg});
        }
        return callNode.call(target, args);
    }

    /** Call with 2 args. Zero-alloc on the scalar path (args reused). */
    public Object call2(IndirectCallNode callNode, Object arg0, Object arg1) {
        if (scalar) {
            args[slot0] = arg0;
            args[slot1] = arg1;
        } else {
            args[offset] = MezzeRecord.fromFields(fieldNames, new Object[]{arg0, arg1});
        }
        return callNode.call(target, args);
    }

    /** Call with 3 args. Zero-alloc on the scalar path (args reused). */
    /**
     * Call a prepared two-argument ELEMENT COMPARATOR — the Mezze `eq` the
     * ability system resolved for this element type — and read its Bool.
     * A native that needs to know whether two Mezze values are equal asks the
     * element's instance through here; deciding it in Java ignores a newtype's
     * declared Eq. Non-Bool (a broken impl) reads as not-equal rather than
     * throwing inside a collection walk.
     */
    public boolean callEq(IndirectCallNode callNode, Object a, Object b) {
        return call2(callNode, a, b) instanceof Boolean result && result;
    }

    /**
     * Call a prepared two-argument ELEMENT COMPARATOR returning -1/0/1, and
     * normalize it to a Java comparator result. Same contract as [callEq] for
     * ordering; a non-Int result reads as "equal", which keeps a sort stable
     * instead of corrupting it.
     */
    public int callCmp(IndirectCallNode callNode, Object a, Object b) {
        Object result = call2(callNode, a, b);
        return result instanceof Long value ? Long.signum(value) : 0;
    }

    public Object call3(IndirectCallNode callNode, Object arg0, Object arg1, Object arg2) {
        if (scalar) {
            args[slot0] = arg0;
            args[slot1] = arg1;
            args[slot2] = arg2;
        } else {
            args[offset] = MezzeRecord.fromFields(fieldNames, new Object[]{arg0, arg1, arg2});
        }
        return callNode.call(target, args);
    }

    /** Whether this context is using the scalar bypass. */
    public boolean isScalar() {
        return scalar;
    }
}
