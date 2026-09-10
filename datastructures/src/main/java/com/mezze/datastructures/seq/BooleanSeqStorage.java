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

    @CompilationFinal
    private final int bitOffset;  // bit offset into words[] for zero-copy slice views

    /** COW ownership token for O(1) amortized append chains. */
    private final Object[] ownerToken;

    public BooleanSeqStorage(long[] words, int size) {
        this.words = words;
        this.size = size;
        this.bitOffset = 0;
        this.ownerToken = null;
    }

    private BooleanSeqStorage(long[] words, int size, Object[] ownerToken) {
        this.words = words;
        this.size = size;
        this.bitOffset = 0;
        this.ownerToken = ownerToken;
    }

    /** Zero-copy slice view: shares the parent's packed words. */
    private BooleanSeqStorage(long[] words, int bitOffset, int size) {
        this.words = words;
        this.size = size;
        this.bitOffset = bitOffset;
        this.ownerToken = null;
    }

    private boolean isOwner() {
        return bitOffset == 0 && ownerToken != null && ownerToken[0] == this;
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
        int actualIndex = bitOffset + index;
        int wordIndex = actualIndex >>> 6;
        int bitIndex = actualIndex & 63;
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

        // Need a mutable copy at bitOffset=0
        int requiredWords = (size + 63) >>> 6;
        long[] newWords;
        if (bitOffset == 0) {
            // compactWords() may return the shared words array; always copy
            newWords = Arrays.copyOf(words, requiredWords);
        } else {
            // compactWords() already allocates a fresh array when bitOffset != 0
            newWords = compactWords();
        }
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

        // isOwner() already rejects views (bitOffset != 0)
        int requiredWordsOwner = (newSize + 63) >>> 6;
        if (isOwner() && requiredWordsOwner <= words.length) {
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

        // Compact to bitOffset=0 first (no-op if already zero)
        long[] compacted = compactWords();
        int requiredWords = (newSize + 63) >>> 6;

        // Allocate with spare capacity (extra words for future appends)
        int newCapacity = Math.max(requiredWords, compacted.length + Math.max(1, compacted.length >> 1) + 1);
        long[] newWords = new long[newCapacity];
        System.arraycopy(compacted, 0, newWords, 0, compacted.length);

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
    public BooleanSeqStorage prependBoolean(boolean value) {
        // Compact to bitOffset=0 first
        long[] compacted = compactWords();
        int newSize = size + 1;
        int requiredWords = (newSize + 63) >>> 6;
        long[] newWords = new long[requiredWords];

        // Shift all bits by 1
        long carry = 0;
        for (int i = 0; i < compacted.length; i++) {
            long word = compacted[i];
            newWords[i] = (word << 1) | carry;
            carry = word >>> 63;
        }
        if (requiredWords > compacted.length && carry != 0) {
            newWords[compacted.length] = carry;
        }

        // Set first bit
        if (value) {
            newWords[0] |= 1L;
        }

        return new BooleanSeqStorage(newWords, newSize);
    }

    @Override
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
        // Zero-copy view over the same packed words.
        return new BooleanSeqStorage(words, bitOffset + start, end - start);
    }

    @Override
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
     * Returns underlying packed words (compacted to bitOffset=0).
     */
    public long[] getWords() {
        long[] compacted = compactWords();
        int requiredWords = (size + 63) >>> 6;
        if (requiredWords == compacted.length) return compacted;
        return Arrays.copyOf(compacted, requiredWords);
    }

    /**
     * Returns a word array with bitOffset=0 containing exactly this view's bits.
     * Used by mutating operations that need a fresh zero-offset array.
     */
    private long[] compactWords() {
        if (bitOffset == 0) {
            int requiredWords = (size + 63) >>> 6;
            if (requiredWords == words.length) return words;
            return Arrays.copyOf(words, requiredWords);
        }
        // Shift bits to start at position 0 in new array
        int requiredWords = (size + 63) >>> 6;
        long[] result = new long[requiredWords];
        int wordShift = bitOffset >>> 6;
        int bitShift = bitOffset & 63;
        if (bitShift == 0) {
            // Word-aligned: just copy the right slice of words
            System.arraycopy(words, wordShift, result, 0, requiredWords);
        } else {
            // Need to shift bits across word boundaries
            for (int i = 0; i < requiredWords; i++) {
                result[i] = words[wordShift + i] >>> bitShift;
                if (wordShift + i + 1 < words.length) {
                    result[i] |= words[wordShift + i + 1] << (64 - bitShift);
                }
            }
        }
        // Clear unused bits in the last word
        int usedBitsInLastWord = size & 63;
        if (usedBitsInLastWord > 0 && requiredWords > 0) {
            result[requiredWords - 1] &= (1L << usedBitsInLastWord) - 1;
        }
        return result;
    }

    // ===== Boolean-specific operations =====

    /**
     * Count true values (popcount).
     */
    public int countTrue() {
        long[] compacted = compactWords();
        int requiredWords = (size + 63) >>> 6;
        int count = 0;
        for (int i = 0; i < requiredWords; i++) {
            count += Long.bitCount(compacted[i]);
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
        long[] compacted = compactWords();
        int requiredWords = (size + 63) >>> 6;
        for (int i = 0; i < requiredWords; i++) {
            if (compacted[i] != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Find index of first true, or -1 if none.
     */
    public int indexOfTrue() {
        long[] compacted = compactWords();
        int requiredWords = (size + 63) >>> 6;
        for (int i = 0; i < requiredWords; i++) {
            if (compacted[i] != 0) {
                int bitIndex = Long.numberOfTrailingZeros(compacted[i]);
                int index = (i << 6) + bitIndex;
                return index < size ? index : -1;
            }
        }
        return -1;
    }

    /**
     * Reverse the sequence.
     */
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
    public BooleanSeqStorage and(BooleanSeqStorage other) {
        if (size != other.size) {
            throw new IllegalArgumentException("Sequences must have same size");
        }
        long[] thisCompacted = compactWords();
        long[] otherCompacted = other.compactWords();
        int requiredWords = (size + 63) >>> 6;
        long[] newWords = new long[requiredWords];
        for (int i = 0; i < requiredWords; i++) {
            newWords[i] = thisCompacted[i] & otherCompacted[i];
        }
        return new BooleanSeqStorage(newWords, size);
    }

    /**
     * Logical OR with another boolean sequence.
     */
    public BooleanSeqStorage or(BooleanSeqStorage other) {
        if (size != other.size) {
            throw new IllegalArgumentException("Sequences must have same size");
        }
        long[] thisCompacted = compactWords();
        long[] otherCompacted = other.compactWords();
        int requiredWords = (size + 63) >>> 6;
        long[] newWords = new long[requiredWords];
        for (int i = 0; i < requiredWords; i++) {
            newWords[i] = thisCompacted[i] | otherCompacted[i];
        }
        return new BooleanSeqStorage(newWords, size);
    }

    /**
     * Logical NOT (flip all bits).
     */
    public BooleanSeqStorage not() {
        long[] compacted = compactWords();
        int requiredWords = (size + 63) >>> 6;
        long[] newWords = new long[requiredWords];
        for (int i = 0; i < requiredWords; i++) {
            newWords[i] = ~compacted[i];
        }
        return new BooleanSeqStorage(newWords, size);
    }

    /**
     * Transition to ObjectStorage when type changes.
     */
    private ObjectSeqStorage transitionToObject() {
        Object[] objects = new Object[size];
        for (int i = 0; i < size; i++) {
            objects[i] = getBoolean(i);
        }
        return new ObjectSeqStorage(objects);
    }

    @Override
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
        long[] compacted = compactWords();
        int requiredWords = (size + 63) >>> 6;
        int result = size;
        for (int i = 0; i < requiredWords; i++) {
            result = 31 * result + Long.hashCode(compacted[i]);
        }
        return result;
    }
}
