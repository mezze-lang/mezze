package com.mezze.interpreter.v2.nodes.builtin.effects;

import com.mezze.interpreter.v2.nodes.builtin.collections.*;
import com.mezze.interpreter.v2.nodes.builtin.io.BytesNatives;
import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native implementations for Inspect ability operations.
 *
 * The Inspect ability provides a way to convert any Mezze value to a human-readable string representation.
 * This is similar to toString() in other languages.
 *
 * Operations:
 * - to_str: Convert any value to its string representation
 */
public final class InspectNatives {

    private InspectNatives() {}

    @TruffleBoundary
    public static String toStr(MezzeRecord args) {
        int index = args.getFieldIndex("val");
        if (index < 0) {
            throw new RuntimeException("__native_inspect_to_str expects 'val' field");
        }
        Object value = args.getField(index);
        return valueToString(value);
    }

    /**
     * Convert a value to a human-readable string representation.
     */
    @TruffleBoundary
    public static String valueToString(Object value) {
        if (value == null) {
            return "null";
        }

        // Primitive types
        if (value instanceof Long l) {
            return l.toString();
        }
        if (value instanceof Double d) {
            return d.toString();
        }
        if (value instanceof String s) {
            return "\"" + s + "\"";  // Include quotes for strings
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }

        // Unit
        if (value instanceof MezzeUnit) {
            return "()";
        }

        // Records - show field names and values
        if (value instanceof MezzeRecord rec) {
            int fieldCount = rec.getShape().getFieldCount();
            if (fieldCount == 0) {
                return "{}";
            }
            StringBuilder sb = new StringBuilder("{ ");
            for (int i = 0; i < fieldCount; i++) {
                if (i > 0) sb.append(", ");
                String fieldName = rec.getShape().getFieldName(i);
                Object fieldValue = rec.getField(i);
                sb.append(fieldName).append(" = ").append(valueToString(fieldValue));
            }
            sb.append(" }");
            return sb.toString();
        }

        // Variants - show constructor and value
        if (value instanceof MezzeVariant variant) {
            String constructor = variant.getConstructorName();
            if (!variant.hasPayload()) {
                return constructor;
            }
            return constructor + " " + valueToString(variant.getPayloadValue());
        }

        // Lists
        if (value instanceof MezzeList list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(valueToString(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }

        // Non-empty lists
        if (value instanceof NonEmptyListNatives.MezzeNonEmptyList nel) {
            StringBuilder sb = new StringBuilder("NonEmptyList(");
            MezzeList list = NonEmptyListNatives.toList(nel);
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(valueToString(list.get(i)));
            }
            sb.append(")");
            return sb.toString();
        }

        // Maps
        if (value instanceof MapNatives.MezzeMap map) {
            if (map.isEmpty()) {
                return "{}";
            }
            StringBuilder sb = new StringBuilder("{ ");
            Object[] keys = map.keys();
            for (int i = 0; i < keys.length; i++) {
                if (i > 0) sb.append(", ");
                Object k = keys[i];
                Object v = MapNatives.get(map, k);
                // get returns a variant, extract the value
                if (v instanceof MezzeVariant opt && opt.isSome()) {
                    v = ((MezzeRecord) opt.getPayloadValue()).getField(0);
                }
                sb.append(valueToString(k))
                  .append(" = ")
                  .append(valueToString(v));
            }
            sb.append(" }");
            return sb.toString();
        }

        // Sets
        if (value instanceof SetNatives.MezzeSet set) {
            if (set.isEmpty()) {
                return "{}";
            }
            StringBuilder sb = new StringBuilder("{");
            MezzeList elems = SetNatives.toList(set);
            for (int i = 0; i < elems.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(valueToString(elems.get(i)));
            }
            sb.append("}");
            return sb.toString();
        }

        // Arrays (ArrayList)
        if (value instanceof Object[] arr) {
            StringBuilder sb = new StringBuilder("[|");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(valueToString(arr[i]));
            }
            sb.append("|]");
            return sb.toString();
        }

        // Sorted Sets
        if (value instanceof SortedSetNatives.MezzeSortedSet sortedSet) {
            if (sortedSet.isEmpty()) {
                return "SortedSet{}";
            }
            StringBuilder sb = new StringBuilder("SortedSet{");
            MezzeList elems = SortedSetNatives.toList(sortedSet);
            for (int i = 0; i < elems.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(valueToString(elems.get(i)));
            }
            sb.append("}");
            return sb.toString();
        }

        // Sorted Maps
        if (value instanceof SortedMapNatives.MezzeSortedMap sortedMap) {
            if (sortedMap.isEmpty()) {
                return "SortedMap{}";
            }
            StringBuilder sb = new StringBuilder("SortedMap{ ");
            MezzeList keysList = SortedMapNatives.keys(sortedMap);
            for (int i = 0; i < keysList.size(); i++) {
                if (i > 0) sb.append(", ");
                Object k = keysList.get(i);
                Object v = SortedMapNatives.get(sortedMap, k);
                // get returns a variant, extract the value
                if (v instanceof MezzeVariant opt && opt.isSome()) {
                    v = ((MezzeRecord) opt.getPayloadValue()).getField(0);
                }
                sb.append(valueToString(k))
                  .append(" = ")
                  .append(valueToString(v));
            }
            sb.append(" }");
            return sb.toString();
        }

        // Deques
        if (value instanceof DequeNatives.MezzeDeque deque) {
            StringBuilder sb = new StringBuilder("Deque[");
            MezzeList list = DequeNatives.toList(deque);
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(valueToString(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }

        // Bytes
        if (value instanceof BytesNatives.MezzeBytes bytes) {
            return BytesNatives.toHex(bytes);
        }

        // Functions
        if (value instanceof MezzeFunction func) {
            String name = func.getName();
            return name != null ? "<function:" + name + ">" : "<function>";
        }

        // Fallback
        return "<" + value.getClass().getSimpleName() + ">";
    }
}
