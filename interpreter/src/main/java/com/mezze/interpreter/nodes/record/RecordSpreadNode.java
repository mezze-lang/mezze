package com.mezze.interpreter.nodes.record;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeRecord.Shape;
import com.mezze.interpreter.values.MezzeRecord.FieldLocation;
import com.mezze.interpreter.values.MezzeRecord.FieldLocation.StorageType;
import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SUPER-OPTIMIZED record spread node for V2.
 *
 * Handles: {...rec1, ...rec2, field1 = val1, field2 = val2}
 *
 * Optimizations:
 * 1. Shape caching: Merged shapes are cached and reused
 * 2. Inline caching: Monomorphic path for single spread shape
 * 3. Typed storage: Preserves long[]/double[] without boxing
 * 4. @ExplodeLoop: JIT fully unrolls field copying
 * 5. NO @TruffleBoundary in hot execution path
 */
@NodeInfo(shortName = "record-spread")
public final class RecordSpreadNode extends MezzeNode {

    // Global cache for merged shapes (shape combination → merged shape)
    private static final ConcurrentHashMap<ShapeCacheKey, Shape> SHAPE_CACHE = new ConcurrentHashMap<>();

    @Children
    private final MezzeNode[] spreadNodes;

    @Children
    private final MezzeNode[] fieldValueNodes;

    @CompilationFinal(dimensions = 1)
    private final String[] fieldNames;

    @CompilationFinal
    private final int spreadCount;

    @CompilationFinal
    private final int fieldCount;

    // Inline cache for monomorphic spread pattern
    @CompilationFinal
    private Shape cachedSourceShape;

    @CompilationFinal
    private Shape cachedResultShape;

    @CompilationFinal
    private Assumption shapeStableAssumption;

    // Field mapping cache: for each result field, where does it come from?
    // -1 = from explicit field, >=0 = from spread source at that index
    @CompilationFinal(dimensions = 1)
    private int[] fieldSourceMapping;

    @CompilationFinal(dimensions = 1)
    private int[] fieldSourceIndex;

    // Cached storage counts for result shape (avoids counting on every call)
    @CompilationFinal
    private int cachedLongCount;

    @CompilationFinal
    private int cachedDoubleCount;

    @CompilationFinal
    private int cachedObjectCount;

    public RecordSpreadNode(MezzeNode[] spreadNodes, String[] fieldNames, MezzeNode[] fieldValueNodes) {
        this.spreadNodes = spreadNodes;
        this.fieldNames = fieldNames;
        this.fieldValueNodes = fieldValueNodes;
        this.spreadCount = spreadNodes.length;
        this.fieldCount = fieldNames.length;
        this.shapeStableAssumption = Truffle.getRuntime().createAssumption("spread-shape-stable");
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Fast path: single spread with cached shape
        if (spreadCount == 1 && cachedSourceShape != null && shapeStableAssumption.isValid()) {
            Object spreadValue = spreadNodes[0].execute(frame);
            if (spreadValue instanceof MezzeRecord rec && rec.getShape() == cachedSourceShape) {
                return executeCachedSingleSpread(frame, rec);
            }
            // Shape changed - invalidate and fall through
            CompilerDirectives.transferToInterpreterAndInvalidate();
            shapeStableAssumption.invalidate("spread shape changed");
        }

        // Execute all spread nodes
        Object[] spreadValues = new Object[spreadCount];
        for (int i = 0; i < spreadCount; i++) {
            spreadValues[i] = spreadNodes[i].execute(frame);
        }

        // Execute all field value nodes
        Object[] fieldValues = new Object[fieldCount];
        for (int i = 0; i < fieldCount; i++) {
            fieldValues[i] = fieldValueNodes[i].execute(frame);
        }

        // Try to use cached shape or compute new one
        return executeSpread(spreadValues, fieldValues);
    }

    /**
     * Fast path for single spread with cached shape.
     * Fully JIT-compiled, no @TruffleBoundary.
     * OPTIMIZED: Uses cached storage counts instead of counting on every call.
     */
    @ExplodeLoop
    private MezzeRecord executeCachedSingleSpread(VirtualFrame frame, MezzeRecord source) {
        Shape resultShape = cachedResultShape;
        int resultFieldCount = resultShape.getFieldCount();

        // Use cached storage counts (computed once in cacheShapeMapping)
        long[] longs = cachedLongCount > 0 ? new long[cachedLongCount] : EMPTY_LONGS;
        double[] doubles = cachedDoubleCount > 0 ? new double[cachedDoubleCount] : EMPTY_DOUBLES;
        Object[] objects = cachedObjectCount > 0 ? new Object[cachedObjectCount] : EMPTY_OBJECTS;

        // Execute field values - avoid allocation for 0 fields case
        Object[] fieldValues;
        if (fieldCount == 0) {
            fieldValues = EMPTY_OBJECTS;
        } else {
            fieldValues = new Object[fieldCount];
            for (int i = 0; i < fieldCount; i++) {
                fieldValues[i] = fieldValueNodes[i].execute(frame);
            }
        }

        // Copy fields using cached mapping
        for (int i = 0; i < resultFieldCount; i++) {
            FieldLocation loc = resultShape.getFieldLocation(i);
            int sourceType = fieldSourceMapping[i];
            int sourceIdx = fieldSourceIndex[i];

            Object value;
            if (sourceType == -1) {
                // From explicit field
                value = fieldValues[sourceIdx];
            } else {
                // From spread source
                value = source.getField(sourceIdx);
            }

            // Store in appropriate typed array
            switch (loc.storageType) {
                case LONG -> longs[loc.index] = (value instanceof Long l) ? l : ((Number) value).longValue();
                case DOUBLE -> doubles[loc.index] = (value instanceof Double d) ? d : ((Number) value).doubleValue();
                case OBJECT -> objects[loc.index] = value;
            }
        }

        return new MezzeRecord(resultShape, longs, doubles, objects);
    }

    /**
     * General path - may cache shape for future fast path.
     */
    private MezzeRecord executeSpread(Object[] spreadValues, Object[] fieldValues) {
        // Validate spread values are records
        MezzeRecord[] spreadRecords = new MezzeRecord[spreadCount];
        for (int i = 0; i < spreadCount; i++) {
            if (!(spreadValues[i] instanceof MezzeRecord rec)) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeException("Cannot spread non-record value");
            }
            spreadRecords[i] = rec;
        }

        // Try to cache for single spread case
        if (spreadCount == 1 && cachedSourceShape == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cacheShapeMapping(spreadRecords[0].getShape());
        }

        // Merge and create record
        return mergeRecords(spreadRecords, fieldValues);
    }

    @TruffleBoundary
    private void cacheShapeMapping(Shape sourceShape) {
        this.cachedSourceShape = sourceShape;

        // Compute merged shape
        LinkedHashMap<String, FieldInfo> mergedFields = new LinkedHashMap<>();

        // Add all source fields
        for (int i = 0; i < sourceShape.getFieldCount(); i++) {
            String name = sourceShape.getFieldName(i);
            FieldLocation loc = sourceShape.getFieldLocation(i);
            mergedFields.put(name, new FieldInfo(0, i, loc.storageType));
        }

        // Override with explicit fields (all stored as OBJECT for simplicity)
        for (int i = 0; i < fieldCount; i++) {
            mergedFields.put(fieldNames[i], new FieldInfo(-1, i, StorageType.OBJECT));
        }

        // Build result shape
        int size = mergedFields.size();
        String[] names = new String[size];
        FieldLocation[] locations = new FieldLocation[size];
        this.fieldSourceMapping = new int[size];
        this.fieldSourceIndex = new int[size];

        int longIdx = 0, doubleIdx = 0, objIdx = 0;
        int idx = 0;
        for (Map.Entry<String, FieldInfo> entry : mergedFields.entrySet()) {
            names[idx] = entry.getKey();
            FieldInfo info = entry.getValue();
            fieldSourceMapping[idx] = info.sourceType;
            fieldSourceIndex[idx] = info.sourceIndex;

            switch (info.storageType) {
                case LONG -> locations[idx] = new FieldLocation(StorageType.LONG, longIdx++);
                case DOUBLE -> locations[idx] = new FieldLocation(StorageType.DOUBLE, doubleIdx++);
                case OBJECT -> locations[idx] = new FieldLocation(StorageType.OBJECT, objIdx++);
            }
            idx++;
        }

        // Cache storage counts for fast path
        this.cachedLongCount = longIdx;
        this.cachedDoubleCount = doubleIdx;
        this.cachedObjectCount = objIdx;

        this.cachedResultShape = new Shape(names, locations);
        this.shapeStableAssumption = Truffle.getRuntime().createAssumption("spread-shape-stable");
    }

    @TruffleBoundary
    private MezzeRecord mergeRecords(MezzeRecord[] spreadRecords, Object[] fieldValues) {
        // Build shape cache key
        Shape[] spreadShapes = new Shape[spreadRecords.length];
        for (int i = 0; i < spreadRecords.length; i++) {
            spreadShapes[i] = spreadRecords[i].getShape();
        }
        ShapeCacheKey cacheKey = new ShapeCacheKey(spreadShapes, fieldNames);

        // Try to get cached shape
        Shape cachedMergedShape = SHAPE_CACHE.get(cacheKey);

        if (cachedMergedShape != null) {
            // Fast path: use cached shape and just copy values
            return mergeWithCachedShape(cachedMergedShape, spreadRecords, fieldValues);
        }

        // Slow path: compute merged shape and cache it
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();

        // Merge all spread records (later ones override earlier)
        for (MezzeRecord rec : spreadRecords) {
            Shape shape = rec.getShape();
            for (int i = 0; i < shape.getFieldCount(); i++) {
                merged.put(shape.getFieldName(i), rec.getField(i));
            }
        }

        // Override with explicit fields
        for (int i = 0; i < fieldCount; i++) {
            merged.put(fieldNames[i], fieldValues[i]);
        }

        // Create result record with all OBJECT storage
        String[] names = merged.keySet().toArray(new String[0]);
        Object[] values = merged.values().toArray();
        FieldLocation[] locations = new FieldLocation[names.length];
        for (int i = 0; i < names.length; i++) {
            locations[i] = new FieldLocation(StorageType.OBJECT, i);
        }

        Shape newShape = new Shape(names, locations);
        SHAPE_CACHE.put(cacheKey, newShape);

        return new MezzeRecord(newShape, EMPTY_LONGS, EMPTY_DOUBLES, values);
    }

    @TruffleBoundary
    private MezzeRecord mergeWithCachedShape(Shape resultShape, MezzeRecord[] spreadRecords, Object[] fieldValues) {
        int resultFieldCount = resultShape.getFieldCount();
        Object[] values = new Object[resultFieldCount];

        // Build lookup for explicit field values
        java.util.HashMap<String, Object> explicitFields = new java.util.HashMap<>(fieldCount);
        for (int i = 0; i < fieldCount; i++) {
            explicitFields.put(fieldNames[i], fieldValues[i]);
        }

        // Copy values in result shape order
        for (int i = 0; i < resultFieldCount; i++) {
            String fieldName = resultShape.getFieldName(i);

            // Check explicit fields first (they override)
            Object explicitValue = explicitFields.get(fieldName);
            if (explicitValue != null) {
                values[i] = explicitValue;
                continue;
            }

            // Find in spread records (later overrides earlier)
            for (int j = spreadRecords.length - 1; j >= 0; j--) {
                MezzeRecord rec = spreadRecords[j];
                int fieldIdx = rec.getShape().findFieldIndex(fieldName);
                if (fieldIdx >= 0) {
                    values[i] = rec.getField(fieldIdx);
                    break;
                }
            }
        }

        return new MezzeRecord(resultShape, EMPTY_LONGS, EMPTY_DOUBLES, values);
    }

    // Shared empty arrays to avoid allocation
    private static final long[] EMPTY_LONGS = new long[0];
    private static final double[] EMPTY_DOUBLES = new double[0];
    private static final Object[] EMPTY_OBJECTS = new Object[0];

    // Helper class for field info during shape computation
    private static final class FieldInfo {
        final int sourceType;  // -1 = explicit field, >=0 = spread source index
        final int sourceIndex; // index in source (field array or spread record)
        final StorageType storageType;

        FieldInfo(int sourceType, int sourceIndex, StorageType storageType) {
            this.sourceType = sourceType;
            this.sourceIndex = sourceIndex;
            this.storageType = storageType;
        }
    }

    // Cache key for shape combinations
    private static final class ShapeCacheKey {
        final Shape[] spreadShapes;
        final String[] fieldNames;
        final int hashCode;

        ShapeCacheKey(Shape[] spreadShapes, String[] fieldNames) {
            this.spreadShapes = spreadShapes;
            this.fieldNames = fieldNames;
            this.hashCode = computeHash();
        }

        private int computeHash() {
            int h = 0;
            for (Shape s : spreadShapes) {
                h = 31 * h + System.identityHashCode(s);
            }
            for (String n : fieldNames) {
                h = 31 * h + n.hashCode();
            }
            return h;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ShapeCacheKey other)) return false;
            if (spreadShapes.length != other.spreadShapes.length) return false;
            if (fieldNames.length != other.fieldNames.length) return false;
            for (int i = 0; i < spreadShapes.length; i++) {
                if (spreadShapes[i] != other.spreadShapes[i]) return false;
            }
            for (int i = 0; i < fieldNames.length; i++) {
                if (!fieldNames[i].equals(other.fieldNames[i])) return false;
            }
            return true;
        }
    }

    /**
     * Factory method.
     */
    public static RecordSpreadNode create(MezzeNode[] spreadNodes, String[] fieldNames, MezzeNode[] fieldValueNodes) {
        return new RecordSpreadNode(spreadNodes, fieldNames, fieldValueNodes);
    }
}
