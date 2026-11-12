package com.mezze.datastructures.map;

/**
 * Base interface for all Map storage strategies.
 */
public sealed interface MapStorage permits
        EmptyMapStorage,
        SingleEntryMapStorage,
        SmallFlatMapStorage,
        LongKeyMapStorage,
        LongTrieMapStorage,
        TrieMapStorage,
        // Buckets by the KEY'S own hashing rather than its runtime representation.
        // Chosen for a key type that wrote its own `Eq`/`Hash`; never promoted away
        // from — see EvidenceMapStorage.
        EvidenceMapStorage {

    /**
     * Returns the number of entries.
     */
    int size();

    /**
     * Returns true if empty.
     */
    default boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Gets the value for a key, or null if not present.
     */
    Object get(Object key);

    /**
     * Returns true if the key is present.
     */
    boolean containsKey(Object key);

    /**
     * Returns a new storage with the key-value pair added/updated.
     */
    MapStorage put(Object key, Object value);

    /**
     * Returns a new storage with the key removed.
     */
    MapStorage remove(Object key);

    /**
     * Returns all keys.
     */
    Object[] keys();

    /**
     * Returns all values.
     */
    Object[] values();

    /**
     * Iterates over all entries without creating intermediate arrays.
     * More efficient than extracting keys() and values() separately.
     */
    default void forEach(java.util.function.BiConsumer<Object, Object> consumer) {
        Object[] keys = keys();
        Object[] values = values();
        for (int i = 0; i < keys.length; i++) {
            consumer.accept(keys[i], values[i]);
        }
    }

    /**
     * {@link #forEach} that can STOP, for the operations that answer before the
     * end: `any`, `all`, `none` and `find`. Returns false iff the predicate
     * stopped it.
     *
     * <p>The two tries override this with a walk that returns early. The
     * default pays for {@link #keys()} and {@link #values()}, which is what
     * every storage used to do and is no worse than it was — the storages that
     * take it hold at most 32 entries, where the arrays are the cheap answer
     * anyway.
     */
    default boolean forEachUntil(java.util.function.BiPredicate<Object, Object> p) {
        Object[] keys = keys();
        Object[] values = values();
        for (int i = 0; i < keys.length; i++) {
            if (!p.test(keys[i], values[i])) return false;
        }
        return true;
    }

    /**
     * Returns the storage strategy tag.
     */
    byte getStrategyTag();

    // Strategy tags
    byte TAG_EMPTY = 0;
    byte TAG_SINGLE = 1;
    byte TAG_SMALL_FLAT = 2;
    byte TAG_LONG_KEY = 3;
    byte TAG_HASH = 4;
}
