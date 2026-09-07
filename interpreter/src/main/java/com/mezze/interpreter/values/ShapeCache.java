package com.mezze.interpreter.values;

import com.mezze.interpreter.values.MezzeRecord.Shape;
import com.mezze.interpreter.values.MezzeRecord.FieldLocation;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Global cache of Shape objects for MezzeRecord.
 *
 * Shape caching enables:
 * 1. Identity comparison (==) for shape checks instead of structural comparison
 * 2. Reduced memory usage by sharing shapes across records
 * 3. Better JIT optimization via stable shape references
 *
 * Shapes are keyed by a canonical representation of their field structure.
 */
public final class ShapeCache {

    private static final ConcurrentHashMap<ShapeKey, Shape> CACHE = new ConcurrentHashMap<>();

    private ShapeCache() {}

    /**
     * Get or create a Shape for the given field structure.
     * Returns a cached Shape if one exists with identical structure.
     */
    @TruffleBoundary
    public static Shape getOrCreate(String[] fieldNames, FieldLocation[] fieldLocations) {
        ShapeKey key = new ShapeKey(fieldNames, fieldLocations);
        return CACHE.computeIfAbsent(key, k -> new Shape(fieldNames, fieldLocations));
    }

    /**
     * Get or create a Shape where all fields use OBJECT storage.
     * This is the most common case for dynamic records.
     */
    @TruffleBoundary
    public static Shape getOrCreateAllObjects(String[] fieldNames) {
        FieldLocation[] locations = new FieldLocation[fieldNames.length];
        for (int i = 0; i < fieldNames.length; i++) {
            locations[i] = new FieldLocation(FieldLocation.StorageType.OBJECT, i);
        }
        return getOrCreate(fieldNames, locations);
    }

    /**
     * Get or create a Shape where the single field uses LONG storage.
     * Used by MakeSingleFieldRecord to keep integer values unboxed.
     */
    @TruffleBoundary
    public static Shape getOrCreateSingleLong(String[] fieldNames) {
        assert fieldNames.length == 1 : "getOrCreateSingleLong requires exactly one field";
        FieldLocation[] locations = new FieldLocation[]{
            new FieldLocation(FieldLocation.StorageType.LONG, 0)
        };
        return getOrCreate(fieldNames, locations);
    }

    /**
     * Key for shape lookup - based on field names and storage types.
     */
    private static final class ShapeKey {
        private final String[] fieldNames;
        private final int[] storageTypes;  // Encoded as int ordinals
        private final int[] storageIndices;
        private final int hashCode;

        ShapeKey(String[] fieldNames, FieldLocation[] locations) {
            this.fieldNames = fieldNames;
            this.storageTypes = new int[locations.length];
            this.storageIndices = new int[locations.length];

            int hash = 1;
            for (int i = 0; i < locations.length; i++) {
                storageTypes[i] = locations[i].storageType.ordinal();
                storageIndices[i] = locations[i].index;
                hash = 31 * hash + fieldNames[i].hashCode();
                hash = 31 * hash + storageTypes[i];
                hash = 31 * hash + storageIndices[i];
            }
            this.hashCode = hash;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ShapeKey that)) return false;

            return Arrays.equals(fieldNames, that.fieldNames)
                && Arrays.equals(storageTypes, that.storageTypes)
                && Arrays.equals(storageIndices, that.storageIndices);
        }
    }

    /**
     * Clear the cache (useful for testing).
     */
    @TruffleBoundary
    public static void clear() {
        CACHE.clear();
    }

    /**
     * Get cache size (for diagnostics).
     */
    public static int size() {
        return CACHE.size();
    }
}
