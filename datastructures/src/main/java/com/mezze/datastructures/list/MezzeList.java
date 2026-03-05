package com.mezze.datastructures.list;

import com.oracle.truffle.api.interop.TruffleObject;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Immutable functional linked list with pattern matching support.
 *
 * Implementations:
 * - MezzeNil: Empty list singleton
 * - MezzeLongCons: Cons cell with unboxed long head
 * - MezzeDoubleCons: Cons cell with unboxed double head
 * - MezzeCons: Cons cell with Object head
 *
 * @param <A> Element type
 */
public sealed interface MezzeList<A> extends TruffleObject, Iterable<A>
        permits MezzeNil, MezzeLongCons, MezzeDoubleCons, MezzeCons {

    // ===== CONSTRUCTION =====

    /**
     * Returns the empty list.
     */
    @SuppressWarnings("unchecked")
    static <A> MezzeList<A> empty() {
        return (MezzeList<A>) MezzeNil.INSTANCE;
    }

    /**
     * Returns the empty list (Nil).
     */
    @SuppressWarnings("unchecked")
    static <A> MezzeList<A> nil() {
        return (MezzeList<A>) MezzeNil.INSTANCE;
    }

    /**
     * Creates a cons cell (prepend).
     */
    static <A> MezzeList<A> cons(A head, MezzeList<A> tail) {
        if (head instanceof Long l) {
            @SuppressWarnings("unchecked")
            MezzeList<A> result = (MezzeList<A>) new MezzeLongCons(l, (MezzeList<Long>) tail);
            return result;
        }
        if (head instanceof Double d) {
            @SuppressWarnings("unchecked")
            MezzeList<A> result = (MezzeList<A>) new MezzeDoubleCons(d, (MezzeList<Double>) tail);
            return result;
        }
        return new MezzeCons<>(head, tail);
    }

    /**
     * Creates a single-element list.
     */
    static <A> MezzeList<A> of(A element) {
        return cons(element, empty());
    }

    /**
     * Creates a list from varargs.
     */
    @SafeVarargs
    static <A> MezzeList<A> of(A... elements) {
        MezzeList<A> result = empty();
        for (int i = elements.length - 1; i >= 0; i--) {
            result = cons(elements[i], result);
        }
        return result;
    }

    /**
     * Creates a list of longs (unboxed).
     */
    static MezzeList<Long> ofLongs(long... elements) {
        MezzeList<Long> result = empty();
        for (int i = elements.length - 1; i >= 0; i--) {
            result = new MezzeLongCons(elements[i], result);
        }
        return result;
    }

    /**
     * Creates a list of doubles (unboxed).
     */
    static MezzeList<Double> ofDoubles(double... elements) {
        MezzeList<Double> result = empty();
        for (int i = elements.length - 1; i >= 0; i--) {
            result = new MezzeDoubleCons(elements[i], result);
        }
        return result;
    }

    // ===== BASIC OPERATIONS =====

    /**
     * Returns true if this list is empty (Nil).
     */
    boolean isEmpty();

    /**
     * Returns true if this list is not empty.
     */
    default boolean nonEmpty() {
        return !isEmpty();
    }

    /**
     * Returns the head element (throws on Nil).
     */
    A head();

    /**
     * Returns the tail of the list (throws on Nil).
     */
    MezzeList<A> tail();

    /**
     * Returns the number of elements.
     */
    int size();

    // ===== PREPEND/APPEND =====

    /**
     * Prepends an element (O(1)).
     */
    default MezzeList<A> prepend(A element) {
        return cons(element, this);
    }

    /**
     * Appends an element (O(n) - builds new list).
     */
    default MezzeList<A> append(A element) {
        if (isEmpty()) {
            return of(element);
        }
        return cons(head(), tail().append(element));
    }

    /**
     * Concatenates another list (O(n) where n is this.size()).
     */
    default MezzeList<A> concat(MezzeList<A> other) {
        if (isEmpty()) {
            return other;
        }
        return cons(head(), tail().concat(other));
    }

    // ===== TRANSFORM =====

    /**
     * Maps a function over the list.
     */
    default <B> MezzeList<B> map(Function<A, B> fn) {
        if (isEmpty()) {
            return empty();
        }
        return cons(fn.apply(head()), tail().map(fn));
    }

    /**
     * Flat maps a function over the list.
     */
    default <B> MezzeList<B> flatMap(Function<A, MezzeList<B>> fn) {
        if (isEmpty()) {
            return empty();
        }
        return fn.apply(head()).concat(tail().flatMap(fn));
    }

    /**
     * Filters elements by predicate.
     */
    default MezzeList<A> filter(Predicate<A> p) {
        if (isEmpty()) {
            return this;
        }
        A h = head();
        MezzeList<A> t = tail().filter(p);
        if (p.test(h)) {
            return cons(h, t);
        } else {
            return t;
        }
    }

    /**
     * Filters elements by negated predicate.
     */
    default MezzeList<A> filterNot(Predicate<A> p) {
        return filter(p.negate());
    }

    // ===== FOLD =====

    /**
     * Left fold.
     */
    default <B> B fold(B init, BiFunction<B, A, B> fn) {
        B acc = init;
        MezzeList<A> current = this;
        while (!current.isEmpty()) {
            acc = fn.apply(acc, current.head());
            current = current.tail();
        }
        return acc;
    }

    /**
     * Right fold.
     */
    default <B> B foldRight(B init, BiFunction<A, B, B> fn) {
        if (isEmpty()) {
            return init;
        }
        return fn.apply(head(), tail().foldRight(init, fn));
    }

    /**
     * Reduce (requires non-empty).
     */
    default A reduce(BiFunction<A, A, A> fn) {
        if (isEmpty()) {
            throw new NoSuchElementException("Empty list");
        }
        return tail().fold(head(), fn);
    }

    // ===== SEARCH =====

    /**
     * Find first element matching predicate.
     */
    default A find(Predicate<A> p) {
        MezzeList<A> current = this;
        while (!current.isEmpty()) {
            A h = current.head();
            if (p.test(h)) {
                return h;
            }
            current = current.tail();
        }
        return null;
    }

    /**
     * Check if any element matches predicate.
     */
    default boolean exists(Predicate<A> p) {
        return find(p) != null;
    }

    /**
     * Check if all elements match predicate.
     */
    default boolean forall(Predicate<A> p) {
        MezzeList<A> current = this;
        while (!current.isEmpty()) {
            if (!p.test(current.head())) {
                return false;
            }
            current = current.tail();
        }
        return true;
    }

    /**
     * Check if list contains element.
     */
    default boolean contains(A element) {
        return exists(e -> java.util.Objects.equals(e, element));
    }

    // ===== ACCESS =====

    /**
     * Get element at index (O(n)).
     */
    default A get(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
        MezzeList<A> current = this;
        for (int i = 0; i < index; i++) {
            if (current.isEmpty()) {
                throw new IndexOutOfBoundsException("Index: " + index);
            }
            current = current.tail();
        }
        if (current.isEmpty()) {
            throw new IndexOutOfBoundsException("Index: " + index);
        }
        return current.head();
    }

    /**
     * Take first n elements.
     */
    default MezzeList<A> take(int n) {
        if (n <= 0 || isEmpty()) {
            return empty();
        }
        return cons(head(), tail().take(n - 1));
    }

    /**
     * Drop first n elements.
     */
    default MezzeList<A> drop(int n) {
        MezzeList<A> current = this;
        for (int i = 0; i < n && !current.isEmpty(); i++) {
            current = current.tail();
        }
        return current;
    }

    /**
     * Take elements while predicate is true.
     */
    default MezzeList<A> takeWhile(Predicate<A> p) {
        if (isEmpty()) {
            return this;
        }
        A h = head();
        if (p.test(h)) {
            return cons(h, tail().takeWhile(p));
        }
        return empty();
    }

    /**
     * Drop elements while predicate is true.
     */
    default MezzeList<A> dropWhile(Predicate<A> p) {
        MezzeList<A> current = this;
        while (!current.isEmpty() && p.test(current.head())) {
            current = current.tail();
        }
        return current;
    }

    // ===== ORDER =====

    /**
     * Reverse the list.
     */
    default MezzeList<A> reverse() {
        return fold(empty(), (acc, elem) -> cons(elem, acc));
    }

    // ===== ITERATION =====

    @Override
    default Iterator<A> iterator() {
        return new Iterator<>() {
            private MezzeList<A> current = MezzeList.this;

            @Override
            public boolean hasNext() {
                return !current.isEmpty();
            }

            @Override
            public A next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                A h = current.head();
                current = current.tail();
                return h;
            }
        };
    }

    /**
     * Apply function to each element for side effects.
     */
    default void foreach(java.util.function.Consumer<A> fn) {
        MezzeList<A> current = this;
        while (!current.isEmpty()) {
            fn.accept(current.head());
            current = current.tail();
        }
    }

    // ===== CONVERSION =====

    /**
     * Convert to array.
     */
    default Object[] toArray() {
        Object[] result = new Object[size()];
        int i = 0;
        MezzeList<A> current = this;
        while (!current.isEmpty()) {
            result[i++] = current.head();
            current = current.tail();
        }
        return result;
    }
}
