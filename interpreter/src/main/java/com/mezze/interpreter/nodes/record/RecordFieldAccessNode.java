package com.mezze.interpreter.nodes.record;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeRecord.FieldLocation;
import com.mezze.interpreter.values.MezzeRecord.FieldLocation.StorageType;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Record field access node.
 *
 * Uses compile-time field index for O(1) access.
 * The field index and storage type are determined at compile time
 * from the record's inferredType.
 */
@NodeChild("recordNode")
@NodeField(name = "fieldIndex", type = int.class)
@NodeField(name = "storageType", type = int.class) // 0=Long, 1=Double, 2=Object
@NodeField(name = "locationIndex", type = int.class)
@NodeInfo(shortName = "field")
public abstract class RecordFieldAccessNode extends MezzeNode {

    public static final int STORAGE_LONG = 0;
    public static final int STORAGE_DOUBLE = 1;
    public static final int STORAGE_OBJECT = 2;

    protected abstract int getFieldIndex();
    protected abstract int getStorageType();
    protected abstract int getLocationIndex();

    /**
     * Fast path: Access Long field (Int type).
     */
    @Specialization(guards = "storageType == 0")
    protected long accessLongField(MezzeRecord record) {
        return record.getLongField(getLocationIndex());
    }

    /**
     * Fast path: Access Double field (Float type).
     */
    @Specialization(guards = "storageType == 1")
    protected double accessDoubleField(MezzeRecord record) {
        return record.getDoubleField(getLocationIndex());
    }

    /**
     * General path: Access Object field.
     */
    @Specialization(guards = "storageType == 2")
    protected Object accessObjectField(MezzeRecord record) {
        return record.getObjectField(getLocationIndex());
    }

    /**
     * Create a field access node.
     */
    public static RecordFieldAccessNode create(MezzeNode recordNode, int fieldIndex,
                                                int storageType, int locationIndex) {
        return RecordFieldAccessNodeGen.create(recordNode, fieldIndex, storageType, locationIndex);
    }

    /**
     * Create an Object field access node (default).
     */
    public static RecordFieldAccessNode createObject(MezzeNode recordNode, int fieldIndex, int locationIndex) {
        return create(recordNode, fieldIndex, STORAGE_OBJECT, locationIndex);
    }

    /**
     * Create a cached field access node by name (for cases where field index isn't known).
     * Uses POLYMORPHIC INLINE CACHE for fast repeated access.
     */
    public static MezzeNode createByName(MezzeNode recordNode, String fieldName) {
        return CachedFieldAccessNode.create(recordNode, fieldName);
    }
}

/**
 * Dynamic field access by name - slower path.
 */
@NodeInfo(shortName = "dynamic-field")
final class DynamicFieldAccessNode extends MezzeNode {

    @Child
    private MezzeNode recordNode;

    @CompilationFinal
    private final String fieldName;

    DynamicFieldAccessNode(MezzeNode recordNode, String fieldName) {
        this.recordNode = recordNode;
        this.fieldName = fieldName;
    }

    @Override
    public Object execute(com.oracle.truffle.api.frame.VirtualFrame frame) {
        Object recordObj = recordNode.execute(frame);
        if (recordObj instanceof MezzeRecord record) {
            // getField is non-boundary for small shapes (≤ SMALL_SHAPE_THRESHOLD)
            // since shape.findFieldIndex unrolls the name compare. Large
            // shapes still pay the HashMap boundary internally — acceptable.
            Object value = record.getField(fieldName);
            if (value == null) {
                com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new RuntimeException("Field not found: " + fieldName);
            }
            return value;
        }
        com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("Expected record, got: " + recordObj.getClass().getSimpleName());
    }
}
