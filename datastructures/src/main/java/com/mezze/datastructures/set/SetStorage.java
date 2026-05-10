package com.mezze.datastructures.set;

/**
 * Base interface for all Set storage strategies.
 */
public sealed interface SetStorage permits
        EmptySetStorage,
        SingleElemSetStorage,
        SmallFlatSetStorage,
        LongSetStorage,
        LongHashSetStorage,
        HashSetStorage {

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
