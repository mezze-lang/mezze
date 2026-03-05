package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownKeyException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import io.vavr.collection.HashMap;
import io.vavr.control.Option;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * Mezze Map backed by Vavr HashMap (HAMT) for O(1) amortized persistent operations.
 *
 * Vavr HashMap provides:
 * - O(1) amortized get, put, remove (Hash Array Mapped Trie)
 * - Structural sharing for memory efficiency
 * - Immutable semantics with persistent updates
 *
 * All "mutating" operations return new MezzeMap instances with shared structure.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeMap implements TruffleObject {

    // Singleton empty map
    private static final MezzeMap EMPTY = new MezzeMap(HashMap.empty());

    // Vavr HashMap backing store
    private final HashMap<Object, Object> entries;

    private MezzeMap(HashMap<Object, Object> entries) {
        this.entries = entries;
    }

    // ===== FACTORY METHODS =====

    public static MezzeMap empty() {
        return EMPTY;
    }

    public static MezzeMap of(Object key, Object value) {
        return new MezzeMap(HashMap.of(key, value));
    }

    public static MezzeMap of(Object k1, Object v1, Object k2, Object v2) {
        return new MezzeMap(HashMap.of(k1, v1, k2, v2));
    }

    @TruffleBoundary
    public static MezzeMap fromJavaMap(Map<?, ?> map) {
        HashMap<Object, Object> result = HashMap.empty();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result = result.put(entry.getKey(), entry.getValue());
        }
        return new MezzeMap(result);
    }

    @TruffleBoundary
    public static MezzeMap fromEntries(Iterable<? extends Map.Entry<?, ?>> entries) {
        HashMap<Object, Object> result = HashMap.empty();
        for (Map.Entry<?, ?> entry : entries) {
            result = result.put(entry.getKey(), entry.getValue());
        }
        return new MezzeMap(result);
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

    public HashMap<Object, Object> getEntries() {
        return entries;
    }

    // ===== PERSISTENT UPDATE OPERATIONS =====

    @TruffleBoundary
    public MezzeMap put(Object key, Object value) {
        return new MezzeMap(entries.put(key, value));
    }

    @TruffleBoundary
    public MezzeMap remove(Object key) {
        return new MezzeMap(entries.remove(key));
    }

    @TruffleBoundary
    public MezzeMap merge(MezzeMap other) {
        return new MezzeMap(entries.merge(other.entries));
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
        return new MapEntryIterator(entries.iterator());
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeMap other)) return false;
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
        StringBuilder sb = new StringBuilder("{");
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
    static final class MapEntryIterator implements TruffleObject {
        private final Iterator<io.vavr.Tuple2<Object, Object>> iterator;

        MapEntryIterator(Iterator<io.vavr.Tuple2<Object, Object>> iterator) {
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
