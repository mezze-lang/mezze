package com.mezze.language.interpreter.nodes;

import com.mezze.language.interpreter.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

/**
 * Adapter node that wraps a V2 MezzeNode and converts its Object result to MezzeValue.
 *
 * This allows the V2 interpreter (which returns raw Objects like Long, Double, String)
 * to be used as a drop-in replacement for V1 (which returns MezzeValue wrappers).
 */
public final class V2AdapterNode extends MezzeNode {

    @Child
    private com.mezze.interpreter.v2.nodes.MezzeNode v2Node;

    public V2AdapterNode(com.mezze.interpreter.v2.nodes.MezzeNode v2Node) {
        this.v2Node = v2Node;
    }

    @Override
    public MezzeValue execute(VirtualFrame frame) {
        Object result = v2Node.execute(frame);
        return convertToMezzeValue(result);
    }

    @TruffleBoundary
    private MezzeValue convertToMezzeValue(Object value) {
        if (value == null) {
            return MezzeNull.INSTANCE;
        }

        // Primitive types
        if (value instanceof Long l) {
            return new MezzeInt(l);
        }
        if (value instanceof Integer i) {
            return new MezzeInt(i.longValue());
        }
        if (value instanceof Double d) {
            return new MezzeFloat(d);
        }
        if (value instanceof Float f) {
            return new MezzeFloat(f.doubleValue());
        }
        if (value instanceof Boolean b) {
            return new MezzeBool(b);
        }
        if (value instanceof String s) {
            return new MezzeString(s);
        }

        // V2 specific types - convert to V1 equivalents
        if (value instanceof com.mezze.interpreter.v2.values.MezzeFunction v2Func) {
            return convertV2Function(v2Func);
        }
        if (value instanceof com.mezze.interpreter.v2.values.MezzeRecord v2Record) {
            return convertV2Record(v2Record);
        }
        if (value instanceof com.mezze.interpreter.v2.values.MezzeVariant v2Variant) {
            return convertV2Variant(v2Variant);
        }
        if (value instanceof com.mezze.interpreter.v2.values.MezzeList v2List) {
            return convertV2List(v2List);
        }
        if (value instanceof com.mezze.interpreter.v2.values.MezzeSeq v2Seq) {
            return convertV2Seq(v2Seq);
        }
        if (value instanceof com.mezze.interpreter.v2.values.MezzeUnit) {
            return MezzeNull.INSTANCE;
        }
        if (value instanceof com.mezze.interpreter.v2.values.MezzeNull) {
            return MezzeNull.INSTANCE;
        }

        // Fallback for unknown types
        return new MezzeString(value.toString());
    }

    @TruffleBoundary
    private MezzeValue convertV2Function(com.mezze.interpreter.v2.values.MezzeFunction v2Func) {
        // Create a NativeMezzeFunction that delegates to the V2 function
        return new NativeMezzeFunction(v2Func.getName(), args -> {
            Object[] v2Args = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) {
                v2Args[i] = convertToV2Value(args.get(i));
            }
            Object result = v2Func.getCallTarget().call(v2Args);
            return convertToMezzeValue(result);
        });
    }

    @TruffleBoundary
    private MezzeValue convertV2Record(com.mezze.interpreter.v2.values.MezzeRecord v2Record) {
        java.util.Map<String, MezzeValue> fields = new java.util.LinkedHashMap<>();
        String[] fieldNames = v2Record.getShape().getMemberNames();
        for (String name : fieldNames) {
            Object v2Value = v2Record.getField(name);
            fields.put(name, convertToMezzeValue(v2Value));
        }
        // Use the companion object's invoke method (exposed as static method in Java)
        return MezzeRecord.invoke(fields);
    }

    @TruffleBoundary
    private MezzeValue convertV2Variant(com.mezze.interpreter.v2.values.MezzeVariant v2Variant) {
        String tag = v2Variant.getConstructorName();
        Object[] v2Payloads = v2Variant.getPayload();

        if (v2Payloads.length == 0) {
            return new MezzeVariant(tag, MezzeNull.INSTANCE);
        } else if (v2Payloads.length == 1) {
            return new MezzeVariant(tag, convertToMezzeValue(v2Payloads[0]));
        } else {
            // Multiple payloads - convert to record
            java.util.Map<String, MezzeValue> fields = new java.util.LinkedHashMap<>();
            for (int i = 0; i < v2Payloads.length; i++) {
                fields.put("_" + i, convertToMezzeValue(v2Payloads[i]));
            }
            return new MezzeVariant(tag, MezzeRecord.invoke(fields));
        }
    }

    @TruffleBoundary
    private MezzeValue convertV2List(com.mezze.interpreter.v2.values.MezzeList v2List) {
        int size = v2List.size();
        java.util.List<MezzeValue> elements = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            elements.add(convertToMezzeValue(v2List.get(i)));
        }
        return new MezzeArrayList(elements);
    }

    @TruffleBoundary
    private MezzeValue convertV2Seq(com.mezze.interpreter.v2.values.MezzeSeq v2Seq) {
        int size = v2Seq.size();
        java.util.List<MezzeValue> elements = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            elements.add(convertToMezzeValue(v2Seq.get(i)));
        }
        return new MezzeArrayList(elements);
    }

    @TruffleBoundary
    private Object convertToV2Value(MezzeValue value) {
        if (value instanceof MezzeInt i) {
            return i.getValue();
        }
        if (value instanceof MezzeFloat f) {
            return f.getValue();
        }
        if (value instanceof MezzeBool b) {
            return b.getValue();
        }
        if (value instanceof MezzeString s) {
            return s.getValue();
        }
        if (value instanceof MezzeNull) {
            return com.mezze.interpreter.v2.values.MezzeUnit.INSTANCE;
        }
        // For complex types, return as-is and let V2 handle the interop
        return value;
    }

    /**
     * Static factory method.
     */
    public static V2AdapterNode create(com.mezze.interpreter.v2.nodes.MezzeNode v2Node) {
        return new V2AdapterNode(v2Node);
    }
}
