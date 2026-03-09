package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownKeyException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Mezze Map backed by custom datastructures module for O(1) amortized persistent operations.
 *
 * The datastructures MezzeMap provides:
 * - O(1) amortized get, put, remove (tiered storage)
 * - Structural sharing for memory efficiency
 * - Immutable semantics with persistent updates
 * - Specialized storage for long keys
 *
 * All "mutating" operations return new MezzeMap instances with shared structure.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeMap implements TruffleObject {

    // Singleton empty map
    private static final MezzeMap EMPTY = new MezzeMap(com.mezze.datastructures.map.MezzeMap.empty());

    // Datastructures MezzeMap backing store
    private final com.mezze.datastructures.map.MezzeMap<Object, Object> delegate;

    private MezzeMap(com.mezze.datastructures.map.MezzeMap<Object, Object> delegate) {
        this.delegate = delegate;
    }

    // ===== FACTORY METHODS =====

    public static MezzeMap empty() {
        return EMPTY;
    }

    public static MezzeMap of(Object key, Object value) {
        return new MezzeMap(com.mezze.datastructures.map.MezzeMap.of(key, value));
    }

    public static MezzeMap of(Object k1, Object v1, Object k2, Object v2) {
        return new MezzeMap(com.mezze.datastructures.map.MezzeMap.of(k1, v1).put(k2, v2));
    }

    @TruffleBoundary
    public static MezzeMap fromJavaMap(Map<?, ?> map) {
        com.mezze.datastructures.map.MezzeMap<Object, Object> result = com.mezze.datastructures.map.MezzeMap.empty();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result = result.put(entry.getKey(), entry.getValue());
        }
        return new MezzeMap(result);
    }

    @TruffleBoundary
    public static MezzeMap fromEntries(Iterable<? extends Map.Entry<?, ?>> entries) {
        com.mezze.datastructures.map.MezzeMap<Object, Object> result = com.mezze.datastructures.map.MezzeMap.empty();
        for (Map.Entry<?, ?> entry : entries) {
            result = result.put(entry.getKey(), entry.getValue());
        }
        return new MezzeMap(result);
    }

    // ===== BASIC OPERATIONS =====

    public int size() {
        return delegate.size();
    }

    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @TruffleBoundary
    public Object get(Object key) {
        return delegate.get(key);
    }

    @TruffleBoundary
    public Object getOrNull(Object key) {
        return delegate.get(key);
    }

    @TruffleBoundary
    public boolean containsKey(Object key) {
        return delegate.containsKey(key);
    }

    /**
     * Get the underlying datastructures map for internal use.
     */
    public com.mezze.datastructures.map.MezzeMap<Object, Object> getDelegate() {
        return delegate;
    }

    // ===== PERSISTENT UPDATE OPERATIONS =====

    @TruffleBoundary
    public MezzeMap put(Object key, Object value) {
        return new MezzeMap(delegate.put(key, value));
    }

    @TruffleBoundary
    public MezzeMap remove(Object key) {
        return new MezzeMap(delegate.remove(key));
    }

    @TruffleBoundary
    public MezzeMap merge(MezzeMap other) {
        return new MezzeMap(delegate.putAll(other.delegate));
    }

    // ===== ITERATION =====

    @TruffleBoundary
    public Iterable<Object> keys() {
        Object[] keyArray = delegate.keys();
        return java.util.Arrays.asList(keyArray);
    }

    @TruffleBoundary
    public Iterable<Object> values() {
        Object[] valueArray = delegate.values();
        return java.util.Arrays.asList(valueArray);
    }

    @TruffleBoundary
    public java.util.Map<Object, Object> toJavaMap() {
        java.util.HashMap<Object, Object> result = new java.util.HashMap<>(delegate.size());
        for (com.mezze.datastructures.map.MezzeMap.Entry<Object, Object> entry : delegate) {
            result.put(entry.key(), entry.value());
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
        return delegate.size();
    }

    @ExportMessage
    @TruffleBoundary
    boolean isHashEntryReadable(Object key) {
        return delegate.containsKey(key);
    }

    @ExportMessage
    @TruffleBoundary
    Object readHashValue(Object key) throws UnknownKeyException {
        Object value = delegate.get(key);
        if (value != null || delegate.containsKey(key)) {
            return value;
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
        return new MapEntryIterator(delegate.iterator());
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeMap other)) return false;
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
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (com.mezze.datastructures.map.MezzeMap.Entry<Object, Object> entry : delegate) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(entry.key()).append(" = ").append(entry.value());
        }
        sb.append("}");
        return sb.toString();
    }

    // ===== ITERATOR HELPER =====

    @ExportLibrary(InteropLibrary.class)
    static final class MapEntryIterator implements TruffleObject {
        private final Iterator<com.mezze.datastructures.map.MezzeMap.Entry<Object, Object>> iterator;

        MapEntryIterator(Iterator<com.mezze.datastructures.map.MezzeMap.Entry<Object, Object>> iterator) {
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
            com.mezze.datastructures.map.MezzeMap.Entry<Object, Object> entry = iterator.next();
            return new Object[]{entry.key(), entry.value()};
        }
    }
}
