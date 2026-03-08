package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * Optimized record representation for Mezze v2.
 *
 * Records use array-based storage with compile-time field indices:
 * - longFields: long[] for Int fields (unboxed)
 * - doubleFields: double[] for Float fields (unboxed)
 * - objectFields: Object[] for all other fields
 *
 * The shape contains the mapping from field names to indices.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeRecord implements TruffleObject {

    /**
     * Shape defines the field layout - shared across records with same structure.
     *
     * AGGRESSIVE OPTIMIZATIONS:
     * 1. Uses regular HashMap instead of ConcurrentHashMap (no atomic operations)
     *    - Shapes are immutable after creation, so thread-safety is guaranteed
     *    - HashMap.get() is ~3x faster than ConcurrentHashMap.get()
     * 2. Pre-populated cache at construction (O(n) once, O(1) lookups thereafter)
     * 3. Small shape fast path (≤5 fields) with JIT-unrollable linear scan
     * 4. Sentinel optimization for not-found case (avoids Integer unboxing)
     */
    public static final class Shape {
        private static final int SMALL_SHAPE_THRESHOLD = 5;

        @CompilationFinal(dimensions = 1)
        private final String[] fieldNames;

        @CompilationFinal(dimensions = 1)
        private final FieldLocation[] fieldLocations;

        // Pre-computed for InteropLibrary
        @CompilationFinal(dimensions = 1)
        private final String[] memberNames;

        // OPTIMIZATION: Regular HashMap instead of ConcurrentHashMap
        // Shapes are immutable - no thread-safety concerns after construction
        // HashMap.get() avoids volatile reads and CAS operations
        private final HashMap<String, Integer> fieldIndexCache;

        // Pre-computed flag for small shape optimization
        @CompilationFinal
        private final boolean isSmallShape;

        public Shape(String[] fieldNames, FieldLocation[] fieldLocations) {
            this.fieldNames = fieldNames;
            this.fieldLocations = fieldLocations;
            this.memberNames = fieldNames.clone();
            // OPTIMIZATION: Size the HashMap exactly - no rehashing
            this.fieldIndexCache = new HashMap<>(fieldNames.length * 4 / 3 + 1);
            this.isSmallShape = fieldNames.length <= SMALL_SHAPE_THRESHOLD;

            // Pre-populate cache for all fields (O(n) once at shape creation)
            for (int i = 0; i < fieldNames.length; i++) {
                fieldIndexCache.put(fieldNames[i], i);
            }
        }

        public int getFieldCount() {
            return fieldNames.length;
        }

        public String getFieldName(int index) {
            return fieldNames[index];
        }

        public FieldLocation getFieldLocation(int index) {
            return fieldLocations[index];
        }

        /**
         * Find field index by name - O(1) via cache lookup.
         * For small shapes, we use linear scan which the JIT can unroll.
         */
        public int findFieldIndex(String name) {
            // Fast path for small shapes - JIT can unroll this loop
            if (isSmallShape) {
                return findFieldIndexSmall(name);
            }
            // Use cached lookup for larger shapes
            return findFieldIndexCached(name);
        }

        /**
         * Linear scan for small shapes - JIT can fully unroll.
         */
        private int findFieldIndexSmall(String name) {
            for (int i = 0; i < fieldNames.length; i++) {
                if (fieldNames[i].equals(name)) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * Cached lookup for larger shapes - O(1) amortized.
         */
        @TruffleBoundary
        private int findFieldIndexCached(String name) {
            Integer cached = fieldIndexCache.get(name);
            return cached != null ? cached : -1;
        }

        public String[] getMemberNames() {
            return memberNames;
        }
    }

    /**
     * Describes where a field is stored.
     */
    public static final class FieldLocation {
        public enum StorageType { LONG, DOUBLE, OBJECT }

        public final StorageType storageType;
        public final int index;

        public FieldLocation(StorageType storageType, int index) {
            this.storageType = storageType;
            this.index = index;
        }
    }

    private final Shape shape;

    // Typed storage arrays - primitives stay unboxed!
    @CompilationFinal(dimensions = 1)
    private final long[] longFields;

    @CompilationFinal(dimensions = 1)
    private final double[] doubleFields;

    @CompilationFinal(dimensions = 1)
    private final Object[] objectFields;

    public MezzeRecord(Shape shape, long[] longFields, double[] doubleFields, Object[] objectFields) {
        this.shape = shape;
        this.longFields = longFields;
        this.doubleFields = doubleFields;
        this.objectFields = objectFields;
    }

    /**
     * Create a record with all object fields (simple case).
     */
    public static MezzeRecord withAllObjects(Shape shape, Object[] values) {
        return new MezzeRecord(shape, new long[0], new double[0], values);
    }

    public Shape getShape() {
        return shape;
    }

    // Direct field access by index - no bounds checking for JIT
    public long getLongField(int locationIndex) {
        return longFields[locationIndex];
    }

    public double getDoubleField(int locationIndex) {
        return doubleFields[locationIndex];
    }

    public Object getObjectField(int locationIndex) {
        return objectFields[locationIndex];
    }

    // Field count accessors for type specialization guards
    public int getLongFieldCount() {
        return longFields.length;
    }

    public int getDoubleFieldCount() {
        return doubleFields.length;
    }

    public int getObjectFieldCount() {
        return objectFields.length;
    }

    /**
     * Get field by logical index (goes through location).
     */
    public Object getField(int fieldIndex) {
        FieldLocation loc = shape.getFieldLocation(fieldIndex);
        return switch (loc.storageType) {
            case LONG -> longFields[loc.index];
            case DOUBLE -> doubleFields[loc.index];
            case OBJECT -> objectFields[loc.index];
        };
    }

    /**
     * Get field by name (slow path - uses string lookup).
     */
    @TruffleBoundary
    public Object getFieldByName(String name) throws UnknownIdentifierException {
        int index = shape.findFieldIndex(name);
        if (index < 0) {
            throw UnknownIdentifierException.create(name);
        }
        return getField(index);
    }

    /**
     * Create a copy with one field updated.
     * OPTIMIZED: Only clones the array that needs modification.
     * Other arrays are SHARED (structural sharing) to reduce allocation.
     */
    public MezzeRecord withField(int fieldIndex, Object value) {
        FieldLocation loc = shape.getFieldLocation(fieldIndex);
        return switch (loc.storageType) {
            case LONG -> {
                long[] newLongs = longFields.clone();
                newLongs[loc.index] = (long) value;
                // OPTIMIZATION: Reuse doubleFields and objectFields (no clone)
                yield new MezzeRecord(shape, newLongs, doubleFields, objectFields);
            }
            case DOUBLE -> {
                double[] newDoubles = doubleFields.clone();
                newDoubles[loc.index] = (double) value;
                // OPTIMIZATION: Reuse longFields and objectFields (no clone)
                yield new MezzeRecord(shape, longFields, newDoubles, objectFields);
            }
            case OBJECT -> {
                Object[] newObjects = objectFields.clone();
                newObjects[loc.index] = value;
                // OPTIMIZATION: Reuse longFields and doubleFields (no clone)
                yield new MezzeRecord(shape, longFields, doubleFields, newObjects);
            }
        };
    }

    /**
     * Create a copy with multiple fields updated.
     * BATCH UPDATE: More efficient than calling withField multiple times.
     */
    public MezzeRecord withFields(int[] fieldIndices, Object[] values) {
        if (fieldIndices.length != values.length) {
            throw new IllegalArgumentException("Field indices and values must have same length");
        }
        if (fieldIndices.length == 0) {
            return this;  // No changes
        }
        if (fieldIndices.length == 1) {
            return withField(fieldIndices[0], values[0]);  // Single field fast path
        }

        // Determine which arrays need cloning
        boolean needsLongClone = false;
        boolean needsDoubleClone = false;
        boolean needsObjectClone = false;

        for (int fieldIndex : fieldIndices) {
            FieldLocation loc = shape.getFieldLocation(fieldIndex);
            switch (loc.storageType) {
                case LONG -> needsLongClone = true;
                case DOUBLE -> needsDoubleClone = true;
                case OBJECT -> needsObjectClone = true;
            }
        }

        // Clone only what's needed
        long[] newLongs = needsLongClone ? longFields.clone() : longFields;
        double[] newDoubles = needsDoubleClone ? doubleFields.clone() : doubleFields;
        Object[] newObjects = needsObjectClone ? objectFields.clone() : objectFields;

        // Apply all updates
        for (int i = 0; i < fieldIndices.length; i++) {
            FieldLocation loc = shape.getFieldLocation(fieldIndices[i]);
            Object value = values[i];
            switch (loc.storageType) {
                case LONG -> newLongs[loc.index] = (long) value;
                case DOUBLE -> newDoubles[loc.index] = (double) value;
                case OBJECT -> newObjects[loc.index] = value;
            }
        }

        return new MezzeRecord(shape, newLongs, newDoubles, newObjects);
    }

    // InteropLibrary exports
    @ExportMessage
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(boolean includeInternal) {
        return new MemberKeys(shape.getMemberNames());
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) {
        return shape.findFieldIndex(member) >= 0;
    }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String member) throws UnknownIdentifierException {
        return getFieldByName(member);
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < shape.getFieldCount(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(shape.getFieldName(i)).append(" = ").append(getField(i));
        }
        sb.append("}");
        return sb.toString();
    }

    @Override
    @TruffleBoundary
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MezzeRecord that)) return false;

        if (shape.getFieldCount() != that.shape.getFieldCount()) return false;

        for (int i = 0; i < shape.getFieldCount(); i++) {
            String name = shape.getFieldName(i);
            int otherIndex = that.shape.findFieldIndex(name);
            if (otherIndex < 0) return false;

            Object thisValue = getField(i);
            Object thatValue = that.getField(otherIndex);

            if (!java.util.Objects.equals(thisValue, thatValue)) return false;
        }
        return true;
    }

    @Override
    @TruffleBoundary
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < shape.getFieldCount(); i++) {
            result = 31 * result + shape.getFieldName(i).hashCode();
            result = 31 * result + java.util.Objects.hashCode(getField(i));
        }
        return result;
    }

    /**
     * Convert to Map for interop (slow path).
     */
    @TruffleBoundary
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < shape.getFieldCount(); i++) {
            map.put(shape.getFieldName(i), getField(i));
        }
        return map;
    }

    /**
     * Get field by name (convenience method, returns null if not found).
     */
    @TruffleBoundary
    public Object getField(String name) {
        int index = shape.findFieldIndex(name);
        if (index < 0) {
            return null;
        }
        return getField(index);
    }

    /**
     * Get field index by name. Returns -1 if not found.
     * Use this for caching field indices in hot paths.
     */
    public int getFieldIndex(String name) {
        return shape.findFieldIndex(name);
    }

    /**
     * Get the number of fields in this record.
     */
    public int getFieldCount() {
        return shape.getFieldCount();
    }

    /**
     * Create an empty record.
     */
    public static MezzeRecord empty() {
        return EMPTY;
    }

    private static final MezzeRecord EMPTY = new MezzeRecord(
        new Shape(new String[0], new FieldLocation[0]),
        new long[0],
        new double[0],
        new Object[0]
    );

    /**
     * Create a record from field names and values (all object storage).
     */
    @TruffleBoundary
    public static MezzeRecord fromFields(String[] fieldNames, Object[] values) {
        FieldLocation[] locations = new FieldLocation[fieldNames.length];
        for (int i = 0; i < fieldNames.length; i++) {
            locations[i] = new FieldLocation(FieldLocation.StorageType.OBJECT, i);
        }
        Shape shape = new Shape(fieldNames, locations);
        return new MezzeRecord(shape, new long[0], new double[0], values);
    }

    /**
     * Helper class for InteropLibrary member enumeration.
     */
    @ExportLibrary(InteropLibrary.class)
    static final class MemberKeys implements TruffleObject {
        private final String[] keys;

        MemberKeys(String[] keys) {
            this.keys = keys;
        }

        @ExportMessage
        boolean hasArrayElements() {
            return true;
        }

        @ExportMessage
        long getArraySize() {
            return keys.length;
        }

        @ExportMessage
        boolean isArrayElementReadable(long index) {
            return index >= 0 && index < keys.length;
        }

        @ExportMessage
        Object readArrayElement(long index) {
            return keys[(int) index];
        }
    }
}
