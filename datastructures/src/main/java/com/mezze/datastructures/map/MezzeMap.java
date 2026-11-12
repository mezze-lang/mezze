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

    /** Retest: with the native dispatch gone, does the interface call matter? */
    private final int cachedSize;

    private MezzeMap(MapStorage storage) {
        this.storage = storage;
        this.cachedSize = storage.size();
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
     * BUILDS A MAP FROM ENTRIES ALREADY IN HAND, in one pass.
     *
     * <p>WHY. Every path that rebuilds a whole map — `filter`, `map_values`,
     * `filter_keys`, `filter_values` — used to write `result = result.put(k, v)`
     * per entry, and so did `TrieMapStorage.fromMap`, whose comment calls
     * itself a one-pass build while doing exactly those N persistent puts. A
     * persistent put allocates a node at every level of the path it touches,
     * which is what an update wants and what a rebuild discards on the next
     * entry. MEASURED: 1 000 puts cost 76.3 ns an entry where a
     * `java.util.HashMap` of the same keys costs 10.4.
     *
     * <p>THE STRATEGY LADDER IS PRESERVED, which is the whole reason this lives
     * on `MezzeMap` and not on a storage. A map's storage is chosen by size and
     * by key type — {@link SingleEntryMapStorage}, then
     * {@link SmallFlatMapStorage} to {@link SmallFlatMapStorage#MAX_SIZE},
     * then the LONG-KEYED trie when every key is a {@code Long} and the general
     * trie otherwise. A builder that always made a general trie would be faster
     * than one path and slower than another, so this picks the same tier the
     * ladder would and only uses a builder where the ladder ends up in a trie.
     * Below the flat storage's maximum it puts one at a time, because at eight
     * entries the flat storage is the faster answer anyway.
     *
     * <p>Duplicate keys are allowed: a later entry replaces an earlier one, so
     * the result has the same contents repeated puts would give. {@code count}
     * says how much of the arrays to read, so a caller that filtered may pass
     * arrays sized for the input.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public static <K, V> MezzeMap<K, V> fromEntries(Object[] keys, Object[] values, int count) {
        if (count <= 0) {
            return empty();
        }
        if (count <= SmallFlatMapStorage.MAX_SIZE) {
            MapStorage out = EmptyMapStorage.INSTANCE;
            for (int i = 0; i < count; i++) {
                out = out.put(keys[i], values[i]);
            }
            return new MezzeMap<>(out);
        }
        if (allLongKeys(keys, count)) {
            var b = new LongTrieMapStorage.Builder();
            for (int i = 0; i < count; i++) {
                b.put((Long) keys[i], values[i]);
            }
            return new MezzeMap<>(b.build());
        }
        var b = new TrieMapStorage.Builder();
        for (int i = 0; i < count; i++) {
            b.put(keys[i], values[i]);
        }
        return new MezzeMap<>(b.build());
    }

    /** The flat storage's own condition for promoting to the long-keyed trie. */
    private static boolean allLongKeys(Object[] keys, int count) {
        for (int i = 0; i < count; i++) {
            if (!(keys[i] instanceof Long)) return false;
        }
        return true;
    }

    /**
     * Creates from an existing storage.
     */
    /** The storage strategy backing this map. Callers use it to ask whether the map
     *  already buckets by a key instance (see EvidenceMapStorage) before rebuilding
     *  it under one. */
    public MapStorage storage() {
        return storage;
    }

    public static <K, V> MezzeMap<K, V> fromStorage(MapStorage storage) {
        return new MezzeMap<>(storage);
    }

    // ===== ACCESS =====

    /**
     * Returns the number of entries.
     */
    public int size() {
        return cachedSize;
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
     *
     * <p>A CONCRETE-RECEIVER PROBE WAS TRIED HERE AND NOT DEMONSTRATED, which
     * is worth recording because the identical move is 1.33x on
     * {@code MezzeSet.containsDirect} and was 1.21x on `seq::get` and 4.19x on
     * `list::get`. A CPU profile of the one-entry probe put 118 of ~130
     * steady-state samples in `MapNatives.containsKey` — a `@TruffleBoundary` —
     * and `InlineMapContainsKey.doMap`, so the boundary looked like the whole
     * cost. Removing it with the storage receiver named:
     *
     * <pre>
     *   row            n=2e7 (8 reps)   n=1e7 (14 reps)
     *     map has          1.29x             1.13x
     *     map get          1.08x             1.01x
     *     set has          1.34x             1.33x   <- shipped
     *     controls      0.92-1.01x        0.99-1.09x
     * </pre>
     *
     * `map has` does not converge and 1.13x sits on the control ceiling;
     * `map::get` is null. So the map half is NOT here, while the set half is —
     * an unproven type test is exactly what cost 10% on the one-element
     * storages.
     *
     * <p>AND THE WIDE VERSION OF IT IS A DISASTER. Naming SIX storage
     * implementations rather than one measured **0.02x** — 40-48x WORSE on all
     * three rows at once, checksums still correct. Each branch is a whole
     * hash-map probe, one an `@ExplodeLoop` 8-way scan and another a general
     * HAMT walk, so partial evaluation inlined six map implementations into the
     * caller. The chain pays when each branch is a CONSTANT number of field
     * reads — one array load on seq, one field read on list — and not
     * otherwise.
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
     *
     * <p>Both long-keyed storages are covered. The trie was added to this
     * check when it replaced the open-addressed table: without it the method
     * boxed on every Long-keyed map above SmallFlat, which is the size at
     * which it matters and the opposite of what the name says.
     */
    @SuppressWarnings("unchecked")
    public V getLong(long key) {
        if (storage instanceof LongKeyMapStorage longStorage) {
            return (V) longStorage.getLong(key);
        }
        if (storage instanceof LongTrieMapStorage trie) {
            return (V) trie.getLong(key);
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
     *
     * <p>Both sides' entries go to {@link #fromEntries}, which builds the trie
     * in place. This used to fill a {@code java.util.HashMap} and then put its
     * entries into a trie ONE AT A TIME — the detour cost an extra hash table
     * and still paid a persistent put per entry, and it also forced a general
     * trie, so merging two long-keyed maps silently gave up the unboxed
     * storage every later lookup would have used.
     */
    @SuppressWarnings("unchecked")
    public MezzeMap<K, V> putAll(MezzeMap<K, V> other) {
        if (other.isEmpty()) return this;
        if (isEmpty()) return other;

        // THE FOUR ARRAYS STAY HERE, and that is a measurement, not an
        // oversight. Everywhere else in this family, replacing `keys()` and
        // `values()` with a `storage.forEach` walk paid — 1.33x on
        // `map::count`, 1.23x and 1.24x on the set's intersection and
        // difference. Here it measured 1.01x on `map::merge` and 0.99x on
        // `set::union`, because this shape COPIES both sides into the output
        // wholesale: `System.arraycopy` moves them in bulk, and a per-element
        // consumer walk gives that up to save the allocation. The two cancel.
        Object[] mine = keys();
        Object[] mineV = values();
        Object[] theirs = other.keys();
        Object[] theirsV = other.values();

        Object[] outK = new Object[mine.length + theirs.length];
        Object[] outV = new Object[mine.length + theirs.length];
        System.arraycopy(mine, 0, outK, 0, mine.length);
        System.arraycopy(mineV, 0, outV, 0, mine.length);
        // `other` goes in second, so a shared key keeps ITS value.
        System.arraycopy(theirs, 0, outK, mine.length, theirs.length);
        System.arraycopy(theirsV, 0, outV, mine.length, theirs.length);

        return (MezzeMap<K, V>) fromEntries(outK, outV, outK.length);
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
    @SuppressWarnings("unchecked")
    public <K2, V2> MezzeMap<K2, V2> map(BiFunction<K, V, Entry<K2, V2>> fn) {
        if (isEmpty()) return empty();

        int size = size();
        Object[] outK = new Object[size];
        Object[] outV = new Object[size];
        int[] n = {0};
        storage.forEach((k, v) -> {
            Entry<K2, V2> mapped = fn.apply((K) k, (V) v);
            outK[n[0]] = mapped.key;
            outV[n[0]] = mapped.value;
            n[0]++;
        });
        // Two entries may map to one key: the later wins, as the puts did.
        return (MezzeMap<K2, V2>) fromEntries(outK, outV, size);
    }

    /**
     * Maps over keys.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public <K2> MezzeMap<K2, V> mapKeys(Function<K, K2> fn) {
        if (isEmpty()) return empty();

        int size = size();
        Object[] outK = new Object[size];
        Object[] outV = new Object[size];
        int[] n = {0};
        storage.forEach((k, v) -> {
            outK[n[0]] = fn.apply((K) k);
            outV[n[0]] = v;
            n[0]++;
        });
        // Two keys may map to one: the later wins, as the puts did.
        return (MezzeMap<K2, V>) fromEntries(outK, outV, size);
    }

    /**
     * Maps over values.
     *
     * <p>The keys are unchanged, so only the values are rebuilt — and now that
     * is what happens. This said exactly the same thing while calling
     * {@link #fromEntries}, which pushes every key back through a builder and
     * hashes it again; the claim was true of the intent and false of the code.
     * {@link MapStorage#withValues} shares the key storage by reference and
     * copies only what holds values, so no key is hashed and none is compared.
     *
     * <p>29 302 ps/element for the rebuild against 10 925 for the structure
     * copy, measured on 1000 string keys — and that ratio is a lower bound,
     * because the copy was measured through reflection.
     *
     * <p>(The {@code LinkedHashMap} this once filled "to preserve insertion
     * order" preserved nothing — a trie has no insertion order — and then put
     * its entries in one at a time anyway.)
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public <V2> MezzeMap<K, V2> mapValues(Function<V, V2> fn) {
        if (isEmpty()) return empty();

        Object[] vs = storage.values();
        Object[] outV = new Object[vs.length];
        for (int i = 0; i < vs.length; i++) outV[i] = fn.apply((V) vs[i]);
        return (MezzeMap<K, V2>) new MezzeMap<>(storage.withValues(outV));
    }

    /**
     * Filters entries by predicate.
     *
     * <p>Keeping everything still returns {@code this}: an immutable map that
     * would be rebuilt entry for entry is already the answer.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public MezzeMap<K, V> filter(BiPredicate<K, V> p) {
        if (isEmpty()) return this;

        int size = size();
        Object[] outK = new Object[size];
        Object[] outV = new Object[size];
        int[] n = {0};
        storage.forEach((k, v) -> {
            if (p.test((K) k, (V) v)) {
                outK[n[0]] = k;
                outV[n[0]] = v;
                n[0]++;
            }
        });
        if (n[0] == size) return this;

        return (MezzeMap<K, V>) fromEntries(outK, outV, n[0]);
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
     * THIS MAP WITH NEW VALUES, sharing every key.
     *
     * <p>For a caller that has already mapped {@link #values()} and so does not
     * want {@link #mapValues}'s callback — the natives, which run the guest
     * callback in their own frame rather than behind this class's
     * {@code @TruffleBoundary}.
     *
     * <p>{@code newValues} is in {@link #values()} order and must have
     * {@link #size()} elements. See {@link MapStorage#withValues}.
     */
    @SuppressWarnings("unchecked")
    public <V2> MezzeMap<K, V2> withValues(Object[] newValues) {
        if (isEmpty()) return empty();
        return (MezzeMap<K, V2>) new MezzeMap<>(storage.withValues(newValues));
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
    /**
     * ONE WALK, NO ARRAYS, for the whole-map operations.
     *
     * <p>MEASURED on a 1 000-entry long-keyed map: taking {@code keys()} and
     * {@code values()} and indexing them in step costs 7.16 ns an entry, the
     * {@code Entry} iterator it replaced 7.25, and this 1.97 — 3.6x, because
     * neither of the others is the walk, they are the two n-element arrays and
     * the trie walked twice to fill them. 2.56x for String keys, 3.54x at
     * n=4 000.
     */
    @SuppressWarnings("unchecked")
    public void walkEntries(java.util.function.BiConsumer<Object, Object> fn) {
        storage.forEach(fn);
    }

    /** {@link #walkEntries} that stops, for `any`, `all`, `none` and `find`. */
    public boolean walkEntriesUntil(java.util.function.BiPredicate<Object, Object> p) {
        return storage.forEachUntil(p);
    }

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
        Object[] outK = new Object[map.size()];
        Object[] outV = new Object[map.size()];
        int n = 0;
        for (java.util.Map.Entry<K, V> entry : map.entrySet()) {
            outK[n] = entry.getKey();
            outV[n] = entry.getValue();
            n++;
        }
        return (MezzeMap<K, V>) fromEntries(outK, outV, n);
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
