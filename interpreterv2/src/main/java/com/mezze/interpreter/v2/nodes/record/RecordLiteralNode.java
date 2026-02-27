package com.mezze.interpreter.v2.nodes.record;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeRecord.Shape;
import com.mezze.interpreter.v2.values.MezzeRecord.FieldLocation;
import com.mezze.interpreter.v2.values.MezzeRecord.FieldLocation.StorageType;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Record literal node.
 *
 * Creates a record with typed field arrays based on the field types
 * determined at compile time from inferredType.
 */
@NodeInfo(shortName = "record")
public final class RecordLiteralNode extends MezzeNode {

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

    public RecordLiteralNode(Shape shape, MezzeNode[] fieldValueNodes,
                             int longFieldCount, int doubleFieldCount, int objectFieldCount) {
        this.shape = shape;
        this.fieldValueNodes = fieldValueNodes;
        this.longFieldCount = longFieldCount;
        this.doubleFieldCount = doubleFieldCount;
        this.objectFieldCount = objectFieldCount;
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
        // Allocate typed arrays
        long[] longFields = longFieldCount > 0 ? new long[longFieldCount] : new long[0];
        double[] doubleFields = doubleFieldCount > 0 ? new double[doubleFieldCount] : new double[0];
        Object[] objectFields = objectFieldCount > 0 ? new Object[objectFieldCount] : new Object[0];

        // Evaluate each field and store in the appropriate array
        for (int i = 0; i < fieldValueNodes.length; i++) {
            Object value = fieldValueNodes[i].execute(frame);
            FieldLocation loc = shape.getFieldLocation(i);

            switch (loc.storageType) {
                case LONG -> {
                    if (value instanceof Long l) {
                        longFields[loc.index] = l;
                    } else {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new RuntimeException("Expected Long for field " + shape.getFieldName(i));
                    }
                }
                case DOUBLE -> {
                    if (value instanceof Double d) {
                        doubleFields[loc.index] = d;
                    } else if (value instanceof Long l) {
                        doubleFields[loc.index] = l.doubleValue();
                    } else {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new RuntimeException("Expected Double for field " + shape.getFieldName(i));
                    }
                }
                case OBJECT -> objectFields[loc.index] = value;
            }
        }

        return new MezzeRecord(shape, longFields, doubleFields, objectFields);
    }
}
