package com.mezze.interpreter.nodes.continuation;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeUnit;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * __native_continuation_is_serializable - Check if a continuation can be serialized.
 *
 * Returns true if all captured values are serializable primitive types,
 * records with serializable fields, sequences, variants, or bytes.
 *
 * Non-serializable values include:
 * - Functions (MezzeFunction) - can't serialize closures
 * - Native objects (TruffleObject) - can't serialize native state
 * - Promises, atoms, channels, etc. - runtime-specific state
 */
@NodeInfo(shortName = "continuation-is-serializable")
public final class ContinuationIsSerializableNode extends MezzeNode {

    @Child
    private MezzeNode argumentNode;

    private ContinuationIsSerializableNode(MezzeNode argumentNode) {
        this.argumentNode = argumentNode;
    }

    public static ContinuationIsSerializableNode create(MezzeNode argumentNode) {
        return new ContinuationIsSerializableNode(argumentNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object arg = argumentNode.execute(frame);

        if (!(arg instanceof MezzeRecord record)) {
            throw new RuntimeException("__native_continuation_is_serializable expects record argument");
        }

        Object contObj = record.getField("cont");
        if (!(contObj instanceof MezzeFunction func)) {
            throw new RuntimeException("__native_continuation_is_serializable expects 'cont' field as function");
        }

        return isSerializable(func);
    }

    @TruffleBoundary
    private boolean isSerializable(MezzeFunction func) {
        Object[] captured = func.getClosureValues();
        for (Object value : captured) {
            if (!isValueSerializable(value)) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    private boolean isValueSerializable(Object value) {
        if (value == null || value instanceof MezzeUnit) {
            return true;
        }
        if (value instanceof Long || value instanceof Double || value instanceof Boolean ||
            value instanceof String || value instanceof Character || value instanceof byte[]) {
            return true;
        }
        if (value instanceof MezzeRecord record) {
            int fieldCount = record.getFieldCount();
            for (int i = 0; i < fieldCount; i++) {
                if (!isValueSerializable(record.getField(i))) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof MezzeSeq<?> seq) {
            Object[] arr = seq.toArray();
            for (Object elem : arr) {
                if (!isValueSerializable(elem)) {
                    return false;
                }
            }
            return true;
        }
        if (value instanceof MezzeList<?> list) {
            MezzeList<?> curr = list;
            while (!curr.isEmpty()) {
                if (!isValueSerializable(curr.head())) {
                    return false;
                }
                curr = curr.tail();
            }
            return true;
        }
        if (value instanceof MezzeVariant variant) {
            return isValueSerializable(variant.getPayloadValue());
        }
        // Functions, native objects, etc. are not serializable
        return false;
    }
}
