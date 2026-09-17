package com.mezze.datastructures.set;

/**
 * An element type's OWN hashing and equality, for a hash container that must
 * bucket by the element's instance rather than by its runtime representation.
 *
 * <p>The counterpart of the {@link java.util.Comparator} a sorted container carries
 * (see {@code MezzeSortedSet}), and it exists for the same reason: a hash container
 * keeps its element identity INSIDE the data structure, so it needs something it can
 * store and re-use for every later insert, lookup and removal — a per-call closure
 * cannot reach the bucketing.
 *
 * <p>Two methods rather than one because a hash container consults both, and they
 * have to agree: values that compare equal must hash equal, or they land in
 * different buckets and the equality is never asked. The compiler enforces that
 * agreement at the source — `Eq` and `Hash` are written together or not at all — so
 * an implementation of this interface is only ever built from a matched pair.
 *
 * <p>This is the datastructures-side ABSTRACTION only. The implementation that calls
 * back into Mezze closures lives in the interpreter, which is where Mezze functions
 * and the Truffle boundary belong.
 */
public interface ElementHashing {

    /** The element's own hash. */
    int hashOf(Object element);

    /** The element's own equality. Only ever asked of two elements whose
     *  {@link #hashOf} already agreed. */
    boolean eq(Object a, Object b);
}
