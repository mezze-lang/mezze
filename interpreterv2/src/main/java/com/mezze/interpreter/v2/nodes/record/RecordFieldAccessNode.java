package com.mezze.interpreter.v2.nodes.record;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeRecord.FieldLocation;
import com.mezze.interpreter.v2.values.MezzeRecord.FieldLocation.StorageType;
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
}
