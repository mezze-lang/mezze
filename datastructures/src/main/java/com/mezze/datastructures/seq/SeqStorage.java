package com.mezze.datastructures.seq;

/**
 * Base interface for all Seq storage strategies.
 * Each implementation provides optimized storage for specific element types.
 */
public sealed interface SeqStorage permits
        EmptySeqStorage,
        LongSeqStorage,
        DoubleSeqStorage,
        BooleanSeqStorage,
        ObjectSeqStorage {

    /**
     * Returns the number of elements in this storage.
     */
    int size();

    /**
     * Returns true if this storage is empty.
     */
    default boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Returns the element at the given index as an Object.
     * May involve boxing for primitive storages.
     */
    Object get(int index);

    /**
     * Returns a new storage with the element at index replaced.
     */
    SeqStorage update(int index, Object value);

    /**
     * Returns a new storage with the element appended.
     */
    SeqStorage append(Object value);

    /**
     * Returns a new storage with the element prepended.
     */
    SeqStorage prepend(Object value);

    /**
     * Returns a new storage representing a slice from start (inclusive) to end (exclusive).
     */
    SeqStorage slice(int start, int end);

    /**
     * Returns a new storage with the two storages concatenated.
     */
    SeqStorage concat(SeqStorage other);

    /**
     * Returns the storage strategy tag for fast dispatch.
     */
    byte getStrategyTag();

    // Strategy tags
    byte TAG_EMPTY = 0;
    byte TAG_LONG = 1;
    byte TAG_DOUBLE = 2;
    byte TAG_BOOLEAN = 3;
    byte TAG_OBJECT = 4;
}
