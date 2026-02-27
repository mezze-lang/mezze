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
     */
    public static final class Shape {
        @CompilationFinal(dimensions = 1)
        private final String[] fieldNames;

        @CompilationFinal(dimensions = 1)
        private final FieldLocation[] fieldLocations;

        // Pre-computed for InteropLibrary
        @CompilationFinal(dimensions = 1)
        private final String[] memberNames;

        public Shape(String[] fieldNames, FieldLocation[] fieldLocations) {
            this.fieldNames = fieldNames;
            this.fieldLocations = fieldLocations;
            this.memberNames = fieldNames.clone();
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

        @TruffleBoundary
        public int findFieldIndex(String name) {
            for (int i = 0; i < fieldNames.length; i++) {
                if (fieldNames[i].equals(name)) {
                    return i;
                }
            }
            return -1;
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
     */
    public MezzeRecord withField(int fieldIndex, Object value) {
        FieldLocation loc = shape.getFieldLocation(fieldIndex);
        return switch (loc.storageType) {
            case LONG -> {
                long[] newLongs = longFields.clone();
                newLongs[loc.index] = (long) value;
                yield new MezzeRecord(shape, newLongs, doubleFields, objectFields);
            }
            case DOUBLE -> {
                double[] newDoubles = doubleFields.clone();
                newDoubles[loc.index] = (double) value;
                yield new MezzeRecord(shape, longFields, newDoubles, objectFields);
            }
            case OBJECT -> {
                Object[] newObjects = objectFields.clone();
                newObjects[loc.index] = value;
                yield new MezzeRecord(shape, longFields, doubleFields, newObjects);
            }
        };
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
