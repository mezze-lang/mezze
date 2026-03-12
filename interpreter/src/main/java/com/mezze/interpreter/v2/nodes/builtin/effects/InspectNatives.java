package com.mezze.interpreter.v2.nodes.builtin.effects;

import com.mezze.datastructures.deque.MezzeDeque;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.set.MezzeSet;
import com.mezze.datastructures.sorted.MezzeSortedMap;
import com.mezze.datastructures.sorted.MezzeSortedSet;

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
        if (value instanceof MezzeMap map) {
            if (map.isEmpty()) {
                return "{}";
            }
            StringBuilder sb = new StringBuilder("{ ");
            boolean first = true;
            for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(valueToString(entry.key()))
                  .append(" = ")
                  .append(valueToString(entry.value()));
            }
            sb.append(" }");
            return sb.toString();
        }

        // Sets
        if (value instanceof MezzeSet set) {
            if (set.isEmpty()) {
                return "{}";
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Object elem : set) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(valueToString(elem));
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
        if (value instanceof MezzeSortedSet sortedSet) {
            if (sortedSet.isEmpty()) {
                return "SortedSet{}";
            }
            StringBuilder sb = new StringBuilder("SortedSet{");
            boolean first = true;
            for (Object elem : sortedSet) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(valueToString(elem));
            }
            sb.append("}");
            return sb.toString();
        }

        // Sorted Maps
        if (value instanceof MezzeSortedMap<?, ?> sortedMap) {
            if (sortedMap.isEmpty()) {
                return "SortedMap{}";
            }
            StringBuilder sb = new StringBuilder("SortedMap{ ");
            boolean first = true;
            for (MezzeSortedMap.RawEntry entry : sortedMap.rawEntries()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(valueToString(entry.key()))
                  .append(" = ")
                  .append(valueToString(entry.value()));
            }
            sb.append(" }");
            return sb.toString();
        }

        // Deques
        if (value instanceof MezzeDeque deque) {
            StringBuilder sb = new StringBuilder("Deque[");
            boolean first = true;
            for (Object elem : deque) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(valueToString(elem));
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
