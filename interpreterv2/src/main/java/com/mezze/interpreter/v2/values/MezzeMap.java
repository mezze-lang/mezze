package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.StopIterationException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownKeyException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * AGGRESSIVELY OPTIMIZED immutable map for Mezze.
 *
 * Uses multiple storage strategies based on key types:
 * - EMPTY: Singleton empty map
 * - SMALL: Linear array for size <= 8 (cache-friendly, no hashing overhead)
 * - LONG_KEYS: Specialized for Int/Nat keys - primitive long[] for keys
 * - STRING_KEYS: Specialized for String keys - interned string comparison
 * - OBJECT: General hash-based storage
 *
 * Key optimizations:
 * 1. Storage strategy selection for optimal access patterns
 * 2. Small map optimization (linear scan beats hashing for n <= 8)
 * 3. Primitive key arrays to avoid boxing
 * 4. Inline caching hints via key shape tracking
 * 5. Copy-on-write for persistent operations
 *
 * Inspired by TruffleRuby's Hash implementation.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeMap implements TruffleObject {

    // ===== STORAGE STRATEGIES =====

    public enum Strategy {
        EMPTY,       // No entries
        SMALL,       // Linear array, size <= SMALL_THRESHOLD
        LONG_KEYS,   // Int/Nat keys - long[] for keys
        STRING_KEYS, // String keys - optimized string comparison
        OBJECT       // General hash-based
    }

    private static final int SMALL_THRESHOLD = 8;

    // Singleton empty map
    private static final MezzeMap EMPTY_MAP = new MezzeMap();

    @CompilationFinal
    private final Strategy strategy;

    @CompilationFinal
    private final int size;

    // SMALL strategy storage
    @CompilationFinal(dimensions = 1)
    private final Object[] smallKeys;

    @CompilationFinal(dimensions = 1)
    private final Object[] smallValues;

    // LONG_KEYS strategy storage
    @CompilationFinal(dimensions = 1)
    private final long[] longKeys;

    @CompilationFinal(dimensions = 1)
    private final Object[] longKeyValues;

    // STRING_KEYS strategy storage
    @CompilationFinal(dimensions = 1)
    private final String[] stringKeys;

    @CompilationFinal(dimensions = 1)
    private final Object[] stringKeyValues;

    // OBJECT strategy storage (hash-based)
    private final Map<Object, Object> hashMap;

    // ===== CONSTRUCTORS =====

    /** Empty map */
    private MezzeMap() {
        this.strategy = Strategy.EMPTY;
        this.size = 0;
        this.smallKeys = null;
        this.smallValues = null;
        this.longKeys = null;
        this.longKeyValues = null;
        this.stringKeys = null;
        this.stringKeyValues = null;
        this.hashMap = null;
    }

    /** Small map (linear array) */
    private MezzeMap(Object[] keys, Object[] values) {
        this.strategy = Strategy.SMALL;
        this.size = keys.length;
        this.smallKeys = keys;
        this.smallValues = values;
        this.longKeys = null;
        this.longKeyValues = null;
        this.stringKeys = null;
        this.stringKeyValues = null;
        this.hashMap = null;
    }

    /** Long keys map */
    private MezzeMap(long[] keys, Object[] values, boolean isLongKeys) {
        this.strategy = Strategy.LONG_KEYS;
        this.size = keys.length;
        this.smallKeys = null;
        this.smallValues = null;
        this.longKeys = keys;
        this.longKeyValues = values;
        this.stringKeys = null;
        this.stringKeyValues = null;
        this.hashMap = null;
    }

    /** String keys map */
    private MezzeMap(String[] keys, Object[] values) {
        this.strategy = Strategy.STRING_KEYS;
        this.size = keys.length;
        this.smallKeys = null;
        this.smallValues = null;
        this.longKeys = null;
        this.longKeyValues = null;
        this.stringKeys = keys;
        this.stringKeyValues = values;
        this.hashMap = null;
    }

    /** Hash-based map */
    private MezzeMap(Map<Object, Object> map) {
        this.strategy = Strategy.OBJECT;
        this.size = map.size();
        this.smallKeys = null;
        this.smallValues = null;
        this.longKeys = null;
        this.longKeyValues = null;
        this.stringKeys = null;
        this.stringKeyValues = null;
        this.hashMap = map;
    }

    // ===== FACTORY METHODS =====

    public static MezzeMap empty() {
        return EMPTY_MAP;
    }

    /**
     * Create from parallel arrays of keys and values.
     * Automatically selects best storage strategy.
     */
    @TruffleBoundary
    public static MezzeMap fromArrays(Object[] keys, Object[] values) {
        if (keys.length == 0) return EMPTY_MAP;
        if (keys.length != values.length) {
            throw new IllegalArgumentException("Keys and values must have same length");
        }

        // Analyze key types
        boolean allLong = true;
        boolean allString = true;
        for (Object key : keys) {
            if (!(key instanceof Long)) allLong = false;
            if (!(key instanceof String)) allString = false;
        }

        // Small map optimization
        if (keys.length <= SMALL_THRESHOLD) {
            return new MezzeMap(keys.clone(), values.clone());
        }

        // Specialized storage
        if (allLong) {
            long[] longKeys = new long[keys.length];
            for (int i = 0; i < keys.length; i++) {
                longKeys[i] = (Long) keys[i];
            }
            return new MezzeMap(longKeys, values.clone(), true);
        }

        if (allString) {
            String[] stringKeys = new String[keys.length];
            for (int i = 0; i < keys.length; i++) {
                stringKeys[i] = (String) keys[i];
            }
            return new MezzeMap(stringKeys, values.clone());
        }

        // Fall back to hash map
        Map<Object, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++) {
            map.put(keys[i], values[i]);
        }
        return new MezzeMap(map);
    }

    /**
     * Create from Java Map.
     */
    @TruffleBoundary
    public static MezzeMap fromMap(Map<?, ?> map) {
        if (map.isEmpty()) return EMPTY_MAP;

        Object[] keys = map.keySet().toArray();
        Object[] values = map.values().toArray();
        return fromArrays(keys, values);
    }

    /**
     * Create a singleton map.
     */
    public static MezzeMap singleton(Object key, Object value) {
        if (key instanceof Long l) {
            return new MezzeMap(new long[]{l}, new Object[]{value}, true);
        } else if (key instanceof String s) {
            return new MezzeMap(new String[]{s}, new Object[]{value});
        } else {
            return new MezzeMap(new Object[]{key}, new Object[]{value});
        }
    }

    /**
     * Create from long keys - SKIPS TYPE ANALYSIS.
     * Use this when all keys are known to be Int/Nat.
     */
    public static MezzeMap fromLongKeys(long[] keys, Object[] values) {
        if (keys.length == 0) return EMPTY_MAP;
        if (keys.length != values.length) {
            throw new IllegalArgumentException("Keys and values must have same length");
        }
        if (keys.length <= SMALL_THRESHOLD) {
            // Small map - use generic storage for simplicity
            Object[] boxedKeys = new Object[keys.length];
            for (int i = 0; i < keys.length; i++) {
                boxedKeys[i] = keys[i];
            }
            return new MezzeMap(boxedKeys, values.clone());
        }
        return new MezzeMap(keys.clone(), values.clone(), true);
    }

    /**
     * Create from string keys - SKIPS TYPE ANALYSIS.
     * Use this when all keys are known to be Strings.
     */
    public static MezzeMap fromStringKeys(String[] keys, Object[] values) {
        if (keys.length == 0) return EMPTY_MAP;
        if (keys.length != values.length) {
            throw new IllegalArgumentException("Keys and values must have same length");
        }
        if (keys.length <= SMALL_THRESHOLD) {
            // Small map - use generic storage for simplicity
            return new MezzeMap(keys.clone(), values.clone());
        }
        return new MezzeMap(keys.clone(), values.clone());
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
     * Get value by key - returns null if not found.
     */
    public Object get(Object key) {
        return switch (strategy) {
            case EMPTY -> null;
            case SMALL -> getSmall(key);
            case LONG_KEYS -> getLongKey(key);
            case STRING_KEYS -> getStringKey(key);
            case OBJECT -> getHash(key);
        };
    }

    private Object getSmall(Object key) {
        for (int i = 0; i < size; i++) {
            if (Objects.equals(smallKeys[i], key)) {
                return smallValues[i];
            }
        }
        return null;
    }

    private Object getLongKey(Object key) {
        if (!(key instanceof Long l)) return null;
        long target = l;
        for (int i = 0; i < size; i++) {
            if (longKeys[i] == target) {
                return longKeyValues[i];
            }
        }
        return null;
    }

    private Object getStringKey(Object key) {
        if (!(key instanceof String s)) return null;
        for (int i = 0; i < size; i++) {
            if (stringKeys[i].equals(s)) {
                return stringKeyValues[i];
            }
        }
        return null;
    }

    @TruffleBoundary
    private Object getHash(Object key) {
        return hashMap.get(key);
    }

    /**
     * Get value by long key - NO BOXING for LONG_KEYS strategy.
     */
    public Object getByLong(long key) {
        if (strategy == Strategy.LONG_KEYS) {
            for (int i = 0; i < size; i++) {
                if (longKeys[i] == key) {
                    return longKeyValues[i];
                }
            }
            return null;
        }
        return get(key);
    }

    /**
     * Get value by string key - fast path for STRING_KEYS strategy.
     * Single-pass scan with identity check for interned strings.
     */
    public Object getByString(String key) {
        if (strategy == Strategy.STRING_KEYS) {
            // Single pass - check identity first, then equality
            for (int i = 0; i < size; i++) {
                String k = stringKeys[i];
                if (k == key || k.equals(key)) {
                    return stringKeyValues[i];
                }
            }
            return null;
        }
        return get(key);
    }

    /**
     * Check if key exists.
     */
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    // ===== PERSISTENT OPERATIONS =====

    /**
     * Put key-value pair - returns new map (COW).
     */
    @TruffleBoundary
    public MezzeMap put(Object key, Object value) {
        if (strategy == Strategy.EMPTY) {
            return singleton(key, value);
        }

        // Check if key exists and update
        int existingIndex = findKeyIndex(key);
        if (existingIndex >= 0) {
            return updateAt(key, existingIndex, value);
        }

        // Add new key-value pair
        return addEntry(key, value);
    }

    private int findKeyIndex(Object key) {
        return switch (strategy) {
            case EMPTY -> -1;
            case SMALL -> {
                for (int i = 0; i < size; i++) {
                    if (Objects.equals(smallKeys[i], key)) yield i;
                }
                yield -1;
            }
            case LONG_KEYS -> {
                if (!(key instanceof Long l)) yield -1;
                long target = l;
                for (int i = 0; i < size; i++) {
                    if (longKeys[i] == target) yield i;
                }
                yield -1;
            }
            case STRING_KEYS -> {
                if (!(key instanceof String s)) yield -1;
                for (int i = 0; i < size; i++) {
                    if (stringKeys[i] == s || stringKeys[i].equals(s)) yield i;
                }
                yield -1;
            }
            case OBJECT -> hashMap.containsKey(key) ? 0 : -1;  // Index not used for OBJECT
        };
    }

    /**
     * Update value at index - key is needed for OBJECT strategy.
     */
    private MezzeMap updateAt(Object key, int index, Object value) {
        return switch (strategy) {
            case SMALL -> {
                Object[] newValues = smallValues.clone();
                newValues[index] = value;
                yield new MezzeMap(smallKeys.clone(), newValues);
            }
            case LONG_KEYS -> {
                Object[] newValues = longKeyValues.clone();
                newValues[index] = value;
                yield new MezzeMap(longKeys.clone(), newValues, true);
            }
            case STRING_KEYS -> {
                Object[] newValues = stringKeyValues.clone();
                newValues[index] = value;
                yield new MezzeMap(stringKeys.clone(), newValues);
            }
            case OBJECT -> {
                Map<Object, Object> newMap = new LinkedHashMap<>(hashMap);
                newMap.put(key, value);  // Properly update with the actual key
                yield new MezzeMap(newMap);
            }
            default -> this;
        };
    }

    private MezzeMap addEntry(Object key, Object value) {
        int newSize = size + 1;

        // Transition to hash if too large
        if (newSize > SMALL_THRESHOLD && strategy != Strategy.OBJECT) {
            return transitionToHashAndAdd(key, value);
        }

        return switch (strategy) {
            case SMALL -> {
                // Check if new key matches current key type pattern
                Object[] newKeys = Arrays.copyOf(smallKeys, newSize);
                Object[] newValues = Arrays.copyOf(smallValues, newSize);
                newKeys[size] = key;
                newValues[size] = value;
                yield new MezzeMap(newKeys, newValues);
            }
            case LONG_KEYS -> {
                if (key instanceof Long l) {
                    long[] newKeys = Arrays.copyOf(longKeys, newSize);
                    Object[] newValues = Arrays.copyOf(longKeyValues, newSize);
                    newKeys[size] = l;
                    newValues[size] = value;
                    yield new MezzeMap(newKeys, newValues, true);
                }
                yield transitionToHashAndAdd(key, value);
            }
            case STRING_KEYS -> {
                if (key instanceof String s) {
                    String[] newKeys = Arrays.copyOf(stringKeys, newSize);
                    Object[] newValues = Arrays.copyOf(stringKeyValues, newSize);
                    newKeys[size] = s;
                    newValues[size] = value;
                    yield new MezzeMap(newKeys, newValues);
                }
                yield transitionToHashAndAdd(key, value);
            }
            case OBJECT -> {
                Map<Object, Object> newMap = new LinkedHashMap<>(hashMap);
                newMap.put(key, value);
                yield new MezzeMap(newMap);
            }
            default -> singleton(key, value);
        };
    }

    private MezzeMap transitionToHashAndAdd(Object key, Object value) {
        Map<Object, Object> newMap = new LinkedHashMap<>();

        // Copy existing entries
        switch (strategy) {
            case SMALL -> {
                for (int i = 0; i < size; i++) {
                    newMap.put(smallKeys[i], smallValues[i]);
                }
            }
            case LONG_KEYS -> {
                for (int i = 0; i < size; i++) {
                    newMap.put(longKeys[i], longKeyValues[i]);
                }
            }
            case STRING_KEYS -> {
                for (int i = 0; i < size; i++) {
                    newMap.put(stringKeys[i], stringKeyValues[i]);
                }
            }
            case OBJECT -> newMap.putAll(hashMap);
            default -> {}
        }

        newMap.put(key, value);
        return new MezzeMap(newMap);
    }

    /**
     * Remove key - returns new map (COW).
     */
    @TruffleBoundary
    public MezzeMap remove(Object key) {
        int index = findKeyIndex(key);
        if (index < 0) return this;  // Key not found

        if (size == 1) return EMPTY_MAP;

        return switch (strategy) {
            case SMALL -> removeFromSmall(index);
            case LONG_KEYS -> removeFromLongKeys(index);
            case STRING_KEYS -> removeFromStringKeys(index);
            case OBJECT -> {
                Map<Object, Object> newMap = new LinkedHashMap<>(hashMap);
                newMap.remove(key);
                yield new MezzeMap(newMap);
            }
            default -> this;
        };
    }

    private MezzeMap removeFromSmall(int index) {
        Object[] newKeys = new Object[size - 1];
        Object[] newValues = new Object[size - 1];
        System.arraycopy(smallKeys, 0, newKeys, 0, index);
        System.arraycopy(smallKeys, index + 1, newKeys, index, size - index - 1);
        System.arraycopy(smallValues, 0, newValues, 0, index);
        System.arraycopy(smallValues, index + 1, newValues, index, size - index - 1);
        return new MezzeMap(newKeys, newValues);
    }

    private MezzeMap removeFromLongKeys(int index) {
        long[] newKeys = new long[size - 1];
        Object[] newValues = new Object[size - 1];
        System.arraycopy(longKeys, 0, newKeys, 0, index);
        System.arraycopy(longKeys, index + 1, newKeys, index, size - index - 1);
        System.arraycopy(longKeyValues, 0, newValues, 0, index);
        System.arraycopy(longKeyValues, index + 1, newValues, index, size - index - 1);
        return new MezzeMap(newKeys, newValues, true);
    }

    private MezzeMap removeFromStringKeys(int index) {
        String[] newKeys = new String[size - 1];
        Object[] newValues = new Object[size - 1];
        System.arraycopy(stringKeys, 0, newKeys, 0, index);
        System.arraycopy(stringKeys, index + 1, newKeys, index, size - index - 1);
        System.arraycopy(stringKeyValues, 0, newValues, 0, index);
        System.arraycopy(stringKeyValues, index + 1, newValues, index, size - index - 1);
        return new MezzeMap(newKeys, newValues);
    }

    // ===== ITERATION =====

    /**
     * Get all keys as array.
     */
    @TruffleBoundary
    public Object[] keys() {
        return switch (strategy) {
            case EMPTY -> new Object[0];
            case SMALL -> smallKeys.clone();
            case LONG_KEYS -> {
                Object[] keys = new Object[size];
                for (int i = 0; i < size; i++) keys[i] = longKeys[i];
                yield keys;
            }
            case STRING_KEYS -> stringKeys.clone();
            case OBJECT -> hashMap.keySet().toArray();
        };
    }

    /**
     * Get all values as array.
     */
    @TruffleBoundary
    public Object[] values() {
        return switch (strategy) {
            case EMPTY -> new Object[0];
            case SMALL -> smallValues.clone();
            case LONG_KEYS -> longKeyValues.clone();
            case STRING_KEYS -> stringKeyValues.clone();
            case OBJECT -> hashMap.values().toArray();
        };
    }

    /**
     * Convert to Java Map.
     */
    @TruffleBoundary
    public Map<Object, Object> toMap() {
        Map<Object, Object> result = new LinkedHashMap<>();
        switch (strategy) {
            case SMALL -> {
                for (int i = 0; i < size; i++) {
                    result.put(smallKeys[i], smallValues[i]);
                }
            }
            case LONG_KEYS -> {
                for (int i = 0; i < size; i++) {
                    result.put(longKeys[i], longKeyValues[i]);
                }
            }
            case STRING_KEYS -> {
                for (int i = 0; i < size; i++) {
                    result.put(stringKeys[i], stringKeyValues[i]);
                }
            }
            case OBJECT -> result.putAll(hashMap);
            default -> {}
        }
        return result;
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasHashEntries() {
        return true;
    }

    @ExportMessage
    long getHashSize() {
        return size;
    }

    @ExportMessage
    @TruffleBoundary
    boolean isHashEntryReadable(Object key) {
        return containsKey(key);
    }

    @ExportMessage
    @TruffleBoundary
    Object readHashValue(Object key) throws UnknownKeyException {
        Object value = get(key);
        if (value == null && !containsKey(key)) {
            throw UnknownKeyException.create(key);
        }
        return value;
    }

    @ExportMessage
    boolean isHashEntryModifiable(Object key) {
        return false;  // Immutable
    }

    @ExportMessage
    boolean isHashEntryInsertable(Object key) {
        return false;  // Immutable
    }

    @ExportMessage
    void writeHashEntry(Object key, Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();  // Immutable
    }

    @ExportMessage
    @TruffleBoundary
    Object getHashEntriesIterator() {
        return new MapEntriesIterator(this);
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public String toString() {
        if (isEmpty()) return "{}";
        // Pre-size StringBuilder: "{" + (size * ~30 chars per entry) + "}"
        StringBuilder sb = new StringBuilder(Math.max(16, 2 + size * 30));
        sb.append("{");
        Object[] ks = keys();
        Object[] vs = values();
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            sb.append(ks[i]).append(": ").append(vs[i]);
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    @TruffleBoundary
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MezzeMap that)) return false;
        if (this.size != that.size) return false;

        Object[] ks = keys();
        for (Object key : ks) {
            Object thisValue = this.get(key);
            Object thatValue = that.get(key);
            if (!Objects.equals(thisValue, thatValue)) return false;
        }
        return true;
    }

    @Override
    @TruffleBoundary
    public int hashCode() {
        int result = 0;
        Object[] ks = keys();
        Object[] vs = values();
        for (int i = 0; i < size; i++) {
            result += Objects.hashCode(ks[i]) ^ Objects.hashCode(vs[i]);
        }
        return result;
    }

    // ===== ITERATOR =====

    @ExportLibrary(InteropLibrary.class)
    static final class MapEntriesIterator implements TruffleObject {
        private final MezzeMap map;
        private final Object[] keys;
        private final Object[] values;
        private int index;

        MapEntriesIterator(MezzeMap map) {
            this.map = map;
            this.keys = map.keys();
            this.values = map.values();
            this.index = 0;
        }

        @ExportMessage
        boolean isIterator() {
            return true;
        }

        @ExportMessage
        boolean hasIteratorNextElement() {
            return index < keys.length;
        }

        @ExportMessage
        @TruffleBoundary
        Object getIteratorNextElement() throws StopIterationException {
            if (index >= keys.length) {
                throw StopIterationException.create();
            }
            Object[] entry = new Object[]{keys[index], values[index]};
            index++;
            return new MapEntry(entry[0], entry[1]);
        }
    }

    @ExportLibrary(InteropLibrary.class)
    static final class MapEntry implements TruffleObject {
        private final Object key;
        private final Object value;

        MapEntry(Object key, Object value) {
            this.key = key;
            this.value = value;
        }

        @ExportMessage
        boolean hasArrayElements() {
            return true;
        }

        @ExportMessage
        long getArraySize() {
            return 2;
        }

        @ExportMessage
        boolean isArrayElementReadable(long index) {
            return index == 0 || index == 1;
        }

        @ExportMessage
        Object readArrayElement(long index) {
            return index == 0 ? key : value;
        }
    }
}
