package com.mezze.datastructures.seq;

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
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Immutable indexed sequence with automatic storage strategy selection.
 * User-facing API that delegates to optimized storage implementations.
 *
 * @param <A> Element type
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeSeq<A> implements TruffleObject, Iterable<A> {

    @CompilationFinal
    private final SeqStorage storage;

    private MezzeSeq(SeqStorage storage) {
        this.storage = storage;
    }

    // ===== CONSTRUCTION =====

    /**
     * Returns an empty sequence.
     */
    @SuppressWarnings("unchecked")
    public static <A> MezzeSeq<A> empty() {
        return new MezzeSeq<>(EmptySeqStorage.INSTANCE);
    }

    /**
     * Creates a sequence with a single element.
     */
    public static <A> MezzeSeq<A> of(A element) {
        return new MezzeSeq<>(SeqStorageFactory.createSingle(element));
    }

    /**
     * Creates a sequence from varargs.
     */
    @SafeVarargs
    public static <A> MezzeSeq<A> of(A... elements) {
        if (elements.length == 0) {
            return empty();
        }
        return new MezzeSeq<>(SeqStorageFactory.fromArray(elements));
    }

    /**
     * Creates a sequence from an array.
     */
    public static <A> MezzeSeq<A> fromArray(A[] elements) {
        if (elements.length == 0) {
            return empty();
        }
        return new MezzeSeq<>(SeqStorageFactory.fromArray(elements));
    }

    /**
     * Creates a sequence of longs (unboxed).
     */
    public static MezzeSeq<Long> fromLongs(long... elements) {
        if (elements.length == 0) {
            return empty();
        }
        return new MezzeSeq<>(SeqStorageFactory.fromLongs(elements));
    }

    /**
     * Creates a sequence of doubles (unboxed).
     */
    public static MezzeSeq<Double> fromDoubles(double... elements) {
        if (elements.length == 0) {
            return empty();
        }
        return new MezzeSeq<>(SeqStorageFactory.fromDoubles(elements));
    }

    /**
     * Creates a sequence of booleans (packed).
     */
    public static MezzeSeq<Boolean> fromBooleans(boolean... elements) {
        if (elements.length == 0) {
            return empty();
        }
        return new MezzeSeq<>(SeqStorageFactory.fromBooleans(elements));
    }

    /**
     * Creates a range [start, end).
     */
    public static MezzeSeq<Long> range(long start, long end) {
        if (start >= end) {
            return empty();
        }
        return new MezzeSeq<>(SeqStorageFactory.range(start, end));
    }

    /**
     * Creates a range with step.
     */
    public static MezzeSeq<Long> range(long start, long end, long step) {
        if (step == 0 || (step > 0 && start >= end) || (step < 0 && start <= end)) {
            return empty();
        }
        return new MezzeSeq<>(SeqStorageFactory.range(start, end, step));
    }

    /**
     * Creates a sequence filled with the same value.
     */
    public static <A> MezzeSeq<A> fill(int count, A value) {
        if (count <= 0) {
            return empty();
        }
        return new MezzeSeq<>(SeqStorageFactory.fill(count, value));
    }

    /**
     * Creates a sequence by applying a function to indices.
     */
    @TruffleBoundary
    public static <A> MezzeSeq<A> tabulate(int count, Function<Integer, A> fn) {
        if (count <= 0) {
            return empty();
        }
        return new MezzeSeq<>(SeqStorageFactory.tabulate(count, i -> fn.apply(i)));
    }

    /**
     * Creates from an existing storage (internal use).
     */
    public static <A> MezzeSeq<A> fromStorage(SeqStorage storage) {
        return new MezzeSeq<>(storage);
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
     * Returns the element at the given index.
     */
    @SuppressWarnings("unchecked")
    public A get(int index) {
        return (A) storage.get(index);
    }

    /**
     * Returns the first element, or throws if empty.
     */
    public A first() {
        if (isEmpty()) {
            throw new NoSuchElementException("Empty sequence");
        }
        return get(0);
    }

    /**
     * Returns the last element, or throws if empty.
     */
    public A last() {
        if (isEmpty()) {
            throw new NoSuchElementException("Empty sequence");
        }
        return get(size() - 1);
    }

    /**
     * Returns element at index for unboxed long access.
     */
    public long getLong(int index) {
        if (storage instanceof LongSeqStorage longStorage) {
            return longStorage.getLong(index);
        }
        return (Long) storage.get(index);
    }

    /**
     * Returns element at index for unboxed double access.
     */
    public double getDouble(int index) {
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            return doubleStorage.getDouble(index);
        }
        return (Double) storage.get(index);
    }

    /**
     * Returns element at index for unboxed boolean access.
     */
    public boolean getBoolean(int index) {
        if (storage instanceof BooleanSeqStorage boolStorage) {
            return boolStorage.getBoolean(index);
        }
        return (Boolean) storage.get(index);
    }

    // ===== UPDATE =====

    /**
     * Returns a new sequence with the element at index replaced.
     */
    public MezzeSeq<A> update(int index, A value) {
        return new MezzeSeq<>(storage.update(index, value));
    }

    /**
     * Returns a new sequence with the element appended.
     */
    public MezzeSeq<A> append(A value) {
        return new MezzeSeq<>(storage.append(value));
    }

    /**
     * Returns a new sequence with the element prepended.
     */
    public MezzeSeq<A> prepend(A value) {
        return new MezzeSeq<>(storage.prepend(value));
    }

    /**
     * Returns a new sequence with element inserted at index.
     */
    public MezzeSeq<A> insert(int index, A value) {
        if (index < 0 || index > size()) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
        }
        if (index == 0) return prepend(value);
        if (index == size()) return append(value);

        SeqStorage left = storage.slice(0, index);
        SeqStorage right = storage.slice(index, size());
        SeqStorage single = SeqStorageFactory.createSingle(value);
        return new MezzeSeq<>(left.concat(single).concat(right));
    }

    /**
     * Returns a new sequence with element at index removed.
     */
    public MezzeSeq<A> remove(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
        }
        if (size() == 1) return empty();

        SeqStorage left = storage.slice(0, index);
        SeqStorage right = storage.slice(index + 1, size());
        return new MezzeSeq<>(left.concat(right));
    }

    // ===== SLICING =====

    /**
     * Returns a slice from start (inclusive) to end (exclusive).
     */
    public MezzeSeq<A> slice(int start, int end) {
        return new MezzeSeq<>(storage.slice(start, end));
    }

    /**
     * Returns the first n elements.
     */
    public MezzeSeq<A> take(int n) {
        if (n <= 0) return empty();
        if (n >= size()) return this;
        return slice(0, n);
    }

    /**
     * Returns all but the first n elements.
     */
    public MezzeSeq<A> drop(int n) {
        if (n <= 0) return this;
        if (n >= size()) return empty();
        return slice(n, size());
    }

    /**
     * Returns elements while predicate is true.
     */
    @TruffleBoundary
    public MezzeSeq<A> takeWhile(Predicate<A> p) {
        int count = 0;
        for (int i = 0; i < size(); i++) {
            if (p.test(get(i))) {
                count++;
            } else {
                break;
            }
        }
        return take(count);
    }

    /**
     * Drops elements while predicate is true.
     */
    @TruffleBoundary
    public MezzeSeq<A> dropWhile(Predicate<A> p) {
        int count = 0;
        for (int i = 0; i < size(); i++) {
            if (p.test(get(i))) {
                count++;
            } else {
                break;
            }
        }
        return drop(count);
    }

    // ===== CONCAT =====

    /**
     * Returns a new sequence with the other sequence appended.
     */
    public MezzeSeq<A> concat(MezzeSeq<? extends A> other) {
        if (isEmpty()) {
            @SuppressWarnings("unchecked")
            MezzeSeq<A> result = (MezzeSeq<A>) other;
            return result;
        }
        if (other.isEmpty()) {
            return this;
        }
        return new MezzeSeq<>(storage.concat(other.storage));
    }

    // ===== TRANSFORM =====

    /**
     * Applies a function to each element.
     * Pre-allocates result array for efficiency.
     */
    @TruffleBoundary
    public <B> MezzeSeq<B> map(Function<A, B> fn) {
        if (isEmpty()) return empty();

        Object[] results = new Object[size()];
        for (int i = 0; i < size(); i++) {
            results[i] = fn.apply(get(i));
        }
        // Use unsafe version since we just allocated the array and won't mutate it
        return new MezzeSeq<>(SeqStorageFactory.fromArrayUnsafe(results));
    }

    /**
     * Applies a function and flattens the result.
     * Optimized: Uses initial size estimate and grows array as needed.
     */
    @TruffleBoundary
    public <B> MezzeSeq<B> flatMap(Function<A, MezzeSeq<B>> fn) {
        if (isEmpty()) return empty();

        int sz = size();
        // Initial estimate: assume each element produces ~2 results on average
        int estimatedSize = Math.max(sz * 2, 16);
        Object[] results = new Object[estimatedSize];
        int count = 0;

        for (int i = 0; i < sz; i++) {
            MezzeSeq<B> mapped = fn.apply(get(i));
            int mappedSize = mapped.size();

            // Grow array if needed
            if (count + mappedSize > results.length) {
                int newSize = Math.max(results.length * 2, count + mappedSize);
                Object[] newResults = new Object[newSize];
                System.arraycopy(results, 0, newResults, 0, count);
                results = newResults;
            }

            // Copy mapped elements
            for (int j = 0; j < mappedSize; j++) {
                results[count++] = mapped.get(j);
            }
        }

        if (count == 0) return empty();

        // Trim to actual size
        if (count == results.length) {
            return new MezzeSeq<>(SeqStorageFactory.fromArrayUnsafe(results));
        }
        Object[] trimmed = new Object[count];
        System.arraycopy(results, 0, trimmed, 0, count);
        return new MezzeSeq<>(SeqStorageFactory.fromArrayUnsafe(trimmed));
    }

    /**
     * Filters elements by predicate.
     * Optimized: Uses pre-allocated array with trimming instead of ArrayList.
     */
    @TruffleBoundary
    public MezzeSeq<A> filter(Predicate<A> p) {
        if (isEmpty()) return this;

        int sz = size();
        // Pre-allocate max possible size
        Object[] results = new Object[sz];
        int count = 0;

        for (int i = 0; i < sz; i++) {
            A elem = get(i);
            if (p.test(elem)) {
                results[count++] = elem;
            }
        }

        if (count == 0) return empty();
        if (count == sz) return this;

        // Trim to actual size
        Object[] trimmed = new Object[count];
        System.arraycopy(results, 0, trimmed, 0, count);
        return new MezzeSeq<>(SeqStorageFactory.fromArray(trimmed));
    }

    /**
     * Filters elements by negated predicate.
     */
    public MezzeSeq<A> filterNot(Predicate<A> p) {
        return filter(p.negate());
    }

    // ===== FOLD/REDUCE =====

    /**
     * Left fold with initial value.
     */
    @TruffleBoundary
    public <B> B fold(B init, BiFunction<B, A, B> fn) {
        B acc = init;
        for (int i = 0; i < size(); i++) {
            acc = fn.apply(acc, get(i));
        }
        return acc;
    }

    /**
     * Right fold with initial value.
     */
    @TruffleBoundary
    public <B> B foldRight(B init, BiFunction<A, B, B> fn) {
        B acc = init;
        for (int i = size() - 1; i >= 0; i--) {
            acc = fn.apply(get(i), acc);
        }
        return acc;
    }

    /**
     * Reduce without initial value (requires non-empty).
     */
    @TruffleBoundary
    public A reduce(BiFunction<A, A, A> fn) {
        if (isEmpty()) {
            throw new NoSuchElementException("Empty sequence");
        }
        A acc = get(0);
        for (int i = 1; i < size(); i++) {
            acc = fn.apply(acc, get(i));
        }
        return acc;
    }

    // ===== NUMERIC OPERATIONS (Optimized) =====

    /**
     * Sum of all elements (requires Long elements).
     */
    public long sumLong() {
        if (storage instanceof LongSeqStorage longStorage) {
            return longStorage.sum();
        }
        throw new UnsupportedOperationException("sumLong requires Long elements");
    }

    /**
     * Sum of all elements (requires Double elements).
     */
    public double sumDouble() {
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            return doubleStorage.sum();
        }
        throw new UnsupportedOperationException("sumDouble requires Double elements");
    }

    /**
     * Product of all elements (requires Long elements).
     */
    public long productLong() {
        if (storage instanceof LongSeqStorage longStorage) {
            return longStorage.product();
        }
        throw new UnsupportedOperationException("productLong requires Long elements");
    }

    /**
     * Product of all elements (requires Double elements).
     */
    public double productDouble() {
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            return doubleStorage.product();
        }
        throw new UnsupportedOperationException("productDouble requires Double elements");
    }

    /**
     * Minimum element (requires Long elements).
     */
    public long minLong() {
        if (storage instanceof LongSeqStorage longStorage) {
            return longStorage.min();
        }
        throw new UnsupportedOperationException("minLong requires Long elements");
    }

    /**
     * Minimum element (requires Double elements).
     */
    public double minDouble() {
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            return doubleStorage.min();
        }
        throw new UnsupportedOperationException("minDouble requires Double elements");
    }

    /**
     * Maximum element (requires Long elements).
     */
    public long maxLong() {
        if (storage instanceof LongSeqStorage longStorage) {
            return longStorage.max();
        }
        throw new UnsupportedOperationException("maxLong requires Long elements");
    }

    /**
     * Maximum element (requires Double elements).
     */
    public double maxDouble() {
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            return doubleStorage.max();
        }
        throw new UnsupportedOperationException("maxDouble requires Double elements");
    }

    /**
     * Average of all elements (requires Double elements).
     */
    public double average() {
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            return doubleStorage.average();
        }
        throw new UnsupportedOperationException("average requires Double elements");
    }

    // ===== SEARCH =====

    /**
     * Find first element matching predicate.
     */
    @TruffleBoundary
    public A find(Predicate<A> p) {
        for (int i = 0; i < size(); i++) {
            A elem = get(i);
            if (p.test(elem)) {
                return elem;
            }
        }
        return null;
    }

    /**
     * Find index of first element matching predicate.
     */
    @TruffleBoundary
    public int findIndex(Predicate<A> p) {
        for (int i = 0; i < size(); i++) {
            if (p.test(get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Find index of element.
     */
    public int indexOf(A elem) {
        if (storage instanceof LongSeqStorage longStorage && elem instanceof Long l) {
            return longStorage.indexOf(l);
        }
        if (storage instanceof DoubleSeqStorage doubleStorage && elem instanceof Double d) {
            return doubleStorage.indexOf(d);
        }
        if (storage instanceof ObjectSeqStorage objStorage) {
            return objStorage.indexOf(elem);
        }

        for (int i = 0; i < size(); i++) {
            if (Objects.equals(get(i), elem)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Check if contains element.
     */
    public boolean contains(A elem) {
        return indexOf(elem) >= 0;
    }

    /**
     * Check if any element matches predicate.
     */
    @TruffleBoundary
    public boolean exists(Predicate<A> p) {
        for (int i = 0; i < size(); i++) {
            if (p.test(get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if all elements match predicate.
     */
    @TruffleBoundary
    public boolean forall(Predicate<A> p) {
        for (int i = 0; i < size(); i++) {
            if (!p.test(get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Count elements matching predicate.
     */
    @TruffleBoundary
    public int count(Predicate<A> p) {
        int count = 0;
        for (int i = 0; i < size(); i++) {
            if (p.test(get(i))) {
                count++;
            }
        }
        return count;
    }

    // ===== ORDER =====

    /**
     * Reverse the sequence.
     */
    public MezzeSeq<A> reverse() {
        if (size() <= 1) return this;

        if (storage instanceof LongSeqStorage longStorage) {
            return new MezzeSeq<>(longStorage.reverse());
        }
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            return new MezzeSeq<>(doubleStorage.reverse());
        }
        if (storage instanceof BooleanSeqStorage boolStorage) {
            return new MezzeSeq<>(boolStorage.reverse());
        }
        if (storage instanceof ObjectSeqStorage objStorage) {
            return new MezzeSeq<>(objStorage.reverse());
        }

        throw new IllegalStateException("Unknown storage type");
    }

    /**
     * Sort using natural ordering.
     */
    public MezzeSeq<A> sort() {
        if (size() <= 1) return this;

        if (storage instanceof LongSeqStorage longStorage) {
            return new MezzeSeq<>(longStorage.sort());
        }
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            return new MezzeSeq<>(doubleStorage.sort());
        }
        if (storage instanceof ObjectSeqStorage objStorage) {
            return new MezzeSeq<>(objStorage.sort());
        }

        throw new UnsupportedOperationException("Sort not supported for this element type");
    }

    /**
     * Remove duplicates (keeps first occurrence).
     */
    public MezzeSeq<A> distinct() {
        if (size() <= 1) return this;

        if (storage instanceof ObjectSeqStorage objStorage) {
            return new MezzeSeq<>(objStorage.distinct());
        }

        // For primitive storages, convert to object and deduplicate
        java.util.LinkedHashSet<Object> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < size(); i++) {
            seen.add(get(i));
        }
        if (seen.size() == size()) return this;
        return new MezzeSeq<>(SeqStorageFactory.fromArray(seen.toArray()));
    }

    /**
     * Sort using a custom comparator.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSeq<A> sortBy(java.util.Comparator<? super A> comparator) {
        if (size() <= 1) return this;

        Object[] arr = toArray();
        java.util.Arrays.sort(arr, (a, b) -> comparator.compare((A) a, (A) b));
        return new MezzeSeq<>(SeqStorageFactory.fromArrayUnsafe(arr));
    }

    /**
     * Sort by extracting a comparable key from each element.
     */
    @TruffleBoundary
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <K extends Comparable<? super K>> MezzeSeq<A> sortOn(Function<? super A, K> keyFn) {
        if (size() <= 1) return this;

        Object[] arr = toArray();
        java.util.Arrays.sort(arr, (a, b) -> {
            K keyA = keyFn.apply((A) a);
            K keyB = keyFn.apply((A) b);
            return keyA.compareTo(keyB);
        });
        return new MezzeSeq<>(SeqStorageFactory.fromArrayUnsafe(arr));
    }

    /**
     * Flatten a sequence of sequences.
     * Optimized: Uses initial size estimate and grows array as needed.
     */
    @SuppressWarnings("unchecked")
    public <B> MezzeSeq<B> flatten() {
        if (isEmpty()) return empty();

        int sz = size();
        // Initial estimate: assume each element produces ~2 results on average
        int estimatedSize = Math.max(sz * 2, 16);
        Object[] results = new Object[estimatedSize];
        int count = 0;

        for (int i = 0; i < sz; i++) {
            Object elem = get(i);
            int elemSize;

            if (elem instanceof MezzeSeq<?> seq) {
                elemSize = seq.size();
                // Grow array if needed
                if (count + elemSize > results.length) {
                    int newSize = Math.max(results.length * 2, count + elemSize);
                    Object[] newResults = new Object[newSize];
                    System.arraycopy(results, 0, newResults, 0, count);
                    results = newResults;
                }
                for (int j = 0; j < elemSize; j++) {
                    results[count++] = seq.get(j);
                }
            } else if (elem instanceof Object[] arr) {
                elemSize = arr.length;
                // Grow array if needed
                if (count + elemSize > results.length) {
                    int newSize = Math.max(results.length * 2, count + elemSize);
                    Object[] newResults = new Object[newSize];
                    System.arraycopy(results, 0, newResults, 0, count);
                    results = newResults;
                }
                System.arraycopy(arr, 0, results, count, elemSize);
                count += elemSize;
            } else {
                // Grow array if needed
                if (count + 1 > results.length) {
                    int newSize = results.length * 2;
                    Object[] newResults = new Object[newSize];
                    System.arraycopy(results, 0, newResults, 0, count);
                    results = newResults;
                }
                results[count++] = elem;
            }
        }

        if (count == 0) return empty();

        // Trim to actual size
        if (count == results.length) {
            return new MezzeSeq<>(SeqStorageFactory.fromArrayUnsafe(results));
        }
        Object[] trimmed = new Object[count];
        System.arraycopy(results, 0, trimmed, 0, count);
        return new MezzeSeq<>(SeqStorageFactory.fromArrayUnsafe(trimmed));
    }

    /**
     * Unzip a sequence of pairs into two sequences.
     * Assumes elements are Object[2] pairs.
     */
    @SuppressWarnings("unchecked")
    public MezzeSeq<Object>[] unzip() {
        if (isEmpty()) {
            MezzeSeq<Object>[] result = new MezzeSeq[2];
            result[0] = empty();
            result[1] = empty();
            return result;
        }

        Object[] firsts = new Object[size()];
        Object[] seconds = new Object[size()];

        for (int i = 0; i < size(); i++) {
            Object elem = get(i);
            if (elem instanceof Object[] pair && pair.length >= 2) {
                firsts[i] = pair[0];
                seconds[i] = pair[1];
            } else {
                throw new IllegalArgumentException("Element at index " + i + " is not a pair");
            }
        }

        MezzeSeq<Object>[] result = new MezzeSeq[2];
        result[0] = new MezzeSeq<>(SeqStorageFactory.fromArrayUnsafe(firsts));
        result[1] = new MezzeSeq<>(SeqStorageFactory.fromArrayUnsafe(seconds));
        return result;
    }

    /**
     * Insert a separator between each element.
     */
    public MezzeSeq<A> intersperse(A separator) {
        if (size() <= 1) return this;

        Object[] results = new Object[size() * 2 - 1];
        for (int i = 0; i < size(); i++) {
            results[i * 2] = get(i);
            if (i < size() - 1) {
                results[i * 2 + 1] = separator;
            }
        }
        return new MezzeSeq<>(SeqStorageFactory.fromArrayUnsafe(results));
    }

    /**
     * Group elements by key function.
     * Returns a map from keys to sequences of elements with that key.
     */
    @TruffleBoundary
    public <K> java.util.Map<K, MezzeSeq<A>> groupBy(Function<? super A, K> keyFn) {
        java.util.LinkedHashMap<K, java.util.ArrayList<Object>> groups = new java.util.LinkedHashMap<>();

        for (int i = 0; i < size(); i++) {
            A elem = get(i);
            K key = keyFn.apply(elem);
            groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(elem);
        }

        java.util.LinkedHashMap<K, MezzeSeq<A>> result = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<K, java.util.ArrayList<Object>> entry : groups.entrySet()) {
            result.put(entry.getKey(), new MezzeSeq<>(SeqStorageFactory.fromArray(entry.getValue().toArray())));
        }
        return result;
    }

    /**
     * Split into chunks of given size.
     */
    public MezzeSeq<MezzeSeq<A>> chunk(int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("Chunk size must be positive");
        }
        if (isEmpty()) return empty();

        int numChunks = (size() + chunkSize - 1) / chunkSize;
        Object[] chunks = new Object[numChunks];

        for (int i = 0; i < numChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, size());
            chunks[i] = slice(start, end);
        }

        return new MezzeSeq<>(SeqStorageFactory.fromArrayUnsafe(chunks));
    }

    /**
     * Sliding window of given size.
     */
    public MezzeSeq<MezzeSeq<A>> sliding(int windowSize) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("Window size must be positive");
        }
        if (isEmpty() || windowSize > size()) return empty();

        int numWindows = size() - windowSize + 1;
        Object[] windows = new Object[numWindows];

        for (int i = 0; i < numWindows; i++) {
            windows[i] = slice(i, i + windowSize);
        }

        return new MezzeSeq<>(SeqStorageFactory.fromArrayUnsafe(windows));
    }

    // ===== GROUPING =====

    /**
     * Partition by predicate into (matching, non-matching).
     * Optimized: Uses pre-allocated arrays with trimming instead of ArrayList.
     */
    @TruffleBoundary
    public MezzeSeq<A>[] partition(Predicate<A> p) {
        int sz = size();
        // Pre-allocate max possible sizes
        Object[] matching = new Object[sz];
        Object[] nonMatching = new Object[sz];
        int matchCount = 0;
        int nonMatchCount = 0;

        for (int i = 0; i < sz; i++) {
            A elem = get(i);
            if (p.test(elem)) {
                matching[matchCount++] = elem;
            } else {
                nonMatching[nonMatchCount++] = elem;
            }
        }

        @SuppressWarnings("unchecked")
        MezzeSeq<A>[] result = new MezzeSeq[2];

        if (matchCount == 0) {
            result[0] = empty();
        } else if (matchCount == sz) {
            result[0] = this;
        } else {
            Object[] trimmedMatch = new Object[matchCount];
            System.arraycopy(matching, 0, trimmedMatch, 0, matchCount);
            result[0] = new MezzeSeq<>(SeqStorageFactory.fromArray(trimmedMatch));
        }

        if (nonMatchCount == 0) {
            result[1] = empty();
        } else if (nonMatchCount == sz) {
            result[1] = this;
        } else {
            Object[] trimmedNonMatch = new Object[nonMatchCount];
            System.arraycopy(nonMatching, 0, trimmedNonMatch, 0, nonMatchCount);
            result[1] = new MezzeSeq<>(SeqStorageFactory.fromArray(trimmedNonMatch));
        }

        return result;
    }

    /**
     * Split at index into (left, right).
     */
    public MezzeSeq<A>[] splitAt(int index) {
        @SuppressWarnings("unchecked")
        MezzeSeq<A>[] result = new MezzeSeq[2];
        result[0] = take(index);
        result[1] = drop(index);
        return result;
    }

    // ===== ZIPPING =====

    /**
     * Zip with another sequence.
     */
    public <B> MezzeSeq<Object[]> zip(MezzeSeq<B> other) {
        int minSize = Math.min(size(), other.size());
        if (minSize == 0) return empty();

        Object[] results = new Object[minSize];
        for (int i = 0; i < minSize; i++) {
            results[i] = new Object[]{get(i), other.get(i)};
        }
        return new MezzeSeq<>(new ObjectSeqStorage(results));
    }

    /**
     * Zip with indices.
     */
    public MezzeSeq<Object[]> zipWithIndex() {
        if (isEmpty()) return empty();

        Object[] results = new Object[size()];
        for (int i = 0; i < size(); i++) {
            results[i] = new Object[]{get(i), (long) i};
        }
        return new MezzeSeq<>(new ObjectSeqStorage(results));
    }

    // ===== ITERATION =====

    @Override
    public Iterator<A> iterator() {
        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < size();
            }

            @Override
            public A next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return get(index++);
            }
        };
    }

    /**
     * Apply a function to each element for side effects.
     */
    @TruffleBoundary
    public void foreach(java.util.function.Consumer<A> fn) {
        for (int i = 0; i < size(); i++) {
            fn.accept(get(i));
        }
    }

    // ===== CONVERSION =====

    /**
     * Convert to Object array.
     */
    public Object[] toArray() {
        Object[] result = new Object[size()];
        for (int i = 0; i < size(); i++) {
            result[i] = get(i);
        }
        return result;
    }

    /**
     * Convert to long array (requires Long elements).
     */
    public long[] toLongArray() {
        if (storage instanceof LongSeqStorage longStorage) {
            return longStorage.getArray().clone();
        }
        throw new UnsupportedOperationException("toLongArray requires Long elements");
    }

    /**
     * Convert to double array (requires Double elements).
     */
    public double[] toDoubleArray() {
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            return doubleStorage.getArray().clone();
        }
        throw new UnsupportedOperationException("toDoubleArray requires Double elements");
    }

    // ===== ALIASES FOR COMPATIBILITY =====

    /**
     * Alias for first() - returns the head element.
     */
    public A head() {
        return first();
    }

    /**
     * Returns all elements except the first.
     */
    public MezzeSeq<A> tail() {
        if (isEmpty()) {
            throw new NoSuchElementException("Empty sequence");
        }
        return drop(1);
    }

    /**
     * Returns all elements except the last.
     */
    public MezzeSeq<A> init() {
        if (isEmpty()) {
            throw new NoSuchElementException("Empty sequence");
        }
        return take(size() - 1);
    }

    /**
     * Alias for size().
     */
    public int length() {
        return size();
    }

    // ===== MUTABLE OPERATIONS (for LocalMut effect) =====

    /**
     * Creates a mutable copy of this sequence.
     * Used by to_mut to get a fresh copy that can be mutated.
     */
    public MezzeSeq<A> copyToMutable() {
        if (storage instanceof LongSeqStorage longStorage) {
            return new MezzeSeq<>(longStorage.copyToMutable());
        }
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            return new MezzeSeq<>(doubleStorage.copyToMutable());
        }
        if (storage instanceof ObjectSeqStorage objStorage) {
            return new MezzeSeq<>(objStorage.copyToMutable());
        }
        if (storage instanceof EmptySeqStorage) {
            return this; // Empty is immutable anyway
        }
        if (storage instanceof BooleanSeqStorage) {
            // BooleanSeqStorage doesn't have mutable ops yet - convert to object
            Object[] arr = toArray();
            return new MezzeSeq<>(new ObjectSeqStorage(arr));
        }
        throw new IllegalStateException("Unknown storage type: " + storage.getClass());
    }

    /**
     * In-place set - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutSet(int index, A value) {
        if (storage instanceof LongSeqStorage longStorage && value instanceof Long l) {
            longStorage.mutSet(index, l);
        } else if (storage instanceof DoubleSeqStorage doubleStorage && value instanceof Double d) {
            doubleStorage.mutSet(index, d);
        } else if (storage instanceof ObjectSeqStorage objStorage) {
            objStorage.mutSet(index, value);
        } else {
            throw new UnsupportedOperationException("mutSet not supported for storage: " + storage.getClass());
        }
    }

    /**
     * In-place set for unboxed long.
     */
    public void mutSetLong(int index, long value) {
        if (storage instanceof LongSeqStorage longStorage) {
            longStorage.mutSet(index, value);
        } else {
            throw new UnsupportedOperationException("mutSetLong requires LongSeqStorage");
        }
    }

    /**
     * In-place set for unboxed double.
     */
    public void mutSetDouble(int index, double value) {
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            doubleStorage.mutSet(index, value);
        } else {
            throw new UnsupportedOperationException("mutSetDouble requires DoubleSeqStorage");
        }
    }

    /**
     * In-place swap - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutSwap(int i, int j) {
        if (storage instanceof LongSeqStorage longStorage) {
            longStorage.mutSwap(i, j);
        } else if (storage instanceof DoubleSeqStorage doubleStorage) {
            doubleStorage.mutSwap(i, j);
        } else if (storage instanceof ObjectSeqStorage objStorage) {
            objStorage.mutSwap(i, j);
        } else {
            throw new UnsupportedOperationException("mutSwap not supported for storage: " + storage.getClass());
        }
    }

    /**
     * In-place sort - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutSort() {
        if (storage instanceof LongSeqStorage longStorage) {
            longStorage.mutSort();
        } else if (storage instanceof DoubleSeqStorage doubleStorage) {
            doubleStorage.mutSort();
        } else {
            throw new UnsupportedOperationException("mutSort not supported for storage: " + storage.getClass());
        }
    }

    /**
     * In-place reverse - mutates the underlying array.
     * Only safe within LocalMut context.
     */
    public void mutReverse() {
        if (storage instanceof LongSeqStorage longStorage) {
            longStorage.mutReverse();
        } else if (storage instanceof DoubleSeqStorage doubleStorage) {
            doubleStorage.mutReverse();
        } else if (storage instanceof ObjectSeqStorage objStorage) {
            objStorage.mutReverse();
        } else {
            throw new UnsupportedOperationException("mutReverse not supported for storage: " + storage.getClass());
        }
    }

    // ===== INTERNAL =====

    /**
     * Returns the storage (for internal use).
     */
    public SeqStorage getStorage() {
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
        return storage instanceof LongSeqStorage;
    }

    /**
     * Returns true if using double storage.
     */
    public boolean isDoubleStorage() {
        return storage instanceof DoubleSeqStorage;
    }

    /**
     * Returns true if using boolean storage.
     */
    public boolean isBooleanStorage() {
        return storage instanceof BooleanSeqStorage;
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
        return storage.get((int) index);
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
        return new SeqIterator(iterator());
    }

    @ExportLibrary(InteropLibrary.class)
    static final class SeqIterator implements TruffleObject {
        private final Iterator<?> iterator;

        SeqIterator(Iterator<?> iterator) {
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
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeSeq<?> other)) return false;
        if (size() != other.size()) return false;
        for (int i = 0; i < size(); i++) {
            if (!Objects.equals(get(i), other.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < size(); i++) {
            result = 31 * result + Objects.hashCode(get(i));
        }
        return result;
    }
}
