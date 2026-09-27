package com.mezze.interpreter.values;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.HostCompilerDirectives.InliningCutoff;
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
 * Optimized record representation.
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

    /**
     * Copy-on-Write ownership flag.
     *
     * When true, this record has exclusive ownership of its arrays and can
     * safely mutate them in-place during updates. After an update, ownership
     * transfers to the new record.
     *
     * This eliminates redundant array clones in update chains:
     *   {...{...{...rec, a=1}, b=2}, c=3}  // 1 clone instead of 3
     *
     * NOT @CompilationFinal because it changes at runtime.
     */
    private boolean owned;

    public MezzeRecord(Shape shape, long[] longFields, double[] doubleFields, Object[] objectFields) {
        this(shape, longFields, doubleFields, objectFields, true);
    }

    public MezzeRecord(Shape shape, long[] longFields, double[] doubleFields, Object[] objectFields, boolean owned) {
        this.shape = shape;
        this.longFields = longFields;
        this.doubleFields = doubleFields;
        this.objectFields = objectFields;
        this.owned = owned;
    }

    /**
     * Create a record with all object fields (simple case).
     * New records always start with ownership=true.
     */
    public static MezzeRecord withAllObjects(Shape shape, Object[] values) {
        return new MezzeRecord(shape, new long[0], new double[0], values, true);
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
     *
     * COPY-ON-WRITE WITH STRUCTURAL SHARING:
     * - Clones only the array being modified
     * - Shares unmodified arrays between old and new record
     * - New record gets ownership for potential future in-place updates
     *
     * Note: Optimistic in-place mutation (mutating when owned=true) was considered
     * but rejected because it violates immutability semantics - the original record
     * must always preserve its values even after creating updates.
     */
    public MezzeRecord withField(int fieldIndex, Object value) {
        FieldLocation loc = shape.getFieldLocation(fieldIndex);

        // Mark this record as shared since we're sharing unmodified arrays
        this.owned = false;

        return switch (loc.storageType) {
            case LONG -> {
                long[] newLongs = longFields.clone();
                newLongs[loc.index] = (long) value;
                // Share doubleFields and objectFields (structural sharing)
                yield new MezzeRecord(shape, newLongs, doubleFields, objectFields, true);
            }
            case DOUBLE -> {
                double[] newDoubles = doubleFields.clone();
                newDoubles[loc.index] = (double) value;
                // Share longFields and objectFields
                yield new MezzeRecord(shape, longFields, newDoubles, objectFields, true);
            }
            case OBJECT -> {
                Object[] newObjects = objectFields.clone();
                newObjects[loc.index] = value;
                // Share longFields and doubleFields
                yield new MezzeRecord(shape, longFields, doubleFields, newObjects, true);
            }
        };
    }

    /**
     * Create a copy with multiple fields updated.
     *
     * BATCH UPDATE with STRUCTURAL SHARING:
     * Only clones the arrays that need modification. Arrays that don't
     * change are shared between old and new records.
     *
     * This is the most efficient path for spread syntax:
     *   {...rec, a=1, b=2, c=3}  // Single operation, minimal cloning
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

        // Determine which arrays need modification
        boolean needsLongMod = false;
        boolean needsDoubleMod = false;
        boolean needsObjectMod = false;

        for (int fieldIndex : fieldIndices) {
            FieldLocation loc = shape.getFieldLocation(fieldIndex);
            switch (loc.storageType) {
                case LONG -> needsLongMod = true;
                case DOUBLE -> needsDoubleMod = true;
                case OBJECT -> needsObjectMod = true;
            }
        }

        // Mark this record as shared since we're sharing unmodified arrays
        this.owned = false;

        // Clone only arrays that need modification (structural sharing)
        long[] newLongs = needsLongMod ? longFields.clone() : longFields;
        double[] newDoubles = needsDoubleMod ? doubleFields.clone() : doubleFields;
        Object[] newObjects = needsObjectMod ? objectFields.clone() : objectFields;

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

        return new MezzeRecord(shape, newLongs, newDoubles, newObjects, true);
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
    @InliningCutoff
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
     *
     * Not a TruffleBoundary: {@link Shape#findFieldIndex} is non-boundary
     * for small shapes (≤ SMALL_SHAPE_THRESHOLD) so we can stay on the
     * PE-visible path. The cached lookup path remains @TruffleBoundary
     * for large shapes, which is fine — large records are rare and the
     * HashMap.get itself isn't PE-friendly anyway.
     *
     * Frees the FractionHelper hot path (getNumerator/getDenominator) from
     * the per-call PE bailout it used to take.
     */
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
     * Get field name by index. Used by hot-path inline caches (e.g.
     * YieldDispatch) that cache a per-site fieldIndex and validate
     * shape identity by comparing the cached opName against the current
     * record's name at that slot.
     */
    public String getFieldName(int index) {
        return shape.getFieldName(index);
    }

    /**
     * Check if this record owns its arrays (can mutate in-place).
     * Useful for debugging and testing CoW behavior.
     */
    public boolean isOwned() {
        return owned;
    }

    /**
     * Mark this record as shared (not owned).
     * Call this when storing a record in a data structure that might be accessed
     * from multiple places, to ensure updates create proper copies.
     *
     * Returns this record for chaining.
     */
    public MezzeRecord markShared() {
        this.owned = false;
        return this;
    }

    /**
     * Create an empty record.
     */
    public static MezzeRecord empty() {
        return EMPTY;
    }

    /**
     * Singleton empty record - NOT owned since it's shared globally.
     */
    private static final MezzeRecord EMPTY = new MezzeRecord(
        new Shape(new String[0], new FieldLocation[0]),
        new long[0],
        new double[0],
        new Object[0],
        false  // Shared singleton, never owned
    );

    /**
     * Create a record from field names and values (all object storage).
     * New records always start with ownership=true.
     */
    @TruffleBoundary
    public static MezzeRecord fromFields(String[] fieldNames, Object[] values) {
        Shape shape = ShapeCache.getOrCreateAllObjects(fieldNames);
        return new MezzeRecord(shape, EMPTY_LONGS, EMPTY_DOUBLES, values, true);
    }

    // ==================== CACHED SINGLE-FIELD RECORDS ====================
    // For map/filter/fold operations that wrap elements in {x: value}
    // Pre-compute shapes to avoid @TruffleBoundary in hot loops

    private static final Shape SHAPE_X;
    private static final Shape SHAPE_ACC_X;
    private static final Shape SHAPE_A_B;
    private static final Shape SHAPE_S;
    /** `{ it: … }` — the receiver record a one-parameter ability op takes. */
    public static final Shape SHAPE_IT;
    /** `{ it: …, r: … }` — a two-parameter ability op's record (`Eq`'s `eq`/`neq`). */
    public static final Shape SHAPE_IT_R;
    static {
        SHAPE_X = new Shape(
            new String[]{"x"},
            new FieldLocation[]{new FieldLocation(FieldLocation.StorageType.OBJECT, 0)}
        );
        SHAPE_ACC_X = new Shape(
            new String[]{"acc", "x"},
            new FieldLocation[]{
                new FieldLocation(FieldLocation.StorageType.OBJECT, 0),
                new FieldLocation(FieldLocation.StorageType.OBJECT, 1)
            }
        );
        SHAPE_A_B = new Shape(
            new String[]{"a", "b"},
            new FieldLocation[]{
                new FieldLocation(FieldLocation.StorageType.OBJECT, 0),
                new FieldLocation(FieldLocation.StorageType.OBJECT, 1)
            }
        );
        SHAPE_S = new Shape(
            new String[]{"s"},
            new FieldLocation[]{new FieldLocation(FieldLocation.StorageType.OBJECT, 0)}
        );
        SHAPE_IT = new Shape(
            new String[]{"it"},
            new FieldLocation[]{new FieldLocation(FieldLocation.StorageType.OBJECT, 0)}
        );
        SHAPE_IT_R = new Shape(
            new String[]{"it", "r"},
            new FieldLocation[]{
                new FieldLocation(FieldLocation.StorageType.OBJECT, 0),
                new FieldLocation(FieldLocation.StorageType.OBJECT, 1)
            }
        );
    }

    /**
     * Create a {x: value} record - FAST PATH for map/filter predicates.
     * Uses pre-cached shape - NO @TruffleBoundary, JIT-friendly.
     */
    public static MezzeRecord wrapX(Object value) {
        return new MezzeRecord(SHAPE_X, EMPTY_LONGS, EMPTY_DOUBLES, new Object[]{value}, true);
    }

    /**
     * Create a {acc: a, x: b} record - FAST PATH for fold operations.
     * Uses pre-cached shape - NO @TruffleBoundary, JIT-friendly.
     */
    public static MezzeRecord wrapAccX(Object acc, Object x) {
        return new MezzeRecord(SHAPE_ACC_X, EMPTY_LONGS, EMPTY_DOUBLES, new Object[]{acc, x}, true);
    }

    /**
     * Create a {a: a, b: b} record - FAST PATH for comparator operations.
     * Uses pre-cached shape - NO @TruffleBoundary, JIT-friendly.
     */
    /**
     * Create a {s: value} record - FAST PATH for iter unfold step calls.
     * Uses pre-cached shape - NO @TruffleBoundary, JIT-friendly.
     */
    public static MezzeRecord wrapS(Object value) {
        return new MezzeRecord(SHAPE_S, EMPTY_LONGS, EMPTY_DOUBLES, new Object[]{value}, true);
    }

    /** Create an `{it: value}` record — the argument shape of an ability op whose
     *  receiver is its only parameter (`hash: { it: 'a } -> Int`). */
    public static MezzeRecord wrapIt(Object value) {
        return new MezzeRecord(SHAPE_IT, EMPTY_LONGS, EMPTY_DOUBLES, new Object[]{value}, true);
    }

    /** Create an `{it: a, r: b}` record — the argument shape of a two-parameter
     *  ability op such as `Eq`'s `eq`. Distinct from {@link #wrapAB}, which is a
     *  comparator closure's `{a, b}`. */
    public static MezzeRecord wrapItR(Object a, Object b) {
        return new MezzeRecord(SHAPE_IT_R, EMPTY_LONGS, EMPTY_DOUBLES, new Object[]{a, b}, true);
    }

    public static MezzeRecord wrapAB(Object a, Object b) {
        return new MezzeRecord(SHAPE_A_B, EMPTY_LONGS, EMPTY_DOUBLES, new Object[]{a, b}, true);
    }

    // ==================== Promise capability record shapes ====================
    // For promise_from's capability record `{ complete, fail }` and its
    // sub-record args `{ val: 'a }` / `{ err }`. Pre-cached so promise_from
    // and the two capability MezzeFunctions don't cross @TruffleBoundary.

    public static final Shape SHAPE_COMPLETE_FAIL_ON_CANCEL;
    public static final Shape SHAPE_VAL;
    public static final Shape SHAPE_ERR;
    public static final Shape SHAPE_HOOK;
    public static final Shape SHAPE_REASON;
    static {
        SHAPE_COMPLETE_FAIL_ON_CANCEL = new Shape(
            new String[]{"complete", "fail", "on_cancel"},
            new FieldLocation[]{
                new FieldLocation(FieldLocation.StorageType.OBJECT, 0),
                new FieldLocation(FieldLocation.StorageType.OBJECT, 1),
                new FieldLocation(FieldLocation.StorageType.OBJECT, 2)
            }
        );
        SHAPE_VAL = new Shape(
            new String[]{"val"},
            new FieldLocation[]{new FieldLocation(FieldLocation.StorageType.OBJECT, 0)}
        );
        SHAPE_ERR = new Shape(
            new String[]{"err"},
            new FieldLocation[]{new FieldLocation(FieldLocation.StorageType.OBJECT, 0)}
        );
        SHAPE_HOOK = new Shape(
            new String[]{"hook"},
            new FieldLocation[]{new FieldLocation(FieldLocation.StorageType.OBJECT, 0)}
        );
        SHAPE_REASON = new Shape(
            new String[]{"reason"},
            new FieldLocation[]{new FieldLocation(FieldLocation.StorageType.OBJECT, 0)}
        );
    }

    /** { complete, fail, on_cancel } — FAST PATH for promise_from capability record. */
    public static MezzeRecord wrapCompleteFailOnCancel(Object complete, Object fail, Object onCancel) {
        return new MezzeRecord(SHAPE_COMPLETE_FAIL_ON_CANCEL, EMPTY_LONGS, EMPTY_DOUBLES,
            new Object[]{complete, fail, onCancel}, true);
    }

    /** { val: value } — FAST PATH for promise complete-capability arg. */
    public static MezzeRecord wrapVal(Object value) {
        return new MezzeRecord(SHAPE_VAL, EMPTY_LONGS, EMPTY_DOUBLES,
            new Object[]{value}, true);
    }

    /** { err: err } — FAST PATH for promise fail-capability arg. */
    public static MezzeRecord wrapErr(Object err) {
        return new MezzeRecord(SHAPE_ERR, EMPTY_LONGS, EMPTY_DOUBLES,
            new Object[]{err}, true);
    }

    /** { reason: reason } — FAST PATH for cancel-hook invocation arg. */
    public static MezzeRecord wrapReason(Object reason) {
        return new MezzeRecord(SHAPE_REASON, EMPTY_LONGS, EMPTY_DOUBLES,
            new Object[]{reason}, true);
    }

    // Shared empty arrays for cached wrapper records
    private static final long[] EMPTY_LONGS = new long[0];
    private static final double[] EMPTY_DOUBLES = new double[0];

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
