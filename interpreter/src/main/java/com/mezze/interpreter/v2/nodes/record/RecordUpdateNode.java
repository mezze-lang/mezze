package com.mezze.interpreter.v2.nodes.record;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Record update node (functional update).
 *
 * Creates a new record with one field updated.
 * Uses copy-on-write semantics.
 *
 * Example Mezze code:
 *   let point2 = { point | x = 10 }
 */
@NodeChild("recordNode")
@NodeChild("valueNode")
@NodeInfo(shortName = "record-update")
public abstract class RecordUpdateNode extends MezzeNode {

    @CompilationFinal
    private final int fieldIndex;

    protected RecordUpdateNode(int fieldIndex) {
        this.fieldIndex = fieldIndex;
    }

    /**
     * Update a field with copy-on-write.
     */
    @Specialization
    protected MezzeRecord update(MezzeRecord record, Object value) {
        return record.withField(fieldIndex, value);
    }

    /**
     * Create an update node.
     */
    public static RecordUpdateNode create(MezzeNode recordNode, MezzeNode valueNode, int fieldIndex) {
        return RecordUpdateNodeGen.create(fieldIndex, recordNode, valueNode);
    }
}
