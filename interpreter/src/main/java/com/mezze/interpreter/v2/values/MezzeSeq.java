package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Iterator;
import java.util.Objects;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.BiFunction;

/**
 * Mezze Seq (Sequence) backed by optimized datastructures module.
 *
 * This is the general-purpose sequence type for Mezze's [] syntax.
 *
 * The datastructures MezzeSeq provides:
 * - Tiered storage (empty, single, small, array) for optimal memory
 * - Specialized storage for primitives (long[], double[], boolean[])
 * - SIMD-accelerated operations where applicable
 * - Immutable semantics with structural sharing
 *
 * All "mutating" operations return new MezzeSeq instances with shared structure.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeSeq implements TruffleObject, Iterable<Object> {

    // Singleton empty sequence
    private static final MezzeSeq EMPTY = new MezzeSeq(com.mezze.datastructures.seq.MezzeSeq.empty());

    // Backing store from datastructures module
    private final com.mezze.datastructures.seq.MezzeSeq<Object> delegate;

    private MezzeSeq(com.mezze.datastructures.seq.MezzeSeq<Object> delegate) {
        this.delegate = delegate;
    }

    // ===== FACTORY METHODS =====

    public static MezzeSeq empty() {
        return EMPTY;
    }

    public static MezzeSeq of(Object element) {
        return new MezzeSeq(com.mezze.datastructures.seq.MezzeSeq.of(element));
    }

    public static MezzeSeq of(Object e1, Object e2) {
        return new MezzeSeq(com.mezze.datastructures.seq.MezzeSeq.of(e1, e2));
    }

    public static MezzeSeq of(Object e1, Object e2, Object e3) {
        return new MezzeSeq(com.mezze.datastructures.seq.MezzeSeq.of(e1, e2, e3));
    }

    @TruffleBoundary
    public static MezzeSeq of(Object... elements) {
        if (elements.length == 0) return EMPTY;
        return new MezzeSeq(com.mezze.datastructures.seq.MezzeSeq.of(elements));
    }

    @TruffleBoundary
    public static MezzeSeq fromIterable(Iterable<?> elements) {
        java.util.ArrayList<Object> list = new java.util.ArrayList<>();
        for (Object elem : elements) {
            list.add(elem);
        }
        if (list.isEmpty()) return EMPTY;
        return new MezzeSeq(com.mezze.datastructures.seq.MezzeSeq.fromArray(list.toArray()));
    }

    /**
     * Create a sequence from a Java array.
     */
    @TruffleBoundary
    public static MezzeSeq fromArray(Object[] elements) {
        if (elements.length == 0) return EMPTY;
        return new MezzeSeq(com.mezze.datastructures.seq.MezzeSeq.fromArray(elements));
    }

    /**
     * Create a sequence of longs (unboxed storage).
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public static MezzeSeq fromLongs(long... elements) {
        if (elements.length == 0) return EMPTY;
        return new MezzeSeq((com.mezze.datastructures.seq.MezzeSeq<Object>) (Object)
            com.mezze.datastructures.seq.MezzeSeq.fromLongs(elements));
    }

    /**
     * Create a sequence of doubles (unboxed storage).
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public static MezzeSeq fromDoubles(double... elements) {
        if (elements.length == 0) return EMPTY;
        return new MezzeSeq((com.mezze.datastructures.seq.MezzeSeq<Object>) (Object)
            com.mezze.datastructures.seq.MezzeSeq.fromDoubles(elements));
    }

    /**
     * Create a range [start, end).
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public static MezzeSeq range(long start, long end) {
        if (start >= end) return EMPTY;
        return new MezzeSeq((com.mezze.datastructures.seq.MezzeSeq<Object>) (Object)
            com.mezze.datastructures.seq.MezzeSeq.range(start, end));
    }

    /**
     * Create a range with step.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public static MezzeSeq range(long start, long end, long step) {
        return new MezzeSeq((com.mezze.datastructures.seq.MezzeSeq<Object>) (Object)
            com.mezze.datastructures.seq.MezzeSeq.range(start, end, step));
    }

    /**
     * Create a sequence filled with the same value.
     */
    @TruffleBoundary
    public static MezzeSeq fill(int count, Object value) {
        if (count <= 0) return EMPTY;
        return new MezzeSeq(com.mezze.datastructures.seq.MezzeSeq.fill(count, value));
    }

    // ===== BASIC OPERATIONS =====

    public int size() {
        return delegate.size();
    }

    public int length() {
        return delegate.size();
    }

    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    public boolean nonEmpty() {
        return delegate.nonEmpty();
    }

    public Object get(int index) {
        return delegate.get(index);
    }

    public boolean isValidIndex(int index) {
        return index >= 0 && index < delegate.size();
    }

    public Object first() {
        return delegate.first();
    }

    public Object last() {
        return delegate.last();
    }

    public Object head() {
        return delegate.head();
    }

    // ===== PERSISTENT UPDATE OPERATIONS =====

    @TruffleBoundary
    public MezzeSeq append(Object element) {
        return new MezzeSeq(delegate.append(element));
    }

    @TruffleBoundary
    public MezzeSeq prepend(Object element) {
        return new MezzeSeq(delegate.prepend(element));
    }

    @TruffleBoundary
    public MezzeSeq update(int index, Object element) {
        return new MezzeSeq(delegate.update(index, element));
    }

    @TruffleBoundary
    public MezzeSeq removeAt(int index) {
        return new MezzeSeq(delegate.remove(index));
    }

    @TruffleBoundary
    public MezzeSeq concat(MezzeSeq other) {
        return new MezzeSeq(delegate.concat(other.delegate));
    }

    @TruffleBoundary
    public MezzeSeq slice(int start, int end) {
        return new MezzeSeq(delegate.slice(start, end));
    }

    @TruffleBoundary
    public MezzeSeq reverse() {
        return new MezzeSeq(delegate.reverse());
    }

    @TruffleBoundary
    public MezzeSeq take(int n) {
        return new MezzeSeq(delegate.take(n));
    }

    @TruffleBoundary
    public MezzeSeq drop(int n) {
        return new MezzeSeq(delegate.drop(n));
    }

    @TruffleBoundary
    public MezzeSeq tail() {
        return new MezzeSeq(delegate.tail());
    }

    @TruffleBoundary
    public MezzeSeq init() {
        return new MezzeSeq(delegate.init());
    }

    // ===== SEARCH OPERATIONS =====

    @TruffleBoundary
    public boolean contains(Object element) {
        return delegate.contains(element);
    }

    @TruffleBoundary
    public int indexOf(Object element) {
        return delegate.indexOf(element);
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public int findIndex(Predicate<Object> p) {
        return delegate.findIndex((Predicate) p);
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public Object find(Predicate<Object> p) {
        return delegate.find((Predicate) p);
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public boolean exists(Predicate<Object> p) {
        return delegate.exists((Predicate) p);
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public boolean forall(Predicate<Object> p) {
        return delegate.forall((Predicate) p);
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public int count(Predicate<Object> p) {
        return delegate.count((Predicate) p);
    }

    // ===== TRANSFORM OPERATIONS =====

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSeq map(Function<Object, Object> fn) {
        return new MezzeSeq(delegate.map((Function) fn));
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSeq flatMap(Function<Object, MezzeSeq> fn) {
        return new MezzeSeq(delegate.flatMap(elem -> {
            MezzeSeq result = fn.apply(elem);
            return result.delegate;
        }));
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSeq filter(Predicate<Object> p) {
        return new MezzeSeq(delegate.filter((Predicate) p));
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSeq filterNot(Predicate<Object> p) {
        return new MezzeSeq(delegate.filterNot((Predicate) p));
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSeq takeWhile(Predicate<Object> p) {
        return new MezzeSeq(delegate.takeWhile((Predicate) p));
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSeq dropWhile(Predicate<Object> p) {
        return new MezzeSeq(delegate.dropWhile((Predicate) p));
    }

    // ===== FOLD/REDUCE =====

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public Object fold(Object init, BiFunction<Object, Object, Object> fn) {
        return delegate.fold(init, (BiFunction) fn);
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public Object foldRight(Object init, BiFunction<Object, Object, Object> fn) {
        return delegate.foldRight(init, (BiFunction) fn);
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public Object reduce(BiFunction<Object, Object, Object> fn) {
        return delegate.reduce((BiFunction) fn);
    }

    // ===== SORT =====

    @TruffleBoundary
    public MezzeSeq sort() {
        return new MezzeSeq(delegate.sort());
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSeq sortBy(Comparator<Object> comparator) {
        return new MezzeSeq(delegate.sortBy((Comparator) comparator));
    }

    @TruffleBoundary
    public MezzeSeq distinct() {
        return new MezzeSeq(delegate.distinct());
    }

    // ===== GROUPING =====

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSeq[] partition(Predicate<Object> p) {
        com.mezze.datastructures.seq.MezzeSeq<Object>[] parts = delegate.partition((Predicate) p);
        return new MezzeSeq[] {
            new MezzeSeq(parts[0]),
            new MezzeSeq(parts[1])
        };
    }

    @TruffleBoundary
    public MezzeSeq[] splitAt(int index) {
        com.mezze.datastructures.seq.MezzeSeq<Object>[] parts = delegate.splitAt(index);
        return new MezzeSeq[] {
            new MezzeSeq(parts[0]),
            new MezzeSeq(parts[1])
        };
    }

    // ===== ZIPPING =====

    @TruffleBoundary
    public MezzeSeq zip(MezzeSeq other) {
        com.mezze.datastructures.seq.MezzeSeq<Object[]> zipped = delegate.zip(other.delegate);
        // Convert Object[] pairs to a format suitable for Mezze
        return new MezzeSeq(zipped.map(pair -> pair));
    }

    @TruffleBoundary
    public MezzeSeq zipWithIndex() {
        return new MezzeSeq(delegate.zipWithIndex().map(pair -> pair));
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSeq[] unzip() {
        com.mezze.datastructures.seq.MezzeSeq<Object>[] parts = delegate.unzip();
        return new MezzeSeq[] {
            new MezzeSeq(parts[0]),
            new MezzeSeq(parts[1])
        };
    }

    // ===== ADDITIONAL HELPERS =====

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSeq flatten() {
        if (isEmpty()) return EMPTY;

        java.util.ArrayList<Object> results = new java.util.ArrayList<>();
        for (int i = 0; i < size(); i++) {
            Object elem = get(i);
            if (elem instanceof MezzeSeq seq) {
                // Handle interpreter MezzeSeq
                for (int j = 0; j < seq.size(); j++) {
                    results.add(seq.get(j));
                }
            } else if (elem instanceof com.mezze.datastructures.seq.MezzeSeq<?> seq) {
                // Handle datastructures MezzeSeq
                for (int j = 0; j < seq.size(); j++) {
                    results.add(seq.get(j));
                }
            } else if (elem instanceof Object[] arr) {
                for (Object o : arr) {
                    results.add(o);
                }
            } else {
                results.add(elem);
            }
        }

        if (results.isEmpty()) return EMPTY;
        return MezzeSeq.fromArray(results.toArray());
    }

    @TruffleBoundary
    public MezzeSeq intersperse(Object separator) {
        return new MezzeSeq(delegate.intersperse(separator));
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSeq chunk(int chunkSize) {
        com.mezze.datastructures.seq.MezzeSeq<com.mezze.datastructures.seq.MezzeSeq<Object>> chunks =
            delegate.chunk(chunkSize);
        // Wrap each chunk as a MezzeSeq
        return new MezzeSeq(chunks.map(chunk -> new MezzeSeq(chunk)));
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSeq sliding(int windowSize) {
        com.mezze.datastructures.seq.MezzeSeq<com.mezze.datastructures.seq.MezzeSeq<Object>> windows =
            delegate.sliding(windowSize);
        return new MezzeSeq(windows.map(window -> new MezzeSeq(window)));
    }

    // ===== CONVERSION =====

    @TruffleBoundary
    public Object[] toJavaArray() {
        return delegate.toArray();
    }

    @TruffleBoundary
    public Object[] toArray() {
        return delegate.toArray();
    }

    @Override
    @TruffleBoundary
    public Iterator<Object> iterator() {
        return delegate.iterator();
    }

    /**
     * Get the underlying datastructures MezzeSeq for internal use.
     */
    public com.mezze.datastructures.seq.MezzeSeq<Object> getDelegate() {
        return delegate;
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return delegate.size();
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < delegate.size();
    }

    @ExportMessage
    Object readArrayElement(long index) {
        return delegate.get((int) index);
    }

    @ExportMessage
    boolean isArrayElementModifiable(long index) {
        return false;
    }

    @ExportMessage
    boolean isArrayElementInsertable(long index) {
        return false;
    }

    @ExportMessage
    void writeArrayElement(long index, Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    // Iterator support for Truffle interop
    @ExportMessage
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getIterator() {
        return new SeqIterator(delegate.iterator());
    }

    @ExportLibrary(InteropLibrary.class)
    static final class SeqIterator implements TruffleObject {
        private final Iterator<Object> iterator;

        SeqIterator(Iterator<Object> iterator) {
            this.iterator = iterator;
        }

        @ExportMessage
        boolean isIterator() {
            return true;
        }

        @ExportMessage
        boolean hasIteratorNextElement() {
            return iterator.hasNext();
        }

        @ExportMessage
        Object getIteratorNextElement() {
            return iterator.next();
        }
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeSeq other)) return false;
        return Objects.equals(delegate, other.delegate);
    }

    @Override
    @TruffleBoundary
    public int hashCode() {
        return delegate.hashCode();
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return delegate.toString();
    }
}
