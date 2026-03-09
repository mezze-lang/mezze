package com.mezze.datastructures.seq;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

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
    @TruffleBoundary
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
    @TruffleBoundary
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
     */
    @TruffleBoundary
    public <B> MezzeSeq<B> flatMap(Function<A, MezzeSeq<B>> fn) {
        if (isEmpty()) return empty();

        java.util.ArrayList<Object> results = new java.util.ArrayList<>();
        for (int i = 0; i < size(); i++) {
            MezzeSeq<B> mapped = fn.apply(get(i));
            for (int j = 0; j < mapped.size(); j++) {
                results.add(mapped.get(j));
            }
        }

        if (results.isEmpty()) return empty();
        return new MezzeSeq<>(SeqStorageFactory.fromArray(results.toArray()));
    }

    /**
     * Filters elements by predicate.
     */
    @TruffleBoundary
    public MezzeSeq<A> filter(Predicate<A> p) {
        if (isEmpty()) return this;

        java.util.ArrayList<Object> results = new java.util.ArrayList<>();
        for (int i = 0; i < size(); i++) {
            A elem = get(i);
            if (p.test(elem)) {
                results.add(elem);
            }
        }

        if (results.isEmpty()) return empty();
        if (results.size() == size()) return this;
        return new MezzeSeq<>(SeqStorageFactory.fromArray(results.toArray()));
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
    @TruffleBoundary
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
    @TruffleBoundary
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
    @TruffleBoundary
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
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public <B> MezzeSeq<B> flatten() {
        if (isEmpty()) return empty();

        java.util.ArrayList<Object> results = new java.util.ArrayList<>();
        for (int i = 0; i < size(); i++) {
            Object elem = get(i);
            if (elem instanceof MezzeSeq<?> seq) {
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

        if (results.isEmpty()) return empty();
        return new MezzeSeq<>(SeqStorageFactory.fromArray(results.toArray()));
    }

    /**
     * Unzip a sequence of pairs into two sequences.
     * Assumes elements are Object[2] pairs.
     */
    @TruffleBoundary
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
    @TruffleBoundary
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
    @TruffleBoundary
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
    @TruffleBoundary
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
     */
    @TruffleBoundary
    public MezzeSeq<A>[] partition(Predicate<A> p) {
        java.util.ArrayList<Object> matching = new java.util.ArrayList<>();
        java.util.ArrayList<Object> nonMatching = new java.util.ArrayList<>();

        for (int i = 0; i < size(); i++) {
            A elem = get(i);
            if (p.test(elem)) {
                matching.add(elem);
            } else {
                nonMatching.add(elem);
            }
        }

        @SuppressWarnings("unchecked")
        MezzeSeq<A>[] result = new MezzeSeq[2];
        result[0] = matching.isEmpty() ? empty() : new MezzeSeq<>(SeqStorageFactory.fromArray(matching.toArray()));
        result[1] = nonMatching.isEmpty() ? empty() : new MezzeSeq<>(SeqStorageFactory.fromArray(nonMatching.toArray()));
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
    @TruffleBoundary
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
    @TruffleBoundary
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
    @TruffleBoundary
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

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
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
