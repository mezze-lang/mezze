package com.mezze.datastructures.set;

/**
 * Base interface for all Set storage strategies.
 */
public sealed interface SetStorage permits
        EmptySetStorage,
        SingleElemSetStorage,
        SmallFlatSetStorage,
        LongSetStorage,
        LongTrieSetStorage,
        TrieSetStorage,
        // Buckets by the ELEMENT'S own hashing rather than the runtime
        // representation. Chosen for an element type that wrote its own `Eq`/`Hash`,
        // and never promoted away from — see EvidenceSetStorage.
        EvidenceSetStorage {

    /**
     * Returns the number of elements.
     */
    int size();

    /**
     * Returns true if empty.
     */
    default boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Returns true if the element is present.
     */
    boolean contains(Object elem);

    /**
     * Returns a new storage with the element added.
     */
    SetStorage add(Object elem);

    /**
     * Returns a new storage with the element removed.
     */
    SetStorage remove(Object elem);

    /**
     * Returns all elements.
     */
    Object[] toArray();

    /**
     * Iterates over all elements without creating intermediate arrays.
     * More efficient than extracting toArray() first.
     */
    default void forEach(java.util.function.Consumer<Object> consumer) {
        Object[] elements = toArray();
        for (Object elem : elements) {
            consumer.accept(elem);
        }
    }

    /**
     * {@link #forEach} that can STOP, for the operations that answer before the
     * end: `any`, `all`, `none` and `find`. Returns false iff the predicate
     * stopped it.
     *
     * <p>The two tries override both this and {@link #forEach}; the default
     * pays for {@link #toArray()}, which is what every storage used to do and
     * is no worse than it was — the storages that take it hold at most 32
     * elements, where the array is the cheap answer anyway.
     */
    default boolean forEachUntil(java.util.function.Predicate<Object> p) {
        for (Object elem : toArray()) {
            if (!p.test(elem)) return false;
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
    byte TAG_LONG = 3;
    byte TAG_HASH = 4;
}
