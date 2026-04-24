package com.mezze.datastructures.seq;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Arrays;

/**
 * Packed boolean storage using long[] for memory efficiency.
 * Each long stores 64 booleans, reducing memory by 8x compared to boolean[].
 */
public final class BooleanSeqStorage implements SeqStorage {

    @CompilationFinal(dimensions = 1)
    private final long[] words;

    @CompilationFinal
    private final int size;

    /** COW ownership token for O(1) amortized append chains. */
    private final Object[] ownerToken;

    public BooleanSeqStorage(long[] words, int size) {
        this.words = words;
        this.size = size;
        this.ownerToken = null;
    }

    private BooleanSeqStorage(long[] words, int size, Object[] ownerToken) {
        this.words = words;
        this.size = size;
        this.ownerToken = ownerToken;
    }

    private boolean isOwner() {
        return ownerToken != null && ownerToken[0] == this;
    }

    /**
     * Create from boolean array.
     */
    public static BooleanSeqStorage fromBooleans(boolean[] booleans) {
        int numWords = (booleans.length + 63) >>> 6;  // Divide by 64, rounding up
        long[] words = new long[numWords];

        for (int i = 0; i < booleans.length; i++) {
            if (booleans[i]) {
                int wordIndex = i >>> 6;
                int bitIndex = i & 63;
                words[wordIndex] |= (1L << bitIndex);
            }
        }

        return new BooleanSeqStorage(words, booleans.length);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Object get(int index) {
        return getBoolean(index);
    }

    /**
     * Unboxed access.
     */
    public boolean getBoolean(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        int wordIndex = index >>> 6;
        int bitIndex = index & 63;
        return (words[wordIndex] & (1L << bitIndex)) != 0;
    }

    @Override
    public SeqStorage update(int index, Object value) {
        if (value instanceof Boolean b) {
            return updateBoolean(index, b);
        }
        return transitionToObject().update(index, value);
    }

    /**
     * Unboxed update.
     */
    public BooleanSeqStorage updateBoolean(int index, boolean value) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }

        long[] newWords = words.clone();
        int wordIndex = index >>> 6;
        int bitIndex = index & 63;

        if (value) {
            newWords[wordIndex] |= (1L << bitIndex);
        } else {
            newWords[wordIndex] &= ~(1L << bitIndex);
        }

        return new BooleanSeqStorage(newWords, size);
    }

    @Override
    public SeqStorage append(Object value) {
        if (value instanceof Boolean b) {
            return appendBoolean(b);
        }
        return transitionToObject().append(value);
    }

    /**
     * Unboxed append with COW + spare capacity.
     * O(1) amortized in append chains.
     */
    public BooleanSeqStorage appendBoolean(boolean value) {
        int newSize = size + 1;
        int requiredWords = (newSize + 63) >>> 6;

        if (isOwner() && requiredWords <= words.length) {
            // O(1): spare capacity in existing words array — set bit in-place
            if (value) {
                int wordIndex = size >>> 6;
                int bitIndex = size & 63;
                words[wordIndex] |= (1L << bitIndex);
            }
            // Note: if value is false, the bit is already 0 in spare capacity
            var result = new BooleanSeqStorage(words, newSize, ownerToken);
            ownerToken[0] = result;
            return result;
        }

        // Allocate with spare capacity (extra words for future appends)
        int newCapacity = Math.max(requiredWords, words.length + Math.max(1, words.length >> 1) + 1);
        long[] newWords = new long[newCapacity];
        System.arraycopy(words, 0, newWords, 0, words.length);

        if (value) {
            int wordIndex = size >>> 6;
            int bitIndex = size & 63;
            newWords[wordIndex] |= (1L << bitIndex);
        }

        Object[] token = new Object[1];
        var result = new BooleanSeqStorage(newWords, newSize, token);
        token[0] = result;
        return result;
    }

    @Override
    public SeqStorage prepend(Object value) {
        if (value instanceof Boolean b) {
            return prependBoolean(b);
        }
        return transitionToObject().prepend(value);
    }

    /**
     * Unboxed prepend - shifts all bits by 1.
     */
    @TruffleBoundary
    public BooleanSeqStorage prependBoolean(boolean value) {
        int newSize = size + 1;
        int requiredWords = (newSize + 63) >>> 6;
        long[] newWords = new long[requiredWords];

        // Shift all bits by 1
        long carry = 0;
        for (int i = 0; i < words.length; i++) {
            long word = words[i];
            newWords[i] = (word << 1) | carry;
            carry = word >>> 63;
        }
        if (requiredWords > words.length && carry != 0) {
            newWords[words.length] = carry;
        }

        // Set first bit
        if (value) {
            newWords[0] |= 1L;
        }

        return new BooleanSeqStorage(newWords, newSize);
    }

    @Override
    @TruffleBoundary
    public SeqStorage slice(int start, int end) {
        if (start < 0 || end > size || start > end) {
            throw new IndexOutOfBoundsException("Invalid slice range: " + start + " to " + end);
        }
        if (start == end) {
            return EmptySeqStorage.INSTANCE;
        }
        if (start == 0 && end == size) {
            return this;
        }

        int newSize = end - start;
        boolean[] result = new boolean[newSize];
        for (int i = 0; i < newSize; i++) {
            result[i] = getBoolean(start + i);
        }
        return fromBooleans(result);
    }

    @Override
    @TruffleBoundary
    public SeqStorage concat(SeqStorage other) {
        if (other.isEmpty()) {
            return this;
        }
        if (other instanceof BooleanSeqStorage boolOther) {
            return concatBoolean(boolOther);
        }
        return transitionToObject().concat(other);
    }

    /**
     * Concat two boolean storages.
     */
    @TruffleBoundary
    public BooleanSeqStorage concatBoolean(BooleanSeqStorage other) {
        int newSize = size + other.size;
        boolean[] result = new boolean[newSize];

        for (int i = 0; i < size; i++) {
            result[i] = getBoolean(i);
        }
        for (int i = 0; i < other.size; i++) {
            result[size + i] = other.getBoolean(i);
        }

        return fromBooleans(result);
    }

    @Override
    public byte getStrategyTag() {
        return TAG_BOOLEAN;
    }

    /**
     * Returns underlying packed words.
     */
    public long[] getWords() {
        int requiredWords = (size + 63) >>> 6;
        if (requiredWords == words.length) return words;
        return Arrays.copyOf(words, requiredWords);
    }

    // ===== Boolean-specific operations =====

    /**
     * Count true values (popcount).
     */
    public int countTrue() {
        int requiredWords = (size + 63) >>> 6;
        int count = 0;
        for (int i = 0; i < requiredWords; i++) {
            count += Long.bitCount(words[i]);
        }
        // Adjust for unused bits in last word
        int unusedBits = (requiredWords << 6) - size;
        if (unusedBits > 0 && requiredWords > 0) {
            int lastWordIndex = requiredWords - 1;
            long mask = (1L << (64 - unusedBits)) - 1;
            int extraBits = Long.bitCount(words[lastWordIndex] & ~mask);
            count -= extraBits;
        }
        return count;
    }

    /**
     * Count false values.
     */
    public int countFalse() {
        return size - countTrue();
    }

    /**
     * Check if all values are true.
     */
    public boolean allTrue() {
        return countTrue() == size;
    }

    /**
     * Check if any value is true.
     */
    public boolean anyTrue() {
        int requiredWords = (size + 63) >>> 6;
        for (int i = 0; i < requiredWords; i++) {
            if (words[i] != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find index of first true, or -1 if none.
     */
    public int indexOfTrue() {
        int requiredWords = (size + 63) >>> 6;
        for (int i = 0; i < requiredWords; i++) {
            if (words[i] != 0) {
                int bitIndex = Long.numberOfTrailingZeros(words[i]);
                int index = (i << 6) + bitIndex;
                return index < size ? index : -1;
            }
        }
        return -1;
    }

    /**
     * Reverse the sequence.
     */
    @TruffleBoundary
    public BooleanSeqStorage reverse() {
        boolean[] result = new boolean[size];
        for (int i = 0; i < size; i++) {
            result[i] = getBoolean(size - 1 - i);
        }
        return fromBooleans(result);
    }

    /**
     * Logical AND with another boolean sequence.
     */
    @TruffleBoundary
    public BooleanSeqStorage and(BooleanSeqStorage other) {
        if (size != other.size) {
            throw new IllegalArgumentException("Sequences must have same size");
        }
        int requiredWords = (size + 63) >>> 6;
        long[] newWords = new long[requiredWords];
        for (int i = 0; i < requiredWords; i++) {
            newWords[i] = words[i] & other.words[i];
        }
        return new BooleanSeqStorage(newWords, size);
    }

    /**
     * Logical OR with another boolean sequence.
     */
    @TruffleBoundary
    public BooleanSeqStorage or(BooleanSeqStorage other) {
        if (size != other.size) {
            throw new IllegalArgumentException("Sequences must have same size");
        }
        int requiredWords = (size + 63) >>> 6;
        long[] newWords = new long[requiredWords];
        for (int i = 0; i < requiredWords; i++) {
            newWords[i] = words[i] | other.words[i];
        }
        return new BooleanSeqStorage(newWords, size);
    }

    /**
     * Logical NOT (flip all bits).
     */
    public BooleanSeqStorage not() {
        int requiredWords = (size + 63) >>> 6;
        long[] newWords = new long[requiredWords];
        for (int i = 0; i < requiredWords; i++) {
            newWords[i] = ~words[i];
        }
        return new BooleanSeqStorage(newWords, size);
    }

    /**
     * Transition to ObjectStorage when type changes.
     */
    @TruffleBoundary
    private ObjectSeqStorage transitionToObject() {
        Object[] objects = new Object[size];
        for (int i = 0; i < size; i++) {
            objects[i] = getBoolean(i);
        }
        return new ObjectSeqStorage(objects);
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("BooleanSeqStorage[");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            sb.append(getBoolean(i));
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof BooleanSeqStorage other)) return false;
        if (size != other.size) return false;
        // Compare only relevant bits
        for (int i = 0; i < size; i++) {
            if (getBoolean(i) != other.getBoolean(i)) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int requiredWords = (size + 63) >>> 6;
        int result = size;
        for (int i = 0; i < requiredWords; i++) {
            result = 31 * result + Long.hashCode(words[i]);
        }
        return result;
    }
}
