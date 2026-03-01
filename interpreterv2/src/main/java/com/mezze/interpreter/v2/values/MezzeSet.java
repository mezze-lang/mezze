package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * AGGRESSIVELY OPTIMIZED immutable set for Mezze.
 *
 * Uses storage strategies based on element types:
 * - EMPTY: Singleton empty set
 * - SMALL: Linear array for size <= 8 (cache-friendly)
 * - LONG: Primitive long[] for Int/Nat sets - ZERO BOXING
 * - STRING: String[] with interning optimization
 * - OBJECT: Hash-based storage for large/mixed sets
 *
 * Key optimizations:
 * 1. Primitive storage to avoid boxing
 * 2. Small set linear scan (beats hashing for n <= 8)
 * 3. Sorted arrays for binary search on larger primitive sets
 * 4. Copy-on-write for persistent operations
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeSet implements TruffleObject {

    // ===== STORAGE STRATEGIES =====

    public enum Strategy {
        EMPTY,   // No elements
        SMALL,   // Linear array, size <= SMALL_THRESHOLD
        LONG,    // Sorted long[] for Int/Nat
        STRING,  // String[] (unsorted, linear)
        OBJECT   // HashSet-based
    }

    private static final int SMALL_THRESHOLD = 8;

    // Singleton empty set
    private static final MezzeSet EMPTY_SET = new MezzeSet();

    @CompilationFinal
    private final Strategy strategy;

    @CompilationFinal
    private final int size;

    // SMALL strategy
    @CompilationFinal(dimensions = 1)
    private final Object[] smallElements;

    // LONG strategy (sorted for binary search)
    @CompilationFinal(dimensions = 1)
    private final long[] longElements;

    // STRING strategy
    @CompilationFinal(dimensions = 1)
    private final String[] stringElements;

    // OBJECT strategy
    private final Set<Object> hashSet;

    // ===== CONSTRUCTORS =====

    /** Empty set */
    private MezzeSet() {
        this.strategy = Strategy.EMPTY;
        this.size = 0;
        this.smallElements = null;
        this.longElements = null;
        this.stringElements = null;
        this.hashSet = null;
    }

    /** Small set (linear array) */
    private MezzeSet(Object[] elements) {
        this.strategy = Strategy.SMALL;
        this.size = elements.length;
        this.smallElements = elements;
        this.longElements = null;
        this.stringElements = null;
        this.hashSet = null;
    }

    /** Long set (sorted for binary search) */
    private MezzeSet(long[] elements, boolean sorted) {
        this.strategy = Strategy.LONG;
        this.size = elements.length;
        this.smallElements = null;
        this.longElements = elements;
        this.stringElements = null;
        this.hashSet = null;
    }

    /** String set */
    private MezzeSet(String[] elements) {
        this.strategy = Strategy.STRING;
        this.size = elements.length;
        this.smallElements = null;
        this.longElements = null;
        this.stringElements = elements;
        this.hashSet = null;
    }

    /** Hash-based set */
    private MezzeSet(Set<Object> set) {
        this.strategy = Strategy.OBJECT;
        this.size = set.size();
        this.smallElements = null;
        this.longElements = null;
        this.stringElements = null;
        this.hashSet = set;
    }

    // ===== FACTORY METHODS =====

    public static MezzeSet empty() {
        return EMPTY_SET;
    }

    public static MezzeSet singleton(Object element) {
        if (element instanceof Long l) {
            return new MezzeSet(new long[]{l}, true);
        } else if (element instanceof String s) {
            return new MezzeSet(new String[]{s});
        } else {
            return new MezzeSet(new Object[]{element});
        }
    }

    /**
     * Create from array - auto-selects best strategy.
     */
    @TruffleBoundary
    public static MezzeSet fromArray(Object[] elements) {
        if (elements.length == 0) return EMPTY_SET;

        // Deduplicate
        Set<Object> unique = new LinkedHashSet<>();
        for (Object e : elements) unique.add(e);

        if (unique.size() == 0) return EMPTY_SET;

        Object[] deduped = unique.toArray();

        // Analyze types
        boolean allLong = true;
        boolean allString = true;
        for (Object e : deduped) {
            if (!(e instanceof Long)) allLong = false;
            if (!(e instanceof String)) allString = false;
        }

        // Small set optimization
        if (deduped.length <= SMALL_THRESHOLD) {
            return new MezzeSet(deduped);
        }

        // Specialized storage
        if (allLong) {
            long[] sorted = new long[deduped.length];
            for (int i = 0; i < deduped.length; i++) {
                sorted[i] = (Long) deduped[i];
            }
            Arrays.sort(sorted);
            return new MezzeSet(sorted, true);
        }

        if (allString) {
            String[] strings = new String[deduped.length];
            for (int i = 0; i < deduped.length; i++) {
                strings[i] = (String) deduped[i];
            }
            return new MezzeSet(strings);
        }

        // Hash-based
        return new MezzeSet(new LinkedHashSet<>(unique));
    }

    /**
     * Create from primitive long array - ZERO BOXING.
     */
    public static MezzeSet fromLongs(long[] elements) {
        if (elements.length == 0) return EMPTY_SET;

        // Sort and deduplicate
        long[] sorted = elements.clone();
        Arrays.sort(sorted);

        // Count unique
        int uniqueCount = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] != sorted[i - 1]) uniqueCount++;
        }

        if (uniqueCount == sorted.length) {
            return new MezzeSet(sorted, true);
        }

        // Compact
        long[] unique = new long[uniqueCount];
        unique[0] = sorted[0];
        int j = 1;
        for (int i = 1; i < sorted.length; i++) {
            if (sorted[i] != sorted[i - 1]) {
                unique[j++] = sorted[i];
            }
        }
        return new MezzeSet(unique, true);
    }

    /**
     * Create from String array - SKIPS TYPE ANALYSIS.
     * Deduplicates using a hash set.
     */
    @TruffleBoundary
    public static MezzeSet fromStrings(String[] elements) {
        if (elements.length == 0) return EMPTY_SET;

        // Deduplicate using LinkedHashSet to preserve insertion order
        Set<String> unique = new LinkedHashSet<>();
        for (String s : elements) {
            unique.add(s);
        }

        if (unique.size() <= SMALL_THRESHOLD) {
            Object[] small = unique.toArray();
            return new MezzeSet(small);
        }

        String[] strings = unique.toArray(new String[0]);
        return new MezzeSet(strings);
    }

    // ===== ACCESSORS =====

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public Strategy getStrategy() {
        return strategy;
    }

    /**
     * Check if element is in set.
     */
    public boolean contains(Object element) {
        return switch (strategy) {
            case EMPTY -> false;
            case SMALL -> containsSmall(element);
            case LONG -> containsLong(element);
            case STRING -> containsString(element);
            case OBJECT -> containsHash(element);
        };
    }

    private boolean containsSmall(Object element) {
        for (int i = 0; i < size; i++) {
            if (java.util.Objects.equals(smallElements[i], element)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsLong(Object element) {
        if (!(element instanceof Long l)) return false;
        // Binary search on sorted array
        return Arrays.binarySearch(longElements, l) >= 0;
    }

    private boolean containsString(Object element) {
        if (!(element instanceof String s)) return false;
        // Identity check first (interned strings)
        for (int i = 0; i < size; i++) {
            if (stringElements[i] == s) return true;
        }
        // Equals check
        for (int i = 0; i < size; i++) {
            if (stringElements[i].equals(s)) return true;
        }
        return false;
    }

    @TruffleBoundary
    private boolean containsHash(Object element) {
        return hashSet.contains(element);
    }

    /**
     * Check if long is in set - NO BOXING for LONG strategy.
     */
    public boolean containsLong(long element) {
        if (strategy == Strategy.LONG) {
            return Arrays.binarySearch(longElements, element) >= 0;
        }
        return contains(element);
    }

    // ===== PERSISTENT OPERATIONS =====

    /**
     * Add element - returns new set (COW).
     */
    @TruffleBoundary
    public MezzeSet add(Object element) {
        if (contains(element)) return this;

        if (strategy == Strategy.EMPTY) {
            return singleton(element);
        }

        int newSize = size + 1;

        // Check if we should transition to hash
        if (newSize > SMALL_THRESHOLD && strategy != Strategy.OBJECT) {
            return transitionToHashAndAdd(element);
        }

        return switch (strategy) {
            case SMALL -> {
                Object[] newElements = Arrays.copyOf(smallElements, newSize);
                newElements[size] = element;
                yield new MezzeSet(newElements);
            }
            case LONG -> {
                if (element instanceof Long l) {
                    // Insert in sorted order
                    int insertPos = Arrays.binarySearch(longElements, l);
                    if (insertPos >= 0) yield this;  // Already exists
                    insertPos = -(insertPos + 1);

                    long[] newElements = new long[newSize];
                    System.arraycopy(longElements, 0, newElements, 0, insertPos);
                    newElements[insertPos] = l;
                    System.arraycopy(longElements, insertPos, newElements, insertPos + 1, size - insertPos);
                    yield new MezzeSet(newElements, true);
                }
                yield transitionToHashAndAdd(element);
            }
            case STRING -> {
                if (element instanceof String s) {
                    String[] newElements = Arrays.copyOf(stringElements, newSize);
                    newElements[size] = s;
                    yield new MezzeSet(newElements);
                }
                yield transitionToHashAndAdd(element);
            }
            case OBJECT -> {
                Set<Object> newSet = new LinkedHashSet<>(hashSet);
                newSet.add(element);
                yield new MezzeSet(newSet);
            }
            default -> singleton(element);
        };
    }

    private MezzeSet transitionToHashAndAdd(Object element) {
        Set<Object> newSet = new LinkedHashSet<>();

        switch (strategy) {
            case SMALL -> newSet.addAll(Arrays.asList(smallElements));
            case LONG -> {
                for (long l : longElements) newSet.add(l);
            }
            case STRING -> newSet.addAll(Arrays.asList(stringElements));
            case OBJECT -> newSet.addAll(hashSet);
            default -> {}
        }

        newSet.add(element);
        return new MezzeSet(newSet);
    }

    /**
     * Remove element - returns new set (COW).
     */
    @TruffleBoundary
    public MezzeSet remove(Object element) {
        if (!contains(element)) return this;

        if (size == 1) return EMPTY_SET;

        return switch (strategy) {
            case SMALL -> removeFromSmall(element);
            case LONG -> removeFromLong(element);
            case STRING -> removeFromString(element);
            case OBJECT -> {
                Set<Object> newSet = new LinkedHashSet<>(hashSet);
                newSet.remove(element);
                yield new MezzeSet(newSet);
            }
            default -> this;
        };
    }

    private MezzeSet removeFromSmall(Object element) {
        int index = -1;
        for (int i = 0; i < size; i++) {
            if (java.util.Objects.equals(smallElements[i], element)) {
                index = i;
                break;
            }
        }
        if (index < 0) return this;

        Object[] newElements = new Object[size - 1];
        System.arraycopy(smallElements, 0, newElements, 0, index);
        System.arraycopy(smallElements, index + 1, newElements, index, size - index - 1);
        return new MezzeSet(newElements);
    }

    private MezzeSet removeFromLong(Object element) {
        if (!(element instanceof Long l)) return this;
        int index = Arrays.binarySearch(longElements, l);
        if (index < 0) return this;

        long[] newElements = new long[size - 1];
        System.arraycopy(longElements, 0, newElements, 0, index);
        System.arraycopy(longElements, index + 1, newElements, index, size - index - 1);
        return new MezzeSet(newElements, true);
    }

    private MezzeSet removeFromString(Object element) {
        if (!(element instanceof String s)) return this;
        int index = -1;
        for (int i = 0; i < size; i++) {
            if (stringElements[i].equals(s)) {
                index = i;
                break;
            }
        }
        if (index < 0) return this;

        String[] newElements = new String[size - 1];
        System.arraycopy(stringElements, 0, newElements, 0, index);
        System.arraycopy(stringElements, index + 1, newElements, index, size - index - 1);
        return new MezzeSet(newElements);
    }

    // ===== SET OPERATIONS =====

    /**
     * Union of two sets.
     */
    @TruffleBoundary
    public MezzeSet union(MezzeSet other) {
        if (this.isEmpty()) return other;
        if (other.isEmpty()) return this;

        // Fast path: same LONG strategy
        if (this.strategy == Strategy.LONG && other.strategy == Strategy.LONG) {
            return unionLong(other);
        }

        // General case
        Set<Object> result = new LinkedHashSet<>();
        addAllTo(this, result);
        addAllTo(other, result);
        return fromArray(result.toArray());
    }

    private MezzeSet unionLong(MezzeSet other) {
        // Merge sorted arrays
        long[] merged = new long[this.size + other.size];
        int i = 0, j = 0, k = 0;

        while (i < this.size && j < other.size) {
            if (this.longElements[i] < other.longElements[j]) {
                merged[k++] = this.longElements[i++];
            } else if (this.longElements[i] > other.longElements[j]) {
                merged[k++] = other.longElements[j++];
            } else {
                merged[k++] = this.longElements[i++];
                j++;
            }
        }
        while (i < this.size) merged[k++] = this.longElements[i++];
        while (j < other.size) merged[k++] = other.longElements[j++];

        return new MezzeSet(Arrays.copyOf(merged, k), true);
    }

    /**
     * Intersection of two sets.
     */
    @TruffleBoundary
    public MezzeSet intersection(MezzeSet other) {
        if (this.isEmpty() || other.isEmpty()) return EMPTY_SET;

        // Fast path: same LONG strategy
        if (this.strategy == Strategy.LONG && other.strategy == Strategy.LONG) {
            return intersectionLong(other);
        }

        // General case
        Set<Object> result = new LinkedHashSet<>();
        MezzeSet smaller = this.size <= other.size ? this : other;
        MezzeSet larger = this.size <= other.size ? other : this;

        for (Object e : smaller.toArray()) {
            if (larger.contains(e)) result.add(e);
        }
        return fromArray(result.toArray());
    }

    private MezzeSet intersectionLong(MezzeSet other) {
        long[] result = new long[Math.min(this.size, other.size)];
        int i = 0, j = 0, k = 0;

        while (i < this.size && j < other.size) {
            if (this.longElements[i] < other.longElements[j]) {
                i++;
            } else if (this.longElements[i] > other.longElements[j]) {
                j++;
            } else {
                result[k++] = this.longElements[i++];
                j++;
            }
        }

        if (k == 0) return EMPTY_SET;
        return new MezzeSet(Arrays.copyOf(result, k), true);
    }

    /**
     * Difference: this - other.
     */
    @TruffleBoundary
    public MezzeSet difference(MezzeSet other) {
        if (this.isEmpty()) return EMPTY_SET;
        if (other.isEmpty()) return this;

        Set<Object> result = new LinkedHashSet<>();
        for (Object e : this.toArray()) {
            if (!other.contains(e)) result.add(e);
        }
        return fromArray(result.toArray());
    }

    // ===== CONVERSION =====

    /**
     * Convert to array.
     */
    @TruffleBoundary
    public Object[] toArray() {
        return switch (strategy) {
            case EMPTY -> new Object[0];
            case SMALL -> smallElements.clone();
            case LONG -> {
                Object[] arr = new Object[size];
                for (int i = 0; i < size; i++) arr[i] = longElements[i];
                yield arr;
            }
            case STRING -> stringElements.clone();
            case OBJECT -> hashSet.toArray();
        };
    }

    /**
     * Get as primitive long array (only for LONG strategy).
     */
    public long[] toLongArray() {
        if (strategy == Strategy.LONG) {
            return longElements.clone();
        }
        throw new IllegalStateException("Not a long set");
    }

    private static void addAllTo(MezzeSet set, Set<Object> dest) {
        switch (set.strategy) {
            case SMALL -> dest.addAll(Arrays.asList(set.smallElements));
            case LONG -> {
                for (long l : set.longElements) dest.add(l);
            }
            case STRING -> dest.addAll(Arrays.asList(set.stringElements));
            case OBJECT -> dest.addAll(set.hashSet);
            default -> {}
        }
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return size;
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < size;
    }

    @ExportMessage
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (index < 0 || index >= size) {
            throw InvalidArrayIndexException.create(index);
        }
        int i = (int) index;
        return switch (strategy) {
            case SMALL -> smallElements[i];
            case LONG -> longElements[i];
            case STRING -> stringElements[i];
            case OBJECT -> toArray()[i];
            default -> throw InvalidArrayIndexException.create(index);
        };
    }

    @ExportMessage
    boolean isArrayElementModifiable(long index) {
        return false;
    }

    @ExportMessage
    boolean isArrayElementInsertable(long index) {
        return false;
    }

    @ExportMessage
    void writeArrayElement(long index, Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public String toString() {
        if (isEmpty()) return "#{}";
        StringBuilder sb = new StringBuilder("#{");
        Object[] arr = toArray();
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            sb.append(arr[i]);
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    @TruffleBoundary
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MezzeSet that)) return false;
        if (this.size != that.size) return false;

        for (Object e : this.toArray()) {
            if (!that.contains(e)) return false;
        }
        return true;
    }

    @Override
    @TruffleBoundary
    public int hashCode() {
        int result = 0;
        for (Object e : toArray()) {
            result += java.util.Objects.hashCode(e);
        }
        return result;
    }
}
