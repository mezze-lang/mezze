package com.mezze.datastructures.sorted;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownKeyException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

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
@ExportLibrary(InteropLibrary.class)
public final class MezzeSortedMap<K extends Comparable<K>, V> implements TruffleObject, Iterable<MezzeSortedMap.Entry<K, V>> {

    @CompilationFinal(dimensions = 1)
    private final Object[] keys;

    @CompilationFinal(dimensions = 1)
    private final Object[] values;

    @CompilationFinal
    private final int length;  // logical size (may be < keys.length with spare capacity)

    @CompilationFinal
    private final Comparator<Object> comparator;

    /** COW ownership token for O(1) amortized insert chains. */
    private final Object[] ownerToken;

    /**
     * Key-value entry.
     */
    public record Entry<K, V>(K key, V value) {}

    /**
     * Raw key-value entry for interpreter use.
     */
    public record RawEntry(Object key, Object value) {}

    @SuppressWarnings("unchecked")
    private MezzeSortedMap(Object[] keys, Object[] values, Comparator<?> comparator) {
        this.keys = keys;
        this.values = values;
        this.length = keys.length;
        this.comparator = comparator != null
                ? (Comparator<Object>) comparator
                : (a, b) -> ((Comparable<Object>) a).compareTo(b);
        this.ownerToken = null;
    }

    @SuppressWarnings("unchecked")
    private MezzeSortedMap(Object[] keys, Object[] values, int length,
                           Comparator<?> comparator, Object[] ownerToken) {
        this.keys = keys;
        this.values = values;
        this.length = length;
        this.comparator = comparator != null
                ? (Comparator<Object>) comparator
                : (a, b) -> ((Comparable<Object>) a).compareTo(b);
        this.ownerToken = ownerToken;
    }

    private boolean isOwner() {
        return ownerToken != null && ownerToken[0] == this;
    }

    private void revokeOwnership() {
        if (ownerToken != null && ownerToken[0] == this) {
            ownerToken[0] = null;
        }
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
        return length;
    }

    /**
     * Returns true if empty.
     */
    public boolean isEmpty() {
        return length == 0;
    }

    /**
     * Gets the value for a key, or null if not present.
     */
    @SuppressWarnings("unchecked")
    public V get(K key) {
        revokeOwnership();
        int idx = binarySearch(key);
        return idx >= 0 ? (V) values[idx] : null;
    }

    /**
     * Gets the value for a key (raw type), or null if not present.
     * For interpreter use with dynamic types.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public Object getObject(Object key) {
        revokeOwnership();
        int idx = binarySearchObject(key);
        return idx >= 0 ? values[idx] : null;
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
        revokeOwnership();
        return binarySearch(key) >= 0;
    }

    /**
     * Returns true if the key is present (raw type).
     * For interpreter use with dynamic types.
     */
    @TruffleBoundary
    public boolean containsKeyObject(Object key) {
        revokeOwnership();
        return binarySearchObject(key) >= 0;
    }

    /**
     * Binary search for key.
     */
    @TruffleBoundary
    private int binarySearch(K key) {
        return binarySearchObject(key);
    }

    /**
     * Binary search for key (raw type).
     */
    @TruffleBoundary
    private int binarySearchObject(Object key) {
        int low = 0;
        int high = length - 1;

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
        return (K) keys[length - 1];
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
        return (V) values[length - 1];
    }

    // ===== UPDATE =====

    /**
     * Returns a new sorted map with the entry added/updated.
     */
    @TruffleBoundary
    public MezzeSortedMap<K, V> put(K key, V value) {
        return putObject(key, value);
    }

    /**
     * Returns a new sorted map with the entry added/updated (raw type).
     * For interpreter use with dynamic types.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSortedMap<K, V> putObject(Object key, Object value) {
        int idx = binarySearchObject(key);

        if (idx >= 0) {
            // Update existing — only clone values array
            Object[] newValues = Arrays.copyOf(values, length);
            newValues[idx] = value;
            return new MezzeSortedMap<>(Arrays.copyOf(keys, length), newValues, comparator);
        }

        // Insert new
        int insertPos = -(idx + 1);

        if (isOwner() && length < keys.length && insertPos == length) {
            // O(1) fast path: appending at end with spare capacity (sorted ascending insert)
            keys[length] = key;
            values[length] = value;
            Object[] token = ownerToken;
            @SuppressWarnings("unchecked")
            MezzeSortedMap<K, V> result = new MezzeSortedMap<>(keys, values, length + 1, comparator, token);
            token[0] = result;
            return result;
        }

        if (isOwner() && length < keys.length) {
            // Spare capacity but inserting in middle — shift in-place
            System.arraycopy(keys, insertPos, keys, insertPos + 1, length - insertPos);
            System.arraycopy(values, insertPos, values, insertPos + 1, length - insertPos);
            keys[insertPos] = key;
            values[insertPos] = value;
            Object[] token = ownerToken;
            @SuppressWarnings("unchecked")
            MezzeSortedMap<K, V> result = new MezzeSortedMap<>(keys, values, length + 1, comparator, token);
            token[0] = result;
            return result;
        }

        // Allocate with spare capacity
        int newCapacity = Math.max(length + 1, length + (length >> 1) + 4);
        Object[] newKeys = new Object[newCapacity];
        Object[] newValues = new Object[newCapacity];

        System.arraycopy(keys, 0, newKeys, 0, insertPos);
        newKeys[insertPos] = key;
        System.arraycopy(keys, insertPos, newKeys, insertPos + 1, length - insertPos);

        System.arraycopy(values, 0, newValues, 0, insertPos);
        newValues[insertPos] = value;
        System.arraycopy(values, insertPos, newValues, insertPos + 1, length - insertPos);

        Object[] token = new Object[1];
        @SuppressWarnings("unchecked")
        MezzeSortedMap<K, V> result = new MezzeSortedMap<>(newKeys, newValues, length + 1, comparator, token);
        token[0] = result;
        return result;
    }

    /**
     * Returns a new sorted map with the key removed.
     */
    @TruffleBoundary
    public MezzeSortedMap<K, V> remove(K key) {
        return removeObject(key);
    }

    /**
     * Returns a new sorted map with the key removed (raw type).
     * For interpreter use with dynamic types.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeSortedMap<K, V> removeObject(Object key) {
        revokeOwnership();
        int idx = binarySearchObject(key);
        if (idx < 0) return this;

        if (length == 1) return empty();

        Object[] newKeys = new Object[length - 1];
        Object[] newValues = new Object[length - 1];

        System.arraycopy(keys, 0, newKeys, 0, idx);
        System.arraycopy(keys, idx + 1, newKeys, idx, length - idx - 1);

        System.arraycopy(values, 0, newValues, 0, idx);
        System.arraycopy(values, idx + 1, newValues, idx, length - idx - 1);

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

        if (idx >= length) return empty();

        return new MezzeSortedMap<>(
                Arrays.copyOfRange(keys, idx, length),
                Arrays.copyOfRange(values, idx, length),
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
        if (insertPos >= length) return null;
        return new Entry<>((K) keys[insertPos], (V) values[insertPos]);
    }

    // ===== VIEWS =====

    /**
     * Returns all keys.
     */
    public Object[] keys() {
        return Arrays.copyOf(keys, length);
    }

    /**
     * Returns all values.
     */
    public Object[] values() {
        return Arrays.copyOf(values, length);
    }

    // ===== ITERATION =====

    @Override
    public Iterator<Entry<K, V>> iterator() {
        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < length;
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

    /**
     * Returns a raw entry iterator for interpreter use with dynamic types.
     */
    public Iterable<RawEntry> rawEntries() {
        return () -> new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < length;
            }

            @Override
            public RawEntry next() {
                if (!hasNext()) throw new NoSuchElementException();
                RawEntry entry = new RawEntry(keys[index], values[index]);
                index++;
                return entry;
            }
        };
    }

    // ===== MUTABLE OPERATIONS (for LocalMut effect) =====

    /**
     * Creates a mutable copy of this sorted map.
     * Used by to_mut to get a fresh copy that can be mutated.
     */
    @TruffleBoundary
    public MezzeSortedMap<K, V> copyToMutable() {
        return new MezzeSortedMap<>(keys.clone(), values.clone(), comparator);
    }

    /**
     * In-place put - mutates the underlying arrays.
     * Only works for updating existing keys (not insertion).
     * For insertion, use the mutable builder approach.
     * Only safe within LocalMut context.
     * Returns true if key was found and updated.
     */
    @TruffleBoundary
    public boolean mutUpdate(K key, V value) {
        int idx = binarySearch(key);
        if (idx >= 0) {
            values[idx] = value;
            return true;
        }
        return false;
    }

    /**
     * In-place update by key (raw type).
     * Only safe within LocalMut context.
     */
    @TruffleBoundary
    public boolean mutUpdateObject(Object key, Object value) {
        int idx = binarySearchObject(key);
        if (idx >= 0) {
            values[idx] = value;
            return true;
        }
        return false;
    }

    /**
     * In-place clear - sets all values to null.
     * Only safe within LocalMut context.
     */
    @TruffleBoundary
    public void mutClearValues() {
        Arrays.fill(values, null);
    }

    /**
     * Returns the internal keys array (for building mutable sorted maps).
     * Only for internal use within LocalMut context.
     */
    public Object[] getKeysInternal() {
        return keys;
    }

    /**
     * Returns the internal values array (for building mutable sorted maps).
     * Only for internal use within LocalMut context.
     */
    public Object[] getValuesInternal() {
        return values;
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("SortedMap(");
        for (int i = 0; i < length; i++) {
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
        return Arrays.equals(keys(), other.keys()) && Arrays.equals(values(), other.values());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(keys()) ^ Arrays.hashCode(values());
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasHashEntries() {
        return true;
    }

    @ExportMessage
    long getHashSize() {
        return length;
    }

    @ExportMessage
    @TruffleBoundary
    boolean isHashEntryReadable(Object key) {
        return containsKeyObject(key);
    }

    @ExportMessage
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    Object readHashValue(Object key) throws UnknownKeyException {
        try {
            V value = get((K) key);
            if (value == null && !containsKey((K) key)) {
                throw UnknownKeyException.create(key);
            }
            return value;
        } catch (ClassCastException e) {
            throw UnknownKeyException.create(key);
        }
    }

    @ExportMessage
    boolean isHashEntryModifiable(@SuppressWarnings("unused") Object key) {
        return false;
    }

    @ExportMessage
    boolean isHashEntryInsertable(@SuppressWarnings("unused") Object key) {
        return false;
    }

    @ExportMessage
    void writeHashEntry(@SuppressWarnings("unused") Object key, @SuppressWarnings("unused") Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getIterator() {
        return new SortedMapIterator(iterator());
    }

    @ExportMessage
    @TruffleBoundary
    Object getHashEntriesIterator() {
        return new SortedMapIterator(iterator());
    }

    @ExportLibrary(InteropLibrary.class)
    static final class SortedMapIterator implements TruffleObject {
        private final Iterator<?> iterator;

        SortedMapIterator(Iterator<?> iterator) {
            this.iterator = iterator;
        }

        @ExportMessage
        boolean isIterator() {
            return true;
        }

        @ExportMessage
        @TruffleBoundary
        boolean hasIteratorNextElement() {
            return iterator.hasNext();
        }

        @ExportMessage
        @TruffleBoundary
        Object getIteratorNextElement() throws UnsupportedMessageException {
            if (!iterator.hasNext()) {
                throw UnsupportedMessageException.create();
            }
            return iterator.next();
        }
    }
}
