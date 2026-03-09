package com.mezze.interpreter.v2.values;

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
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Mezze SortedMap backed by sorted arrays for O(log n) operations.
 *
 * This implementation:
 * - O(log n) get, containsKey (binary search)
 * - O(n) put, remove (due to array copy)
 * - O(1) min, max
 * - Keys maintained in sorted order
 * - Immutable semantics with persistent updates
 *
 * All "mutating" operations return new MezzeSortedMap instances.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeSortedMap implements TruffleObject {

    // Default comparator that handles all Mezze value types
    private static final Comparator<Object> DEFAULT_COMPARATOR = MezzeSortedMap::compareValues;

    // Singleton empty sorted map
    private static final MezzeSortedMap EMPTY = new MezzeSortedMap(new Object[0], new Object[0]);

    // Sorted arrays for keys and values
    private final Object[] keys;
    private final Object[] values;

    private MezzeSortedMap(Object[] keys, Object[] values) {
        this.keys = keys;
        this.values = values;
    }

    // ===== VALUE COMPARISON =====

    @TruffleBoundary
    private static int compareValues(Object a, Object b) {
        // Handle nulls
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;

        // Same type comparisons
        if (a instanceof Long && b instanceof Long) {
            return Long.compare((Long) a, (Long) b);
        }
        if (a instanceof Double && b instanceof Double) {
            return Double.compare((Double) a, (Double) b);
        }
        if (a instanceof String && b instanceof String) {
            return ((String) a).compareTo((String) b);
        }
        if (a instanceof Boolean && b instanceof Boolean) {
            return Boolean.compare((Boolean) a, (Boolean) b);
        }

        // Cross-type: compare by type name first, then by string representation
        int typeCompare = a.getClass().getName().compareTo(b.getClass().getName());
        if (typeCompare != 0) return typeCompare;

        // Same type but not comparable - use string representation
        return a.toString().compareTo(b.toString());
    }

    /**
     * Binary search for key.
     * Returns index if found, or -(insertionPoint + 1) if not found.
     */
    @TruffleBoundary
    private int binarySearch(Object key) {
        int low = 0;
        int high = keys.length - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            int cmp = compareValues(keys[mid], key);

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

    // ===== FACTORY METHODS =====

    public static MezzeSortedMap empty() {
        return EMPTY;
    }

    @TruffleBoundary
    public static MezzeSortedMap of(Object key, Object value) {
        return new MezzeSortedMap(new Object[]{key}, new Object[]{value});
    }

    @TruffleBoundary
    public static MezzeSortedMap of(Object k1, Object v1, Object k2, Object v2) {
        return empty().put(k1, v1).put(k2, v2);
    }

    @TruffleBoundary
    public static MezzeSortedMap fromJavaMap(Map<?, ?> map) {
        MezzeSortedMap result = EMPTY;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result = result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSortedMap fromEntries(Iterable<? extends Map.Entry<?, ?>> entries) {
        MezzeSortedMap result = EMPTY;
        for (Map.Entry<?, ?> entry : entries) {
            result = result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    // ===== BASIC OPERATIONS =====

    public int size() {
        return keys.length;
    }

    public boolean isEmpty() {
        return keys.length == 0;
    }

    @TruffleBoundary
    public Object get(Object key) {
        int idx = binarySearch(key);
        return idx >= 0 ? values[idx] : null;
    }

    @TruffleBoundary
    public Object getOrNull(Object key) {
        return get(key);
    }

    @TruffleBoundary
    public boolean containsKey(Object key) {
        return binarySearch(key) >= 0;
    }

    // ===== PERSISTENT UPDATE OPERATIONS =====

    @TruffleBoundary
    public MezzeSortedMap put(Object key, Object value) {
        int idx = binarySearch(key);

        if (idx >= 0) {
            // Update existing
            Object[] newValues = values.clone();
            newValues[idx] = value;
            return new MezzeSortedMap(keys, newValues);
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

        return new MezzeSortedMap(newKeys, newValues);
    }

    @TruffleBoundary
    public MezzeSortedMap remove(Object key) {
        int idx = binarySearch(key);
        if (idx < 0) return this;

        if (keys.length == 1) return EMPTY;

        Object[] newKeys = new Object[keys.length - 1];
        Object[] newValues = new Object[values.length - 1];

        System.arraycopy(keys, 0, newKeys, 0, idx);
        System.arraycopy(keys, idx + 1, newKeys, idx, keys.length - idx - 1);

        System.arraycopy(values, 0, newValues, 0, idx);
        System.arraycopy(values, idx + 1, newValues, idx, values.length - idx - 1);

        return new MezzeSortedMap(newKeys, newValues);
    }

    @TruffleBoundary
    public MezzeSortedMap merge(MezzeSortedMap other) {
        MezzeSortedMap result = this;
        for (int i = 0; i < other.keys.length; i++) {
            result = result.put(other.keys[i], other.values[i]);
        }
        return result;
    }

    // ===== SORTED MAP SPECIFIC OPERATIONS =====

    /**
     * Get the minimum key. O(1)
     */
    @TruffleBoundary
    public Object minKey() {
        return isEmpty() ? null : keys[0];
    }

    /**
     * Get the maximum key. O(1)
     */
    @TruffleBoundary
    public Object maxKey() {
        return isEmpty() ? null : keys[keys.length - 1];
    }

    /**
     * Get the entry with minimum key. O(1)
     */
    @TruffleBoundary
    public Object[] minEntry() {
        if (isEmpty()) return null;
        return new Object[]{keys[0], values[0]};
    }

    /**
     * Get the entry with maximum key. O(1)
     */
    @TruffleBoundary
    public Object[] maxEntry() {
        if (isEmpty()) return null;
        return new Object[]{keys[keys.length - 1], values[values.length - 1]};
    }

    /**
     * Get all entries with keys in the range [from, to).
     */
    @TruffleBoundary
    public MezzeSortedMap range(Object fromKey, Object toKey) {
        int fromIdx = binarySearch(fromKey);
        if (fromIdx < 0) fromIdx = -(fromIdx + 1);

        int toIdx = binarySearch(toKey);
        if (toIdx < 0) toIdx = -(toIdx + 1);

        if (fromIdx >= toIdx) return EMPTY;

        return new MezzeSortedMap(
            Arrays.copyOfRange(keys, fromIdx, toIdx),
            Arrays.copyOfRange(values, fromIdx, toIdx)
        );
    }

    // ===== ITERATION =====

    @TruffleBoundary
    public Iterable<Object> keys() {
        return () -> new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < keys.length;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                return keys[index++];
            }
        };
    }

    @TruffleBoundary
    public Iterable<Object> values() {
        return () -> new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < values.length;
            }

            @Override
            public Object next() {
                if (!hasNext()) throw new NoSuchElementException();
                return values[index++];
            }
        };
    }

    @TruffleBoundary
    public java.util.Map<Object, Object> toJavaMap() {
        java.util.LinkedHashMap<Object, Object> result = new java.util.LinkedHashMap<>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            result.put(keys[i], values[i]);
        }
        return result;
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasHashEntries() {
        return true;
    }

    @ExportMessage
    long getHashSize() {
        return keys.length;
    }

    @ExportMessage
    @TruffleBoundary
    boolean isHashEntryReadable(Object key) {
        return binarySearch(key) >= 0;
    }

    @ExportMessage
    @TruffleBoundary
    Object readHashValue(Object key) throws UnknownKeyException {
        int idx = binarySearch(key);
        if (idx >= 0) {
            return values[idx];
        }
        throw UnknownKeyException.create(key);
    }

    @ExportMessage
    boolean isHashEntryModifiable(Object key) {
        return false;
    }

    @ExportMessage
    boolean isHashEntryInsertable(Object key) {
        return false;
    }

    @ExportMessage
    void writeHashEntry(Object key, Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
    @TruffleBoundary
    Object getHashEntriesIterator() {
        return new SortedMapEntryIterator(keys, values);
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeSortedMap other)) return false;
        return Arrays.equals(keys, other.keys) && Arrays.equals(values, other.values);
    }

    @Override
    @TruffleBoundary
    public int hashCode() {
        return Arrays.hashCode(keys) ^ Arrays.hashCode(values);
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("SortedMap{");
        for (int i = 0; i < keys.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(keys[i]).append(" = ").append(values[i]);
        }
        sb.append("}");
        return sb.toString();
    }

    // ===== ITERATOR HELPER =====

    @ExportLibrary(InteropLibrary.class)
    static final class SortedMapEntryIterator implements TruffleObject {
        private final Object[] keys;
        private final Object[] values;
        private int index = 0;

        SortedMapEntryIterator(Object[] keys, Object[] values) {
            this.keys = keys;
            this.values = values;
        }

        @ExportMessage
        boolean isIterator() {
            return true;
        }

        @ExportMessage
        @TruffleBoundary
        boolean hasIteratorNextElement() {
            return index < keys.length;
        }

        @ExportMessage
        @TruffleBoundary
        Object getIteratorNextElement() throws UnsupportedMessageException {
            if (index >= keys.length) {
                throw UnsupportedMessageException.create();
            }
            Object[] entry = new Object[]{keys[index], values[index]};
            index++;
            return entry;
        }
    }
}
