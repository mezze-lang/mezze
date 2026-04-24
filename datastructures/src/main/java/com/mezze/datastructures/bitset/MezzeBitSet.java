package com.mezze.datastructures.bitset;

import com.mezze.datastructures.util.VectorOps;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.ExplodeLoop;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Immutable set of non-negative integers backed by packed long[].
 * Each long stores 64 bits, providing very memory-efficient storage.
 *
 * Operations like union, intersection, difference use bitwise operations
 * which are extremely fast and SIMD-friendly.
 */
public final class MezzeBitSet implements TruffleObject, Iterable<Integer> {

    @CompilationFinal(dimensions = 1)
    private final long[] words;

    @CompilationFinal
    private final int cardinality;  // Cached count of set bits

    /** COW ownership token for O(1) amortized add chains. */
    private final Object[] ownerToken;

    private MezzeBitSet(long[] words, int cardinality) {
        this.words = words;
        this.cardinality = cardinality;
        this.ownerToken = null;
    }

    private MezzeBitSet(long[] words, int cardinality, Object[] ownerToken) {
        this.words = words;
        this.cardinality = cardinality;
        this.ownerToken = ownerToken;
    }

    private boolean isOwner() {
        return ownerToken != null && ownerToken[0] == this;
    }

    private void revokeOwnership() {
        if (ownerToken != null && ownerToken[0] == this) {
            ownerToken[0] = null;
        }
    }

    // ===== CONSTRUCTION =====

    /**
     * Returns an empty bitset.
     */
    public static MezzeBitSet empty() {
        return new MezzeBitSet(new long[0], 0);
    }

    /**
     * Creates a bitset with a single index.
     */
    public static MezzeBitSet of(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Index must be non-negative");
        }
        int wordIndex = index >>> 6;
        long[] words = new long[wordIndex + 1];
        words[wordIndex] = 1L << (index & 63);
        return new MezzeBitSet(words, 1);
    }

    /**
     * Creates a bitset from indices.
     */
    @TruffleBoundary
    public static MezzeBitSet of(int... indices) {
        if (indices.length == 0) return empty();
        if (indices.length == 1) return of(indices[0]);

        // Find max to size array
        int max = 0;
        for (int idx : indices) {
            if (idx < 0) {
                throw new IllegalArgumentException("Index must be non-negative");
            }
            if (idx > max) max = idx;
        }

        int numWords = (max >>> 6) + 1;
        long[] words = new long[numWords];

        for (int idx : indices) {
            int wordIndex = idx >>> 6;
            words[wordIndex] |= 1L << (idx & 63);
        }

        int cardinality = 0;
        for (long word : words) {
            cardinality += Long.bitCount(word);
        }

        return new MezzeBitSet(words, cardinality);
    }

    /**
     * Creates a bitset for range [start, end).
     * Optimized to set whole words at once instead of bit-by-bit.
     */
    @TruffleBoundary
    public static MezzeBitSet range(int start, int end) {
        if (start < 0 || start >= end) {
            return empty();
        }

        int startWord = start >>> 6;
        int startBit = start & 63;
        int endWord = (end - 1) >>> 6;
        int endBit = (end - 1) & 63;
        int numWords = endWord + 1;
        long[] words = new long[numWords];

        if (startWord == endWord) {
            // All bits in same word: create mask from startBit to endBit
            long mask = ((1L << (endBit - startBit + 1)) - 1) << startBit;
            words[startWord] = mask;
        } else {
            // First word: set bits from startBit to 63
            words[startWord] = -1L << startBit;  // All 1s from startBit onwards

            // Middle words: all bits set
            for (int i = startWord + 1; i < endWord; i++) {
                words[i] = -1L;  // All 64 bits set
            }

            // Last word: set bits from 0 to endBit
            words[endWord] = (1L << (endBit + 1)) - 1;  // All 1s up to endBit
        }

        return new MezzeBitSet(words, end - start);
    }

    // ===== ACCESS =====

    /**
     * Returns the number of set bits.
     */
    public int size() {
        return cardinality;
    }

    /**
     * Returns true if no bits are set.
     */
    public boolean isEmpty() {
        return cardinality == 0;
    }

    /**
     * Returns true if the bit at index is set.
     */
    public boolean contains(int index) {
        revokeOwnership();
        if (index < 0) return false;
        int wordIndex = index >>> 6;
        if (wordIndex >= words.length) return false;
        return (words[wordIndex] & (1L << (index & 63))) != 0;
    }

    /**
     * Returns the minimum set index, or -1 if empty.
     */
    public int min() {
        for (int i = 0; i < words.length; i++) {
            if (words[i] != 0) {
                return (i << 6) + Long.numberOfTrailingZeros(words[i]);
            }
        }
        return -1;
    }

    /**
     * Returns the maximum set index, or -1 if empty.
     */
    public int max() {
        for (int i = words.length - 1; i >= 0; i--) {
            if (words[i] != 0) {
                return (i << 6) + 63 - Long.numberOfLeadingZeros(words[i]);
            }
        }
        return -1;
    }

    // ===== UPDATE =====

    /**
     * Returns a new bitset with the index added.
     */
    @TruffleBoundary
    public MezzeBitSet add(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Index must be non-negative");
        }

        int wordIndex = index >>> 6;
        int bitIndex = index & 63;
        long mask = 1L << bitIndex;

        // Check if already set
        if (wordIndex < words.length && (words[wordIndex] & mask) != 0) {
            return this;
        }

        if (isOwner() && wordIndex < words.length) {
            // O(1): bit fits in existing words array — set in-place
            words[wordIndex] |= mask;
            var result = new MezzeBitSet(words, cardinality + 1, ownerToken);
            ownerToken[0] = result;
            return result;
        }

        // Allocate with spare capacity
        int newCapacity = Math.max(wordIndex + 1, words.length + Math.max(1, words.length >> 1) + 1);
        long[] newWords = new long[newCapacity];
        System.arraycopy(words, 0, newWords, 0, words.length);
        newWords[wordIndex] |= mask;

        Object[] token = new Object[1];
        var result = new MezzeBitSet(newWords, cardinality + 1, token);
        token[0] = result;
        return result;
    }

    /**
     * Returns a new bitset with the index removed.
     */
    @TruffleBoundary
    public MezzeBitSet remove(int index) {
        revokeOwnership();
        if (index < 0 || !contains(index)) {
            return this;
        }

        int wordIndex = index >>> 6;
        long[] newWords = words.clone();
        newWords[wordIndex] &= ~(1L << (index & 63));

        // Trim trailing zero words
        int newLength = newWords.length;
        while (newLength > 0 && newWords[newLength - 1] == 0) {
            newLength--;
        }

        if (newLength < newWords.length) {
            newWords = Arrays.copyOf(newWords, newLength);
        }

        return new MezzeBitSet(newWords, cardinality - 1);
    }

    // ===== SET OPERATIONS =====

    /**
     * Union (OR) with another bitset.
     * Computes cardinality inline: |A ∪ B| = |A| + |B| - |A ∩ B|
     */
    @TruffleBoundary
    public MezzeBitSet union(MezzeBitSet other) {
        if (isEmpty()) return other;
        if (other.isEmpty()) return this;

        // Compute union and track cardinality inline
        int maxLen = Math.max(words.length, other.words.length);
        long[] result = new long[maxLen];
        int card = 0;

        int minLen = Math.min(words.length, other.words.length);

        // Process overlapping portion - count bits as we go
        for (int i = 0; i < minLen; i++) {
            long or = words[i] | other.words[i];
            result[i] = or;
            card += Long.bitCount(or);
        }

        // Copy remaining from longer array
        if (words.length > other.words.length) {
            for (int i = minLen; i < words.length; i++) {
                result[i] = words[i];
                card += Long.bitCount(words[i]);
            }
        } else {
            for (int i = minLen; i < other.words.length; i++) {
                result[i] = other.words[i];
                card += Long.bitCount(other.words[i]);
            }
        }

        return new MezzeBitSet(result, card);
    }

    /**
     * Intersection (AND) with another bitset.
     * Computes cardinality inline during the AND operation.
     */
    @TruffleBoundary
    public MezzeBitSet intersect(MezzeBitSet other) {
        if (isEmpty() || other.isEmpty()) return empty();

        int minLen = Math.min(words.length, other.words.length);
        long[] result = new long[minLen];
        int card = 0;
        int lastNonZero = -1;

        // Process and count bits as we go
        for (int i = 0; i < minLen; i++) {
            long and = words[i] & other.words[i];
            result[i] = and;
            int bits = Long.bitCount(and);
            card += bits;
            if (bits > 0) {
                lastNonZero = i;
            }
        }

        if (card == 0) return empty();

        // Trim trailing zeros
        if (lastNonZero < minLen - 1) {
            result = Arrays.copyOf(result, lastNonZero + 1);
        }

        return new MezzeBitSet(result, card);
    }

    /**
     * Difference (this AND NOT other).
     * Computes cardinality inline during the operation.
     */
    @TruffleBoundary
    public MezzeBitSet difference(MezzeBitSet other) {
        if (isEmpty()) return this;
        if (other.isEmpty()) return this;

        long[] result = new long[words.length];
        int card = 0;
        int lastNonZero = -1;

        int minLen = Math.min(words.length, other.words.length);

        // Process overlapping portion
        for (int i = 0; i < minLen; i++) {
            long andNot = words[i] & ~other.words[i];
            result[i] = andNot;
            int bits = Long.bitCount(andNot);
            card += bits;
            if (bits > 0) {
                lastNonZero = i;
            }
        }

        // Copy remaining from this (nothing to subtract)
        for (int i = minLen; i < words.length; i++) {
            result[i] = words[i];
            int bits = Long.bitCount(words[i]);
            card += bits;
            if (bits > 0) {
                lastNonZero = i;
            }
        }

        if (card == 0) return empty();

        // Trim trailing zeros
        if (lastNonZero < result.length - 1) {
            result = Arrays.copyOf(result, lastNonZero + 1);
        }

        return new MezzeBitSet(result, card);
    }

    /**
     * Symmetric difference (XOR).
     * Computes cardinality inline during the operation.
     */
    @TruffleBoundary
    public MezzeBitSet symmetricDifference(MezzeBitSet other) {
        if (isEmpty()) return other;
        if (other.isEmpty()) return this;

        int maxLen = Math.max(words.length, other.words.length);
        long[] result = new long[maxLen];
        int card = 0;
        int lastNonZero = -1;

        int minLen = Math.min(words.length, other.words.length);

        // Process overlapping portion
        for (int i = 0; i < minLen; i++) {
            long xor = words[i] ^ other.words[i];
            result[i] = xor;
            int bits = Long.bitCount(xor);
            card += bits;
            if (bits > 0) {
                lastNonZero = i;
            }
        }

        // Copy remaining from longer array
        if (words.length > other.words.length) {
            for (int i = minLen; i < words.length; i++) {
                result[i] = words[i];
                int bits = Long.bitCount(words[i]);
                card += bits;
                if (bits > 0) {
                    lastNonZero = i;
                }
            }
        } else {
            for (int i = minLen; i < other.words.length; i++) {
                result[i] = other.words[i];
                int bits = Long.bitCount(other.words[i]);
                card += bits;
                if (bits > 0) {
                    lastNonZero = i;
                }
            }
        }

        if (card == 0) return empty();

        // Trim trailing zeros
        if (lastNonZero < result.length - 1) {
            result = Arrays.copyOf(result, lastNonZero + 1);
        }

        return new MezzeBitSet(result, card);
    }

    /**
     * Complement (NOT) - flips all bits up to given limit.
     */
    @TruffleBoundary
    public MezzeBitSet complement(int limit) {
        if (limit <= 0) return empty();

        int numWords = (limit + 63) >>> 6;
        long[] result = new long[numWords];

        // XOR with all-ones up to limit
        for (int i = 0; i < numWords; i++) {
            long word = i < words.length ? words[i] : 0;
            result[i] = ~word;
        }

        // Clear bits beyond limit in last word
        int lastWordBits = limit & 63;
        if (lastWordBits != 0) {
            result[numWords - 1] &= (1L << lastWordBits) - 1;
        }

        int card = 0;
        for (long word : result) {
            card += Long.bitCount(word);
        }

        return new MezzeBitSet(result, card);
    }

    // ===== SUBSET =====

    /**
     * Returns true if this is a subset of other.
     */
    public boolean isSubsetOf(MezzeBitSet other) {
        if (words.length > other.words.length) {
            // Check if extra words are zero
            for (int i = other.words.length; i < words.length; i++) {
                if (words[i] != 0) return false;
            }
        }

        int minLength = Math.min(words.length, other.words.length);
        for (int i = 0; i < minLength; i++) {
            if ((words[i] & ~other.words[i]) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns true if sets are disjoint (no common elements).
     */
    public boolean isDisjoint(MezzeBitSet other) {
        int minLength = Math.min(words.length, other.words.length);
        for (int i = 0; i < minLength; i++) {
            if ((words[i] & other.words[i]) != 0) {
                return false;
            }
        }
        return true;
    }

    // ===== ITERATION =====

    @Override
    public Iterator<Integer> iterator() {
        return new Iterator<>() {
            private int wordIndex = 0;
            private long currentWord = words.length > 0 ? words[0] : 0;
            private int bitIndex = -1;

            {
                advance();
            }

            private void advance() {
                // Find next set bit
                while (currentWord == 0 && wordIndex < words.length - 1) {
                    wordIndex++;
                    currentWord = words[wordIndex];
                }

                if (currentWord != 0) {
                    bitIndex = Long.numberOfTrailingZeros(currentWord);
                } else {
                    bitIndex = -1;
                }
            }

            @Override
            public boolean hasNext() {
                return bitIndex >= 0;
            }

            @Override
            public Integer next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }

                int result = (wordIndex << 6) + bitIndex;

                // Clear this bit and advance
                currentWord &= ~(1L << bitIndex);
                advance();

                return result;
            }
        };
    }

    /**
     * Returns all set indices as array.
     */
    @TruffleBoundary
    public int[] toArray() {
        int[] result = new int[cardinality];
        int idx = 0;
        for (int i = 0; i < words.length; i++) {
            long word = words[i];
            while (word != 0) {
                int bit = Long.numberOfTrailingZeros(word);
                result[idx++] = (i << 6) + bit;
                word &= ~(1L << bit);
            }
        }
        return result;
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("BitSet{");
        boolean first = true;
        for (int idx : this) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(idx);
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeBitSet other)) return false;
        if (cardinality != other.cardinality) return false;

        int minLength = Math.min(words.length, other.words.length);
        for (int i = 0; i < minLength; i++) {
            if (words[i] != other.words[i]) return false;
        }

        // Check remaining words are zero
        for (int i = minLength; i < words.length; i++) {
            if (words[i] != 0) return false;
        }
        for (int i = minLength; i < other.words.length; i++) {
            if (other.words[i] != 0) return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = cardinality;
        // Only hash up to last non-zero word (ignore spare capacity)
        int len = words.length;
        while (len > 0 && words[len - 1] == 0) len--;
        for (int i = 0; i < len; i++) {
            result = 31 * result + Long.hashCode(words[i]);
        }
        return result;
    }
}
