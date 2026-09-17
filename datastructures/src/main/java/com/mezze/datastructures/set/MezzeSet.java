package com.mezze.datastructures.set;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Immutable set with automatic storage strategy selection.
 *
 * @param <A> Element type
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeSet<A> implements TruffleObject, Iterable<A> {

    @CompilationFinal
    private final SetStorage storage;

    private MezzeSet(SetStorage storage) {
        this.storage = storage;
    }

    // ===== CONSTRUCTION =====

    /**
     * Returns an empty set.
     */
    public static <A> MezzeSet<A> empty() {
        return new MezzeSet<>(EmptySetStorage.INSTANCE);
    }

    /**
     * Creates a single-element set.
     */
    public static <A> MezzeSet<A> of(A element) {
        return new MezzeSet<>(new SingleElemSetStorage(element));
    }

    /**
     * Creates a set from varargs.
     */
    @SafeVarargs
    public static <A> MezzeSet<A> of(A... elements) {
        if (elements.length == 0) {
            return empty();
        }
        if (elements.length == 1) {
            return of(elements[0]);
        }

        MezzeSet<A> result = empty();
        for (A elem : elements) {
            result = result.add(elem);
        }
        return result;
    }

    /**
     * Creates a set of longs (unboxed).
     */
    public static MezzeSet<Long> ofLongs(long... elements) {
        if (elements.length == 0) {
            return empty();
        }
        if (elements.length == 1) {
            return new MezzeSet<>(new SingleElemSetStorage(elements[0]));
        }

        // Deduplicate
        long[] unique = new long[elements.length];
        int uniqueCount = 0;
        outer:
        for (long elem : elements) {
            for (int i = 0; i < uniqueCount; i++) {
                if (unique[i] == elem) continue outer;
            }
            unique[uniqueCount++] = elem;
        }

        if (uniqueCount == 1) {
            return new MezzeSet<>(new SingleElemSetStorage(unique[0]));
        }

        long[] finalArray = new long[uniqueCount];
        System.arraycopy(unique, 0, finalArray, 0, uniqueCount);
        return new MezzeSet<>(new LongSetStorage(finalArray));
    }

    /**
     * Creates from an existing storage.
     */
    /** The storage strategy backing this set. Callers use it to ask whether the set
     *  already buckets by an element instance (see EvidenceSetStorage) before
     *  rebuilding it under one. */
    public SetStorage storage() {
        return storage;
    }

    public static <A> MezzeSet<A> fromStorage(SetStorage storage) {
        return new MezzeSet<>(storage);
    }

    // ===== ACCESS =====

    /**
     * Returns the number of elements.
     */
    public int size() {
        return storage.size();
    }

    /**
     * Returns true if empty.
     */
    public boolean isEmpty() {
        return storage.isEmpty();
    }

    /**
     * Returns true if not empty.
     */
    public boolean nonEmpty() {
        return !storage.isEmpty();
    }

    /**
     * Returns true if the element is present.
     */
    public boolean contains(A elem) {
        return storage.contains(elem);
    }

    /**
     * Unboxed contains for long sets.
     */
    public boolean containsLong(long elem) {
        if (storage instanceof LongSetStorage longStorage) {
            return longStorage.containsLong(elem);
        }
        return storage.contains(elem);
    }

    // ===== UPDATE =====

    /**
     * Returns a new set with the element added.
     */
    public MezzeSet<A> add(A elem) {
        return new MezzeSet<>(storage.add(elem));
    }

    /**
     * Returns a new set with the element added (raw type).
     * For interpreter use with dynamic types.
     */
    @SuppressWarnings("unchecked")
    public MezzeSet<A> addObject(Object elem) {
        return new MezzeSet<>(storage.add(elem));
    }

    /**
     * Returns a new set with all elements from other set added.
     * Optimized: uses mutable HashSet for batch operations.
     */
    public MezzeSet<A> addAll(MezzeSet<A> other) {
        if (other.isEmpty()) return this;
        if (isEmpty()) return other;

        // Use mutable HashSet for bulk insert
        java.util.HashSet<A> mutable = new java.util.HashSet<>(size() + other.size());

        for (A elem : this) {
            mutable.add(elem);
        }
        for (A elem : other) {
            mutable.add(elem);
        }

        return fromMutableSet(mutable);
    }

    /**
     * Creates a MezzeSet from a mutable HashSet (internal use).
     * Optimized: builds HashSetStorage directly instead of adding one by one.
     */
    @SuppressWarnings("unchecked")
    private static <A> MezzeSet<A> fromMutableSet(java.util.HashSet<A> mutable) {
        if (mutable.isEmpty()) return empty();
        if (mutable.size() == 1) {
            return of(mutable.iterator().next());
        }

        // Build HashSetStorage directly from collection (O(n) vs O(n^2))
        return new MezzeSet<>(HashSetStorage.fromCollection(mutable));
    }

    /**
     * Returns a new set with the element removed.
     */
    public MezzeSet<A> remove(A elem) {
        return new MezzeSet<>(storage.remove(elem));
    }

    // ===== SET OPERATIONS =====

    /**
     * Union of two sets.
     */
    public MezzeSet<A> union(MezzeSet<A> other) {
        if (isEmpty()) return other;
        if (other.isEmpty()) return this;

        // Optimized path for LongSetStorage
        if (storage instanceof LongSetStorage ls && other.storage instanceof LongSetStorage otherLs) {
            return new MezzeSet<>(ls.unionLong(otherLs));
        }

        return addAll(other);
    }

    /**
     * Intersection of two sets.
     */
    public MezzeSet<A> intersect(MezzeSet<A> other) {
        if (isEmpty() || other.isEmpty()) return empty();

        // Optimized path for LongSetStorage
        if (storage instanceof LongSetStorage ls && other.storage instanceof LongSetStorage otherLs) {
            LongSetStorage result = ls.intersectLong(otherLs);
            if (result.size() == 0) return empty();
            return new MezzeSet<>(result);
        }

        MezzeSet<A> result = empty();
        MezzeSet<A> smaller = size() <= other.size() ? this : other;
        MezzeSet<A> larger = size() > other.size() ? this : other;

        for (A elem : smaller) {
            if (larger.contains(elem)) {
                result = result.add(elem);
            }
        }
        return result;
    }

    /**
     * Difference (this - other).
     */
    public MezzeSet<A> difference(MezzeSet<A> other) {
        if (isEmpty()) return this;
        if (other.isEmpty()) return this;

        // Optimized path for LongSetStorage
        if (storage instanceof LongSetStorage ls && other.storage instanceof LongSetStorage otherLs) {
            LongSetStorage result = ls.differenceLong(otherLs);
            if (result.size() == 0) return empty();
            return new MezzeSet<>(result);
        }

        MezzeSet<A> result = empty();
        for (A elem : this) {
            if (!other.contains(elem)) {
                result = result.add(elem);
            }
        }
        return result;
    }

    /**
     * Symmetric difference (elements in either but not both).
     */
    public MezzeSet<A> symmetricDifference(MezzeSet<A> other) {
        return union(other).difference(intersect(other));
    }

    /**
     * Returns true if this is a subset of other.
     */
    public boolean isSubsetOf(MezzeSet<A> other) {
        if (size() > other.size()) return false;

        for (A elem : this) {
            if (!other.contains(elem)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if this is a superset of other.
     */
    public boolean isSupersetOf(MezzeSet<A> other) {
        return other.isSubsetOf(this);
    }

    /**
     * Returns true if sets have no common elements.
     */
    public boolean isDisjoint(MezzeSet<A> other) {
        MezzeSet<A> smaller = size() <= other.size() ? this : other;
        MezzeSet<A> larger = size() > other.size() ? this : other;

        for (A elem : smaller) {
            if (larger.contains(elem)) {
                return false;
            }
        }
        return true;
    }

    // ===== TRANSFORM =====

    /**
     * Maps over elements.
     * Optimized: builds HashSetStorage directly from collection.
     */
    @TruffleBoundary
    public <B> MezzeSet<B> map(Function<A, B> fn) {
        if (isEmpty()) return empty();

        java.util.HashSet<B> mutable = new java.util.HashSet<>(size());
        for (A elem : this) {
            mutable.add(fn.apply(elem));
        }

        return fromMutableSetGeneric(mutable);
    }

    /**
     * Creates a MezzeSet from a mutable HashSet of any type (internal use).
     * Optimized: builds HashSetStorage directly instead of adding one by one.
     */
    @SuppressWarnings("unchecked")
    private static <B> MezzeSet<B> fromMutableSetGeneric(java.util.HashSet<B> mutable) {
        if (mutable.isEmpty()) return empty();
        if (mutable.size() == 1) {
            return of(mutable.iterator().next());
        }

        // Build HashSetStorage directly from collection (O(n) vs O(n^2))
        return new MezzeSet<>(HashSetStorage.fromCollection(mutable));
    }

    /**
     * Flat maps over elements.
     */
    @TruffleBoundary
    public <B> MezzeSet<B> flatMap(Function<A, MezzeSet<B>> fn) {
        if (isEmpty()) return empty();

        MezzeSet<B> result = empty();
        for (A elem : this) {
            result = result.union(fn.apply(elem));
        }
        return result;
    }

    /**
     * Filters elements by predicate.
     * Optimized: uses mutable HashSet for batch operations.
     */
    @TruffleBoundary
    public MezzeSet<A> filter(Predicate<A> p) {
        if (isEmpty()) return this;

        java.util.HashSet<A> mutable = new java.util.HashSet<>();
        boolean allMatch = true;

        for (A elem : this) {
            if (p.test(elem)) {
                mutable.add(elem);
            } else {
                allMatch = false;
            }
        }

        // If all elements matched, return this (no change)
        if (allMatch) return this;

        return fromMutableSet(mutable);
    }

    // ===== FOLD =====

    /**
     * Folds over elements.
     */
    @TruffleBoundary
    public <B> B fold(B init, java.util.function.BiFunction<B, A, B> fn) {
        B acc = init;
        for (A elem : this) {
            acc = fn.apply(acc, elem);
        }
        return acc;
    }

    // ===== SEARCH =====

    /**
     * Find first element matching predicate.
     */
    @TruffleBoundary
    public A find(Predicate<A> p) {
        for (A elem : this) {
            if (p.test(elem)) {
                return elem;
            }
        }
        return null;
    }

    /**
     * Check if any element matches predicate.
     */
    @TruffleBoundary
    public boolean exists(Predicate<A> p) {
        return find(p) != null;
    }

    /**
     * Check if all elements match predicate.
     */
    @TruffleBoundary
    public boolean forall(Predicate<A> p) {
        for (A elem : this) {
            if (!p.test(elem)) {
                return false;
            }
        }
        return true;
    }

    // ===== NUMERIC (for Long sets) =====

    /**
     * Sum of all elements (requires Long elements).
     */
    public long sumLong() {
        if (storage instanceof LongSetStorage longStorage) {
            return longStorage.sum();
        }
        throw new UnsupportedOperationException("sumLong requires Long elements");
    }

    /**
     * Minimum element (requires Long elements).
     */
    public long minLong() {
        if (storage instanceof LongSetStorage longStorage) {
            return longStorage.min();
        }
        throw new UnsupportedOperationException("minLong requires Long elements");
    }

    /**
     * Maximum element (requires Long elements).
     */
    public long maxLong() {
        if (storage instanceof LongSetStorage longStorage) {
            return longStorage.max();
        }
        throw new UnsupportedOperationException("maxLong requires Long elements");
    }

    // ===== ITERATION =====

    @Override
    public Iterator<A> iterator() {
        Object[] elements = storage.toArray();

        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < elements.length;
            }

            @Override
            @SuppressWarnings("unchecked")
            public A next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return (A) elements[index++];
            }
        };
    }

    /**
     * Applies function to each element for side effects.
     */
    @TruffleBoundary
    public void foreach(java.util.function.Consumer<A> fn) {
        for (A elem : this) {
            fn.accept(elem);
        }
    }

    // ===== CONVERSION =====

    /**
     * Returns all elements as array.
     */
    @SuppressWarnings("unchecked")
    public A[] toArray() {
        return (A[]) storage.toArray();
    }

    /**
     * Returns all elements as long array (for Long sets).
     */
    public long[] toLongArray() {
        if (storage instanceof LongSetStorage longStorage) {
            return longStorage.toLongArray();
        }
        throw new UnsupportedOperationException("toLongArray requires Long elements");
    }

    // ===== MUTABLE OPERATIONS (for LocalMut effect) =====

    /**
     * Creates a mutable copy of this set.
     * Used by to_mut to get a fresh copy that can be mutated.
     */
    public MezzeSet<A> copyToMutable() {
        if (storage instanceof HashSetStorage hashStorage) {
            return new MezzeSet<>(hashStorage.copyToMutable());
        }
        // For other storage types, convert to HashSetStorage first
        java.util.HashSet<Object> temp = new java.util.HashSet<>();
        for (A elem : this) {
            temp.add(elem);
        }
        return new MezzeSet<>(HashSetStorage.fromCollection(temp).copyToMutable());
    }

    /**
     * Creates a mutable set with given initial capacity.
     * Use this when you know the approximate final size.
     */
    public static <A> MezzeSet<A> mutableWithCapacity(int capacity) {
        // Pre-create a HashSetStorage with enough room
        java.util.HashSet<Object> temp = new java.util.HashSet<>(capacity);
        return new MezzeSet<>(HashSetStorage.fromCollection(temp).copyToMutable());
    }

    /**
     * In-place add - mutates the underlying storage.
     * Only safe within LocalMut context.
     * Returns true if element was new, false if already present.
     */
    public boolean mutAdd(A elem) {
        if (storage instanceof HashSetStorage hashStorage) {
            return hashStorage.mutAdd(elem);
        }
        throw new UnsupportedOperationException("mutAdd requires HashSetStorage - use copyToMutable() first");
    }

    /**
     * In-place remove - mutates the underlying storage.
     * Only safe within LocalMut context.
     * Returns true if element was found and removed.
     */
    public boolean mutRemove(A elem) {
        if (storage instanceof HashSetStorage hashStorage) {
            return hashStorage.mutRemove(elem);
        }
        throw new UnsupportedOperationException("mutRemove requires HashSetStorage - use copyToMutable() first");
    }

    /**
     * In-place clear - mutates the underlying storage.
     * Only safe within LocalMut context.
     */
    public void mutClear() {
        if (storage instanceof HashSetStorage hashStorage) {
            hashStorage.mutClear();
        } else {
            throw new UnsupportedOperationException("mutClear requires HashSetStorage - use copyToMutable() first");
        }
    }

    // ===== INTERNAL =====

    /**
     * Returns the storage (for internal use).
     */
    public SetStorage getStorage() {
        return storage;
    }

    /**
     * Returns the storage strategy tag.
     */
    public byte getStrategyTag() {
        return storage.getStrategyTag();
    }

    /**
     * Returns true if using long storage.
     */
    public boolean isLongStorage() {
        return storage instanceof LongSetStorage;
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    long getArraySize() {
        return storage.size();
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < storage.size();
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object readArrayElement(long index) {
        return storage.toArray()[(int) index];
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isArrayElementModifiable(@SuppressWarnings("unused") long index) {
        return false;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isArrayElementInsertable(@SuppressWarnings("unused") long index) {
        return false;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    void writeArrayElement(@SuppressWarnings("unused") long index, @SuppressWarnings("unused") Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object getIterator() {
        return new SetIterator(iterator());
    }

    @ExportLibrary(InteropLibrary.class)
    static final class SetIterator implements TruffleObject {
        private final Iterator<?> iterator;

        SetIterator(Iterator<?> iterator) {
            this.iterator = iterator;
        }

        @ExportMessage
       @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        boolean isIterator() {
            return true;
        }

        @ExportMessage
       @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        boolean hasIteratorNextElement() {
            return iterator.hasNext();
        }

        @ExportMessage
       @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        Object getIteratorNextElement() throws UnsupportedMessageException {
            if (!iterator.hasNext()) {
                throw UnsupportedMessageException.create();
            }
            return iterator.next();
        }
    }

    // ===== OBJECT METHODS =====

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (A elem : this) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(elem);
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeSet<?> other)) return false;
        if (size() != other.size()) return false;

        MezzeSet<A> otherCast = (MezzeSet<A>) other;
        for (A elem : this) {
            if (!otherCast.contains(elem)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 0;
        for (A elem : this) {
            result += Objects.hashCode(elem);
        }
        return result;
    }
}
