package com.mezze.datastructures.map;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownKeyException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.Optional;
import java.util.function.Function;

/**
 * Immutable map with automatic storage strategy selection.
 *
 * @param <K> Key type
 * @param <V> Value type
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeMap<K, V> implements TruffleObject, Iterable<MezzeMap.Entry<K, V>> {

    @CompilationFinal
    private final MapStorage storage;

    private MezzeMap(MapStorage storage) {
        this.storage = storage;
    }

    /**
     * Key-value entry.
     */
    public record Entry<K, V>(K key, V value) {}

    // ===== CONSTRUCTION =====

    /**
     * Returns an empty map.
     */
    public static <K, V> MezzeMap<K, V> empty() {
        return new MezzeMap<>(EmptyMapStorage.INSTANCE);
    }

    /**
     * Creates a single-entry map.
     */
    public static <K, V> MezzeMap<K, V> of(K key, V value) {
        return new MezzeMap<>(new SingleEntryMapStorage(key, value));
    }

    /**
     * Creates a map from key-value pairs.
     */
    @SafeVarargs
    public static <K, V> MezzeMap<K, V> of(Entry<K, V>... entries) {
        if (entries.length == 0) {
            return empty();
        }
        if (entries.length == 1) {
            return of(entries[0].key, entries[0].value);
        }

        MezzeMap<K, V> result = empty();
        for (Entry<K, V> entry : entries) {
            result = result.put(entry.key, entry.value);
        }
        return result;
    }

    /**
     * Creates a map with long keys (unboxed).
     */
    public static <V> MezzeMap<Long, V> ofLongKeys(long[] keys, V[] values) {
        if (keys.length != values.length) {
            throw new IllegalArgumentException("Keys and values must have same length");
        }
        if (keys.length == 0) {
            return empty();
        }
        return new MezzeMap<>(new LongKeyMapStorage(keys.clone(), values.clone()));
    }

    /**
     * Creates from an existing storage.
     */
    public static <K, V> MezzeMap<K, V> fromStorage(MapStorage storage) {
        return new MezzeMap<>(storage);
    }

    // ===== ACCESS =====

    /**
     * Returns the number of entries.
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
     * Gets the value for a key, or null if not present.
     */
    @SuppressWarnings("unchecked")
    public V get(K key) {
        return (V) storage.get(key);
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
        return storage.containsKey(key);
    }

    /**
     * Unboxed get for long keys.
     */
    @SuppressWarnings("unchecked")
    public V getLong(long key) {
        if (storage instanceof LongKeyMapStorage longStorage) {
            return (V) longStorage.getLong(key);
        }
        return get((K) Long.valueOf(key));
    }

    // ===== UPDATE =====

    /**
     * Returns a new map with the key-value pair added/updated.
     */
    public MezzeMap<K, V> put(K key, V value) {
        return new MezzeMap<>(storage.put(key, value));
    }

    /**
     * Returns a new map with the key-value pair added/updated (raw type).
     * For interpreter use with dynamic types.
     */
    @SuppressWarnings("unchecked")
    public MezzeMap<K, V> putObject(Object key, Object value) {
        return new MezzeMap<>(storage.put(key, value));
    }

    /**
     * Returns a new map with all entries from other map added.
     * Optimized: uses mutable builder for batch operations.
     */
    public MezzeMap<K, V> putAll(MezzeMap<K, V> other) {
        if (other.isEmpty()) return this;
        if (isEmpty()) return other;

        // Use mutable HashMap for bulk insert, then convert back
        java.util.HashMap<K, V> mutable = new java.util.HashMap<>(size() + other.size());

        // Add all from this
        for (Entry<K, V> entry : this) {
            mutable.put(entry.key, entry.value);
        }
        // Add all from other (overwrites duplicates)
        for (Entry<K, V> entry : other) {
            mutable.put(entry.key, entry.value);
        }

        // Convert back to immutable
        return fromMutableMap(mutable);
    }

    /**
     * Creates a MezzeMap from a mutable HashMap (internal use).
     * Optimized: builds HashMapStorage directly instead of adding one by one.
     */
    @SuppressWarnings("unchecked")
    private static <K, V> MezzeMap<K, V> fromMutableMap(java.util.HashMap<K, V> mutable) {
        if (mutable.isEmpty()) return empty();
        if (mutable.size() == 1) {
            var entry = mutable.entrySet().iterator().next();
            return of(entry.getKey(), entry.getValue());
        }

        // Build HashMapStorage directly from map (O(n) vs O(n^2))
        return new MezzeMap<>(HashMapStorage.fromMap(mutable));
    }

    /**
     * Returns a new map with the value at key transformed.
     */
    @TruffleBoundary
    public MezzeMap<K, V> update(K key, Function<V, V> fn) {
        V oldValue = get(key);
        if (oldValue == null) {
            return this;
        }
        return put(key, fn.apply(oldValue));
    }

    /**
     * Returns a new map with the key removed.
     */
    public MezzeMap<K, V> remove(K key) {
        return new MezzeMap<>(storage.remove(key));
    }

    // ===== TRANSFORM =====

    /**
     * Maps over entries.
     */
    @TruffleBoundary
    public <K2, V2> MezzeMap<K2, V2> map(BiFunction<K, V, Entry<K2, V2>> fn) {
        if (isEmpty()) return empty();

        MezzeMap<K2, V2> result = empty();
        for (Entry<K, V> entry : this) {
            Entry<K2, V2> mapped = fn.apply(entry.key, entry.value);
            result = result.put(mapped.key, mapped.value);
        }
        return result;
    }

    /**
     * Maps over keys.
     */
    @TruffleBoundary
    public <K2> MezzeMap<K2, V> mapKeys(Function<K, K2> fn) {
        if (isEmpty()) return empty();

        MezzeMap<K2, V> result = empty();
        for (Entry<K, V> entry : this) {
            result = result.put(fn.apply(entry.key), entry.value);
        }
        return result;
    }

    /**
     * Maps over values.
     * Optimized: uses mutable builder for batch operations.
     */
    @TruffleBoundary
    public <V2> MezzeMap<K, V2> mapValues(Function<V, V2> fn) {
        if (isEmpty()) return empty();

        // Use mutable LinkedHashMap to preserve insertion order
        java.util.LinkedHashMap<K, V2> mutable = new java.util.LinkedHashMap<>(size());
        for (Entry<K, V> entry : this) {
            mutable.put(entry.key, fn.apply(entry.value));
        }

        return fromMutableLinkedMap(mutable);
    }

    /**
     * Creates a MezzeMap from a mutable LinkedHashMap (internal use).
     * Optimized: builds HashMapStorage directly instead of adding one by one.
     */
    @SuppressWarnings("unchecked")
    private static <K, V> MezzeMap<K, V> fromMutableLinkedMap(java.util.LinkedHashMap<K, V> mutable) {
        if (mutable.isEmpty()) return empty();
        if (mutable.size() == 1) {
            var entry = mutable.entrySet().iterator().next();
            return of(entry.getKey(), entry.getValue());
        }

        // Build HashMapStorage directly from map (O(n) vs O(n^2))
        return new MezzeMap<>(HashMapStorage.fromMap(mutable));
    }

    /**
     * Filters entries by predicate.
     * Optimized: uses mutable builder for batch operations.
     */
    @TruffleBoundary
    public MezzeMap<K, V> filter(BiPredicate<K, V> p) {
        if (isEmpty()) return this;

        // Use mutable LinkedHashMap to preserve order
        java.util.LinkedHashMap<K, V> mutable = new java.util.LinkedHashMap<>();
        boolean allMatch = true;

        for (Entry<K, V> entry : this) {
            if (p.test(entry.key, entry.value)) {
                mutable.put(entry.key, entry.value);
            } else {
                allMatch = false;
            }
        }

        // If all entries matched, return this (no change)
        if (allMatch) return this;

        return fromMutableLinkedMap(mutable);
    }

    /**
     * Filters by key predicate.
     */
    @TruffleBoundary
    public MezzeMap<K, V> filterKeys(java.util.function.Predicate<K> p) {
        return filter((k, v) -> p.test(k));
    }

    /**
     * Filters by value predicate.
     */
    @TruffleBoundary
    public MezzeMap<K, V> filterValues(java.util.function.Predicate<V> p) {
        return filter((k, v) -> p.test(v));
    }

    // ===== FOLD =====

    /**
     * Folds over entries.
     */
    @TruffleBoundary
    public <B> B fold(B init, TriFunction<B, K, V, B> fn) {
        B acc = init;
        for (Entry<K, V> entry : this) {
            acc = fn.apply(acc, entry.key, entry.value);
        }
        return acc;
    }

    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }

    // ===== VIEWS =====

    /**
     * Returns all keys.
     */
    @SuppressWarnings("unchecked")
    public K[] keys() {
        return (K[]) storage.keys();
    }

    /**
     * Returns all values.
     */
    @SuppressWarnings("unchecked")
    public V[] values() {
        return (V[]) storage.values();
    }

    /**
     * Returns all entries as array.
     */
    @SuppressWarnings("unchecked")
    public Entry<K, V>[] entries() {
        Object[] keys = storage.keys();
        Object[] values = storage.values();
        Entry<K, V>[] result = new Entry[keys.length];
        for (int i = 0; i < keys.length; i++) {
            result[i] = new Entry<>((K) keys[i], (V) values[i]);
        }
        return result;
    }

    // ===== MERGE =====

    /**
     * Merges with another map, using resolver for conflicts.
     */
    @TruffleBoundary
    public MezzeMap<K, V> merge(MezzeMap<K, V> other, BiFunction<V, V, V> resolver) {
        if (other.isEmpty()) return this;
        if (isEmpty()) return other;

        MezzeMap<K, V> result = this;
        for (Entry<K, V> entry : other) {
            V existing = result.get(entry.key);
            if (existing != null) {
                result = result.put(entry.key, resolver.apply(existing, entry.value));
            } else {
                result = result.put(entry.key, entry.value);
            }
        }
        return result;
    }

    // ===== ITERATION =====

    @Override
    public Iterator<Entry<K, V>> iterator() {
        Object[] keys = storage.keys();
        Object[] values = storage.values();

        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < keys.length;
            }

            @Override
            @SuppressWarnings("unchecked")
            public Entry<K, V> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                Entry<K, V> entry = new Entry<>((K) keys[index], (V) values[index]);
                index++;
                return entry;
            }
        };
    }

    /**
     * Applies function to each entry for side effects.
     */
    @TruffleBoundary
    public void foreach(java.util.function.BiConsumer<K, V> fn) {
        for (Entry<K, V> entry : this) {
            fn.accept(entry.key, entry.value);
        }
    }

    // ===== CONVERSION =====

    /**
     * Creates a MezzeMap from a Java Map.
     */
    @SuppressWarnings("unchecked")
    public static <K, V> MezzeMap<K, V> fromJavaMap(java.util.Map<K, V> map) {
        MezzeMap<K, V> result = empty();
        for (java.util.Map.Entry<K, V> entry : map.entrySet()) {
            result = result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /**
     * Converts to a Java HashMap.
     */
    public java.util.Map<K, V> toJavaMap() {
        java.util.HashMap<K, V> result = new java.util.HashMap<>(size());
        for (Entry<K, V> entry : this) {
            result.put(entry.key, entry.value);
        }
        return result;
    }

    // ===== MUTABLE OPERATIONS (for LocalMut effect) =====

    /**
     * Creates a mutable copy of this map.
     * Used by to_mut to get a fresh copy that can be mutated.
     */
    public MezzeMap<K, V> copyToMutable() {
        if (storage instanceof HashMapStorage hashStorage) {
            return new MezzeMap<>(hashStorage.copyToMutable());
        }
        // For other storage types, convert to HashMap first
        return new MezzeMap<>(HashMapStorage.fromMap(toJavaMap()).copyToMutable());
    }

    /**
     * Creates a mutable map with given initial capacity.
     * Use this when you know the approximate final size.
     */
    public static <K, V> MezzeMap<K, V> mutableWithCapacity(int capacity) {
        // Pre-create a HashMapStorage with enough room
        java.util.HashMap<Object, Object> temp = new java.util.HashMap<>(capacity);
        return new MezzeMap<>(HashMapStorage.fromMap(temp).copyToMutable());
    }

    /**
     * In-place put - mutates the underlying storage.
     * Only safe within LocalMut context.
     * Returns true if key was new, false if updated.
     */
    public boolean mutPut(K key, V value) {
        if (storage instanceof HashMapStorage hashStorage) {
            return hashStorage.mutPut(key, value);
        }
        throw new UnsupportedOperationException("mutPut requires HashMapStorage - use copyToMutable() first");
    }

    /**
     * In-place remove - mutates the underlying storage.
     * Only safe within LocalMut context.
     * Returns true if key was found and removed.
     */
    public boolean mutRemove(K key) {
        if (storage instanceof HashMapStorage hashStorage) {
            return hashStorage.mutRemove(key);
        }
        throw new UnsupportedOperationException("mutRemove requires HashMapStorage - use copyToMutable() first");
    }

    /**
     * In-place update - mutates the underlying storage.
     * Only safe within LocalMut context.
     * Returns true if key was found and updated.
     */
    public boolean mutUpdate(K key, V value) {
        if (storage instanceof HashMapStorage hashStorage) {
            return hashStorage.mutUpdate(key, value);
        }
        throw new UnsupportedOperationException("mutUpdate requires HashMapStorage - use copyToMutable() first");
    }

    /**
     * In-place clear - mutates the underlying storage.
     * Only safe within LocalMut context.
     */
    public void mutClear() {
        if (storage instanceof HashMapStorage hashStorage) {
            hashStorage.mutClear();
        } else {
            throw new UnsupportedOperationException("mutClear requires HashMapStorage - use copyToMutable() first");
        }
    }

    // ===== INTERNAL =====

    /**
     * Returns the storage (for internal use).
     */
    public MapStorage getStorage() {
        return storage;
    }

    /**
     * Returns the storage strategy tag.
     */
    public byte getStrategyTag() {
        return storage.getStrategyTag();
    }

    /**
     * Returns true if using long key storage.
     */
    public boolean isLongKeyStorage() {
        return storage instanceof LongKeyMapStorage;
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasHashEntries() {
        return true;
    }

    @ExportMessage
    long getHashSize() {
        return storage.size();
    }

    @ExportMessage
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isHashEntryReadable(Object key) {
        return storage.containsKey(key);
    }

    @ExportMessage
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object readHashValue(Object key) throws UnknownKeyException {
        Object value = storage.get(key);
        if (value == null && !storage.containsKey(key)) {
            throw UnknownKeyException.create(key);
        }
        return value;
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
    Object getIterator() {
        return new MapEntryIterator(iterator());
    }

    @ExportMessage
    Object getHashEntriesIterator() {
        return new MapEntryIterator(iterator());
    }

    @ExportLibrary(InteropLibrary.class)
    static final class MapEntryIterator implements TruffleObject {
        private final Iterator<?> iterator;

        MapEntryIterator(Iterator<?> iterator) {
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
        for (Entry<K, V> entry : this) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(entry.key).append(": ").append(entry.value);
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeMap<?, ?> other)) return false;
        if (size() != other.size()) return false;

        for (Entry<K, V> entry : this) {
            Object otherValue = other.storage.get(entry.key);
            if (!Objects.equals(entry.value, otherValue)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = 0;
        for (Entry<K, V> entry : this) {
            result += Objects.hashCode(entry.key) ^ Objects.hashCode(entry.value);
        }
        return result;
    }
}
