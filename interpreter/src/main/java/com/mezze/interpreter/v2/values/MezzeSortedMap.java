package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownKeyException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import io.vavr.collection.TreeMap;
import io.vavr.control.Option;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Mezze SortedMap backed by Vavr TreeMap (Red-Black tree) for O(log n) operations.
 *
 * Vavr TreeMap provides:
 * - O(log n) get, put, remove
 * - O(log n) min, max
 * - Keys maintained in sorted order
 * - Structural sharing for memory efficiency
 * - Immutable semantics with persistent updates
 *
 * All "mutating" operations return new MezzeSortedMap instances with shared structure.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeSortedMap implements TruffleObject {

    // Default comparator that handles all Mezze value types
    private static final Comparator<Object> DEFAULT_COMPARATOR = MezzeSortedMap::compareValues;

    // Singleton empty sorted map
    private static final MezzeSortedMap EMPTY = new MezzeSortedMap(TreeMap.empty(DEFAULT_COMPARATOR));

    // Vavr TreeMap backing store
    private final TreeMap<Object, Object> entries;

    private MezzeSortedMap(TreeMap<Object, Object> entries) {
        this.entries = entries;
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

    // ===== FACTORY METHODS =====

    public static MezzeSortedMap empty() {
        return EMPTY;
    }

    public static MezzeSortedMap of(Object key, Object value) {
        return new MezzeSortedMap(TreeMap.of(DEFAULT_COMPARATOR, key, value));
    }

    public static MezzeSortedMap of(Object k1, Object v1, Object k2, Object v2) {
        return new MezzeSortedMap(
            TreeMap.of(DEFAULT_COMPARATOR, k1, v1).put(k2, v2)
        );
    }

    @TruffleBoundary
    public static MezzeSortedMap fromJavaMap(Map<?, ?> map) {
        TreeMap<Object, Object> result = TreeMap.empty(DEFAULT_COMPARATOR);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result = result.put(entry.getKey(), entry.getValue());
        }
        return new MezzeSortedMap(result);
    }

    @TruffleBoundary
    public static MezzeSortedMap fromEntries(Iterable<? extends Map.Entry<?, ?>> entries) {
        TreeMap<Object, Object> result = TreeMap.empty(DEFAULT_COMPARATOR);
        for (Map.Entry<?, ?> entry : entries) {
            result = result.put(entry.getKey(), entry.getValue());
        }
        return new MezzeSortedMap(result);
    }

    // ===== BASIC OPERATIONS =====

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @TruffleBoundary
    public Object get(Object key) {
        return entries.get(key).getOrNull();
    }

    @TruffleBoundary
    public Option<Object> getOption(Object key) {
        return entries.get(key);
    }

    @TruffleBoundary
    public boolean containsKey(Object key) {
        return entries.containsKey(key);
    }

    public TreeMap<Object, Object> getEntries() {
        return entries;
    }

    // ===== PERSISTENT UPDATE OPERATIONS =====

    @TruffleBoundary
    public MezzeSortedMap put(Object key, Object value) {
        return new MezzeSortedMap(entries.put(key, value));
    }

    @TruffleBoundary
    public MezzeSortedMap remove(Object key) {
        return new MezzeSortedMap(entries.remove(key));
    }

    @TruffleBoundary
    public MezzeSortedMap merge(MezzeSortedMap other) {
        return new MezzeSortedMap(entries.merge(other.entries));
    }

    // ===== SORTED MAP SPECIFIC OPERATIONS =====

    /**
     * Get the minimum key. O(log n)
     */
    @TruffleBoundary
    public Object minKey() {
        return entries.isEmpty() ? null : entries.head()._1;
    }

    /**
     * Get the maximum key. O(log n)
     */
    @TruffleBoundary
    public Object maxKey() {
        return entries.isEmpty() ? null : entries.last()._1;
    }

    /**
     * Get the entry with minimum key. O(log n)
     */
    @TruffleBoundary
    public Object[] minEntry() {
        if (entries.isEmpty()) return null;
        var entry = entries.head();
        return new Object[]{entry._1, entry._2};
    }

    /**
     * Get the entry with maximum key. O(log n)
     */
    @TruffleBoundary
    public Object[] maxEntry() {
        if (entries.isEmpty()) return null;
        var entry = entries.last();
        return new Object[]{entry._1, entry._2};
    }

    /**
     * Get all entries with keys in the range [from, to). O(log n + m)
     */
    @TruffleBoundary
    public MezzeSortedMap range(Object fromKey, Object toKey) {
        TreeMap<Object, Object> ranged = entries.filterKeys(k ->
            compareValues(k, fromKey) >= 0 && compareValues(k, toKey) < 0
        );
        return ranged.isEmpty() ? EMPTY : new MezzeSortedMap(ranged);
    }

    // ===== ITERATION =====

    @TruffleBoundary
    public Iterable<Object> keys() {
        return entries.keySet();
    }

    @TruffleBoundary
    public Iterable<Object> values() {
        return entries.values();
    }

    @TruffleBoundary
    public java.util.Map<Object, Object> toJavaMap() {
        return entries.toJavaMap();
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasHashEntries() {
        return true;
    }

    @ExportMessage
    long getHashSize() {
        return entries.size();
    }

    @ExportMessage
    @TruffleBoundary
    boolean isHashEntryReadable(Object key) {
        return entries.containsKey(key);
    }

    @ExportMessage
    @TruffleBoundary
    Object readHashValue(Object key) throws UnknownKeyException {
        Option<Object> value = entries.get(key);
        if (value.isDefined()) {
            return value.get();
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
        return new SortedMapEntryIterator(entries.iterator());
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeSortedMap other)) return false;
        return Objects.equals(entries, other.entries);
    }

    @Override
    @TruffleBoundary
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("SortedMap{");
        boolean first = true;
        for (var entry : entries) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(entry._1).append(" = ").append(entry._2);
        }
        sb.append("}");
        return sb.toString();
    }

    // ===== ITERATOR HELPER =====

    @ExportLibrary(InteropLibrary.class)
    static final class SortedMapEntryIterator implements TruffleObject {
        private final Iterator<io.vavr.Tuple2<Object, Object>> iterator;

        SortedMapEntryIterator(Iterator<io.vavr.Tuple2<Object, Object>> iterator) {
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
            io.vavr.Tuple2<Object, Object> entry = iterator.next();
            return new Object[]{entry._1, entry._2};
        }
    }
}
