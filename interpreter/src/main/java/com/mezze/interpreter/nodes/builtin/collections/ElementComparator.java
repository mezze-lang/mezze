package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Comparator;

/**
 * A {@link Comparator} backed by a Mezze {@code cmp} closure — the element type's
 * own {@code Ord} impl, resolved at the call site by a {@code with Ord 'a}
 * constraint.
 *
 * <p>Sorted containers keep their ordering INSIDE the data structure, so unlike the
 * flat containers (which call the closure per operation through
 * {@link ScalarCall}) they need a comparator they can store and re-use for every
 * later insert, lookup and range query. Without one they fall back to
 * {@code ((Comparable) a).compareTo(b)}, which throws {@code ClassCastException}
 * for any Mezze record or variant — sorted containers were usable with primitives
 * only.
 *
 * <p>This calls the closure's {@code CallTarget} directly rather than through an
 * {@code IndirectCallNode}: a stored comparator outlives the native call that
 * built it, so it cannot hold a Truffle node belonging to another root. Every
 * comparison is therefore a boundary call — the price of ordering by a user
 * instance, paid only by containers whose elements actually have one.
 */
public final class ElementComparator implements Comparator<Object> {
    private final MezzeFunction cmpFn;

    private ElementComparator(MezzeFunction cmpFn) {
        this.cmpFn = cmpFn;
    }

    /** The comparator for [cmpFn], or null when no closure was supplied (the
     *  container then keeps its natural ordering, which is right for primitives). */
    public static Comparator<Object> of(MezzeFunction cmpFn) {
        return cmpFn == null ? null : new ElementComparator(cmpFn);
    }

    @Override
    @TruffleBoundary
    public int compare(Object a, Object b) {
        Object[] closureValues = cmpFn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.wrapAB(a, b);
        Object result = cmpFn.getCallTarget().call(args);
        return result instanceof Long value ? Long.signum(value) : 0;
    }
}
