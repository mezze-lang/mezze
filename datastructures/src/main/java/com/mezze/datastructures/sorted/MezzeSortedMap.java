package com.mezze.datastructures.sorted;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Immutable sorted map backed by sorted arrays.
 * Uses binary search for O(log n) lookups.
 *
 * @param <K> Key type (must be Comparable)
 * @param <V> Value type
 */
public final class MezzeSortedMap<K extends Comparable<K>, V> implements TruffleObject, Iterable<MezzeSortedMap.Entry<K, V>> {

    @CompilationFinal(dimensions = 1)
    private final Object[] keys;

    @CompilationFinal(dimensions = 1)
    private final Object[] values;

    @CompilationFinal
    private final Comparator<Object> comparator;

    /**
     * Key-value entry.
     */
    public record Entry<K, V>(K key, V value) {}

    @SuppressWarnings("unchecked")
    private MezzeSortedMap(Object[] keys, Object[] values, Comparator<?> comparator) {
        this.keys = keys;
        this.values = values;
        this.comparator = comparator != null
                ? (Comparator<Object>) comparator
                : (a, b) -> ((Comparable<Object>) a).compareTo(b);
    }

    // ===== CONSTRUCTION =====

    /**
     * Returns an empty sorted map.
     */
    public static <K extends Comparable<K>, V> MezzeSortedMap<K, V> empty() {
        return new MezzeSortedMap<>(new Object[0], new Object[0], null);
    }

    /**
     * Returns an empty sorted map with custom comparator.
     */
    public static <K extends Comparable<K>, V> MezzeSortedMap<K, V> empty(Comparator<? super K> comparator) {
        return new MezzeSortedMap<>(new Object[0], new Object[0], comparator);
    }

    /**
     * Creates a sorted map with a single entry.
     */
    public static <K extends Comparable<K>, V> MezzeSortedMap<K, V> of(K key, V value) {
        return new MezzeSortedMap<>(new Object[]{key}, new Object[]{value}, null);
    }

    /**
     * Creates a sorted map from entries.
     */
    @SafeVarargs
    @TruffleBoundary
    public static <K extends Comparable<K>, V> MezzeSortedMap<K, V> of(Entry<K, V>... entries) {
        if (entries.length == 0) return empty();
        if (entries.length == 1) return of(entries[0].key, entries[0].value);

        // Sort by key
        Entry<K, V>[] sorted = entries.clone();
        Arrays.sort(sorted, Comparator.comparing(e -> e.key));

        // Build arrays, handling duplicates (last wins)
        Object[] keys = new Object[sorted.length];
        Object[] values = new Object[sorted.length];
        int size = 0;

        for (Entry<K, V> entry : sorted) {
            if (size > 0 && Objects.equals(keys[size - 1], entry.key)) {
                values[size - 1] = entry.value;  // Update
            } else {
                keys[size] = entry.key;
                values[size] = entry.value;
                size++;
            }
        }

        if (size < sorted.length) {
            keys = Arrays.copyOf(keys, size);
            values = Arrays.copyOf(values, size);
        }

        return new MezzeSortedMap<>(keys, values, null);
    }

    // ===== ACCESS =====

    /**
     * Returns the number of entries.
     */
    public int size() {
        return keys.length;
    }

    /**
     * Returns true if empty.
     */
    public boolean isEmpty() {
        return keys.length == 0;
    }

    /**
     * Gets the value for a key, or null if not present.
     */
    @SuppressWarnings("unchecked")
    public V get(K key) {
        int idx = binarySearch(key);
        return idx >= 0 ? (V) values[idx] : null;
    }

    /**
     * Gets the value for a key, or default if not present.
     */
    public V getOrElse(K key, V defaultValue) {
        V value = get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Returns true if the key is present.
     */
    public boolean containsKey(K key) {
        return binarySearch(key) >= 0;
    }

    /**
     * Binary search for key.
     */
    @TruffleBoundary
    private int binarySearch(K key) {
        int low = 0;
        int high = keys.length - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            @SuppressWarnings("unchecked")
            int cmp = comparator.compare(keys[mid], key);

            if (cmp < 0) {
                low = mid + 1;
            } else if (cmp > 0) {
                high = mid - 1;
            } else {
                return mid;
            }
        }

        return -(low + 1);
    }

    /**
     * Returns the minimum key, or throws if empty.
     */
    @SuppressWarnings("unchecked")
    public K minKey() {
        if (isEmpty()) throw new NoSuchElementException("Empty map");
        return (K) keys[0];
    }

    /**
     * Returns the maximum key, or throws if empty.
     */
    @SuppressWarnings("unchecked")
    public K maxKey() {
        if (isEmpty()) throw new NoSuchElementException("Empty map");
        return (K) keys[keys.length - 1];
    }

    /**
     * Returns the value for the minimum key.
     */
    @SuppressWarnings("unchecked")
    public V minValue() {
        if (isEmpty()) throw new NoSuchElementException("Empty map");
        return (V) values[0];
    }

    /**
     * Returns the value for the maximum key.
     */
    @SuppressWarnings("unchecked")
    public V maxValue() {
        if (isEmpty()) throw new NoSuchElementException("Empty map");
        return (V) values[values.length - 1];
    }

    // ===== UPDATE =====

    /**
     * Returns a new sorted map with the entry added/updated.
     */
    @TruffleBoundary
    public MezzeSortedMap<K, V> put(K key, V value) {
        int idx = binarySearch(key);

        if (idx >= 0) {
            // Update existing
            Object[] newValues = values.clone();
            newValues[idx] = value;
            return new MezzeSortedMap<>(keys, newValues, comparator);
        }

        // Insert new
        int insertPos = -(idx + 1);
        Object[] newKeys = new Object[keys.length + 1];
        Object[] newValues = new Object[values.length + 1];

        System.arraycopy(keys, 0, newKeys, 0, insertPos);
        newKeys[insertPos] = key;
        System.arraycopy(keys, insertPos, newKeys, insertPos + 1, keys.length - insertPos);

        System.arraycopy(values, 0, newValues, 0, insertPos);
        newValues[insertPos] = value;
        System.arraycopy(values, insertPos, newValues, insertPos + 1, values.length - insertPos);

        return new MezzeSortedMap<>(newKeys, newValues, comparator);
    }

    /**
     * Returns a new sorted map with the key removed.
     */
    @TruffleBoundary
    public MezzeSortedMap<K, V> remove(K key) {
        int idx = binarySearch(key);
        if (idx < 0) return this;

        if (keys.length == 1) return empty();

        Object[] newKeys = new Object[keys.length - 1];
        Object[] newValues = new Object[values.length - 1];

        System.arraycopy(keys, 0, newKeys, 0, idx);
        System.arraycopy(keys, idx + 1, newKeys, idx, keys.length - idx - 1);

        System.arraycopy(values, 0, newValues, 0, idx);
        System.arraycopy(values, idx + 1, newValues, idx, values.length - idx - 1);

        return new MezzeSortedMap<>(newKeys, newValues, comparator);
    }

    // ===== RANGE OPERATIONS =====

    /**
     * Returns entries with keys in range [fromKey, toKey].
     */
    @TruffleBoundary
    public MezzeSortedMap<K, V> range(K fromKey, K toKey) {
        int fromIdx = binarySearch(fromKey);
        if (fromIdx < 0) fromIdx = -(fromIdx + 1);

        int toIdx = binarySearch(toKey);
        if (toIdx < 0) {
            toIdx = -(toIdx + 1);
        } else {
            toIdx++;
        }

        if (fromIdx >= toIdx) return empty();

        return new MezzeSortedMap<>(
                Arrays.copyOfRange(keys, fromIdx, toIdx),
                Arrays.copyOfRange(values, fromIdx, toIdx),
                comparator
        );
    }

    /**
     * Returns entries with keys >= fromKey.
     */
    @TruffleBoundary
    public MezzeSortedMap<K, V> rangeFrom(K fromKey) {
        int idx = binarySearch(fromKey);
        if (idx < 0) idx = -(idx + 1);

        if (idx >= keys.length) return empty();

        return new MezzeSortedMap<>(
                Arrays.copyOfRange(keys, idx, keys.length),
                Arrays.copyOfRange(values, idx, values.length),
                comparator
        );
    }

    /**
     * Returns entries with keys <= toKey.
     */
    @TruffleBoundary
    public MezzeSortedMap<K, V> rangeTo(K toKey) {
        int idx = binarySearch(toKey);
        if (idx < 0) {
            idx = -(idx + 1);
        } else {
            idx++;
        }

        if (idx <= 0) return empty();

        return new MezzeSortedMap<>(
                Arrays.copyOfRange(keys, 0, idx),
                Arrays.copyOfRange(values, 0, idx),
                comparator
        );
    }

    // ===== NAVIGATION =====

    /**
     * Returns entry with greatest key <= given, or null if none.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public Entry<K, V> floorEntry(K key) {
        int idx = binarySearch(key);
        if (idx >= 0) {
            return new Entry<>((K) keys[idx], (V) values[idx]);
        }
        int insertPos = -(idx + 1);
        if (insertPos == 0) return null;
        return new Entry<>((K) keys[insertPos - 1], (V) values[insertPos - 1]);
    }

    /**
     * Returns entry with smallest key >= given, or null if none.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public Entry<K, V> ceilingEntry(K key) {
        int idx = binarySearch(key);
        if (idx >= 0) {
            return new Entry<>((K) keys[idx], (V) values[idx]);
        }
        int insertPos = -(idx + 1);
        if (insertPos >= keys.length) return null;
        return new Entry<>((K) keys[insertPos], (V) values[insertPos]);
    }

    // ===== VIEWS =====

    /**
     * Returns all keys.
     */
    public Object[] keys() {
        return keys.clone();
    }

    /**
     * Returns all values.
     */
    public Object[] values() {
        return values.clone();
    }

    // ===== ITERATION =====

    @Override
    public Iterator<Entry<K, V>> iterator() {
        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < keys.length;
            }

            @Override
            @SuppressWarnings("unchecked")
            public Entry<K, V> next() {
                if (!hasNext()) throw new NoSuchElementException();
                Entry<K, V> entry = new Entry<>((K) keys[index], (V) values[index]);
                index++;
                return entry;
            }
        };
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("SortedMap(");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(keys[i]).append(" -> ").append(values[i]);
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeSortedMap<?, ?> other)) return false;
        return Arrays.equals(keys, other.keys) && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(keys) ^ Arrays.hashCode(values);
    }
}
