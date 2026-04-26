package com.mezze.interpreter.nodes.inline;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Direct inspect node that takes the value directly (no record wrapper).
 * Used for method-call syntax: x.to_dbg_str {} (Inspect ability).
 */
@NodeChild(value = "operand", type = MezzeNode.class)
@NodeInfo(shortName = "direct-inspect")
public abstract class DirectInspectNode extends MezzeNode {

    @Specialization
    protected static String inspectLong(long value) {
        return Long.toString(value);
    }

    @Specialization
    protected static String inspectDouble(double value) {
        return Double.toString(value);
    }

    @Specialization
    protected static String inspectBoolean(boolean value) {
        return value ? "True" : "False";
    }

    @Specialization
    protected static String inspectString(String value) {
        return "\"" + value + "\"";
    }

    @Specialization
    @TruffleBoundary
    protected static String inspectGeneric(Object value) {
        return valueToString(value);
    }

    @TruffleBoundary
    private static String valueToString(Object value) {
        if (value == null) return "null";
        if (value instanceof Long) return value.toString();
        if (value instanceof Double) return value.toString();
        if (value instanceof Boolean) return ((Boolean) value) ? "True" : "False";
        if (value instanceof String) return "\"" + value + "\"";
        if (value == MezzeUnit.INSTANCE) return "{}";

        if (value instanceof MezzeRecord record) {
            int fieldCount = record.getFieldCount();
            if (fieldCount == 0) return "{}";
            StringBuilder sb = new StringBuilder("{ ");
            boolean first = true;
            MezzeRecord.Shape shape = record.getShape();
            for (int i = 0; i < fieldCount; i++) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(shape.getFieldName(i)).append(" = ").append(valueToString(record.getField(i)));
            }
            sb.append(" }");
            return sb.toString();
        }

        if (value instanceof MezzeVariant variant) {
            if (!variant.hasPayload()) return variant.getConstructorName();
            return variant.getConstructorName() + " " + valueToString(variant.getPayloadValue());
        }

        if (value instanceof MezzeList list) {
            int size = list.size();
            if (size == 0) return "[]";
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (int i = 0; i < size; i++) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(valueToString(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }

        if (value instanceof Object[] array) {
            if (array.length == 0) return "[]";
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object elem : array) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(valueToString(elem));
            }
            sb.append("]");
            return sb.toString();
        }

        if (value instanceof MezzeFunction func) {
            String name = func.getName();
            return name != null ? "<function:" + name + ">" : "<function>";
        }

        return value.toString();
    }

    public static DirectInspectNode create(MezzeNode operand) {
        return DirectInspectNodeGen.create(operand);
    }
}
