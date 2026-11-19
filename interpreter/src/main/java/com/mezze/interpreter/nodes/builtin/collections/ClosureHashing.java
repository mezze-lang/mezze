package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.set.ElementHashing;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * An {@link ElementHashing} backed by the element type's own `hash` and `eq`
 * closures, resolved at the call site by its `with Hash 'a, Eq 'a` constraints.
 *
 * <p>The hash-container counterpart of {@link ElementComparator}, and built the same
 * way for the same reasons: a stored instance outlives the native call that made it,
 * so it calls each closure's {@code CallTarget} directly rather than through an
 * {@code IndirectCallNode} it would otherwise be holding across roots. Every call is
 * a boundary — the price of bucketing by a user instance, paid only by containers
 * whose elements actually have one.
 *
 * <p>The two closures are one decision and arrive together. A container that hashed
 * by the element's instance but compared structurally (or the reverse) would be
 * worse than either alone: values that ARE equal would land in one bucket and then
 * fail the comparison, or land apart and never be compared at all.
 */
public final class ClosureHashing implements ElementHashing {

    private final MezzeFunction hashFn;
    private final MezzeFunction eqFn;

    private ClosureHashing(MezzeFunction hashFn, MezzeFunction eqFn) {
        this.hashFn = hashFn;
        this.eqFn = eqFn;
    }

    /**
     * The hashing for this pair, or null when either closure is absent — the
     * container then buckets structurally, which is the right answer for an element
     * whose `Eq`/`Hash` are the compiler's derived ones.
     */
    public static ElementHashing of(MezzeFunction hashFn, MezzeFunction eqFn) {
        return (hashFn == null || eqFn == null) ? null : new ClosureHashing(hashFn, eqFn);
    }

    @Override
    @TruffleBoundary
    public int hashOf(Object element) {
        Object[] closureValues = hashFn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.wrapIt(element);
        Object result = hashFn.getCallTarget().call(args);
        // A Mezze `hash` returns Int; the int view is what buckets, and a wide
        // payload must not collapse two different hashes onto one bucket-sized value
        // any more than Java's own Long.hashCode does.
        return result instanceof Long value ? Long.hashCode(value) : 0;
    }

    @Override
    @TruffleBoundary
    public boolean eq(Object a, Object b) {
        Object[] closureValues = eqFn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        // `{ it, r }`, not `{ a, b }`: these are the ABILITY OP's own parameter
        // names. Passing a comparator's `{ a, b }` shape instead returned the
        // function itself rather than a result, and every comparison read false.
        args[closureValues.length] = MezzeRecord.wrapItR(a, b);
        Object result = eqFn.getCallTarget().call(args);
        return result instanceof Boolean value && value;
    }
}
