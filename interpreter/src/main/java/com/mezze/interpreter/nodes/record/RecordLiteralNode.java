package com.mezze.interpreter.nodes.record;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeRecord.Shape;
import com.mezze.interpreter.values.MezzeRecord.FieldLocation;
import com.mezze.interpreter.values.MezzeRecord.FieldLocation.StorageType;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.instrumentation.AllocationReporter;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;

/**
 * Record literal node - OPTIMIZED.
 *
 * Key optimizations:
 * 1. Pre-allocated empty arrays shared across all executions
 * 2. @ExplodeLoop for loop unrolling
 * 3. Direct typed array access
 */
@NodeInfo(shortName = "record")
public final class RecordLiteralNode extends MezzeNode {

    // Shared empty arrays - avoid allocation when count is 0
    private static final long[] EMPTY_LONGS = new long[0];
    private static final double[] EMPTY_DOUBLES = new double[0];
    private static final Object[] EMPTY_OBJECTS = new Object[0];

    @CompilationFinal
    private final Shape shape;

    @Children
    private final MezzeNode[] fieldValueNodes;

    // Pre-computed counts for array allocation
    @CompilationFinal
    private final int longFieldCount;

    @CompilationFinal
    private final int doubleFieldCount;

    @CompilationFinal
    private final int objectFieldCount;

    // Pre-computed storage types for each field (avoids method call in loop)
    @CompilationFinal(dimensions = 1)
    private final int[] fieldStorageTypes; // 0=LONG, 1=DOUBLE, 2=OBJECT

    @CompilationFinal(dimensions = 1)
    private final int[] fieldStorageIndices;

    // Error branch profile - tell JIT this path is cold
    private final InlinedBranchProfile typeErrorProfile = InlinedBranchProfile.getUncached();

    public RecordLiteralNode(Shape shape, MezzeNode[] fieldValueNodes,
                             int longFieldCount, int doubleFieldCount, int objectFieldCount) {
        this.shape = shape;
        this.fieldValueNodes = fieldValueNodes;
        this.longFieldCount = longFieldCount;
        this.doubleFieldCount = doubleFieldCount;
        this.objectFieldCount = objectFieldCount;

        // Pre-compute field locations for faster access
        this.fieldStorageTypes = new int[fieldValueNodes.length];
        this.fieldStorageIndices = new int[fieldValueNodes.length];
        for (int i = 0; i < fieldValueNodes.length; i++) {
            FieldLocation loc = shape.getFieldLocation(i);
            this.fieldStorageTypes[i] = loc.storageType.ordinal();
            this.fieldStorageIndices[i] = loc.index;
        }
    }

    /**
     * Create a simple record where all fields are Object storage.
     */
    public static RecordLiteralNode allObjects(String[] fieldNames, MezzeNode[] fieldValueNodes) {
        FieldLocation[] locations = new FieldLocation[fieldNames.length];
        for (int i = 0; i < fieldNames.length; i++) {
            locations[i] = new FieldLocation(StorageType.OBJECT, i);
        }
        Shape shape = new Shape(fieldNames, locations);
        return new RecordLiteralNode(shape, fieldValueNodes, 0, 0, fieldNames.length);
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Allocate arrays - use shared empty arrays when possible
        final long[] longFields = longFieldCount > 0 ? new long[longFieldCount] : EMPTY_LONGS;
        final double[] doubleFields = doubleFieldCount > 0 ? new double[doubleFieldCount] : EMPTY_DOUBLES;
        final Object[] objectFields = objectFieldCount > 0 ? new Object[objectFieldCount] : EMPTY_OBJECTS;

        // Evaluate each field and store in the appropriate array
        // @ExplodeLoop unrolls this, and fieldStorageTypes is @CompilationFinal
        for (int i = 0; i < fieldValueNodes.length; i++) {
            Object value = fieldValueNodes[i].execute(frame);
            int storageType = fieldStorageTypes[i];
            int storageIndex = fieldStorageIndices[i];

            // Switch on compile-time constant - JIT eliminates branches
            switch (storageType) {
                case 0 -> { // LONG
                    if (value instanceof Long l) {
                        longFields[storageIndex] = l;
                    } else {
                        typeErrorProfile.enter(this);
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new RuntimeException("Expected Long for field, got: " + value.getClass());
                    }
                }
                case 1 -> { // DOUBLE
                    if (value instanceof Double d) {
                        doubleFields[storageIndex] = d;
                    } else if (value instanceof Long l) {
                        doubleFields[storageIndex] = l.doubleValue();
                    } else {
                        typeErrorProfile.enter(this);
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new RuntimeException("Expected Double for field, got: " + value.getClass());
                    }
                }
                default -> objectFields[storageIndex] = value; // OBJECT
            }
        }

        // Allocation tracking. Reporter is a no-op when no memtracer is
        // attached, so the cost on a hot path is one volatile read + a
        // pair of empty method calls — JIT folds it away.
        AllocationReporter reporter = MezzeContext.get(this).getAllocationReporter();
        reporter.onEnter(null, 0, AllocationReporter.SIZE_UNKNOWN);
        MezzeRecord result = new MezzeRecord(shape, longFields, doubleFields, objectFields);
        reporter.onReturnValue(result, 0, AllocationReporter.SIZE_UNKNOWN);
        return result;
    }
}
