package com.mezze.interpreter.nodes.builtin.effects;

import com.mezze.interpreter.runtime.NativeTypeMismatchException;

import com.mezze.datastructures.deque.MezzeDeque;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.set.MezzeSet;
import com.mezze.datastructures.sorted.MezzeSortedMap;
import com.mezze.datastructures.sorted.MezzeSortedSet;

import com.mezze.interpreter.nodes.builtin.collections.*;
import com.mezze.interpreter.nodes.builtin.io.BytesNatives;
import com.mezze.interpreter.values.*;
import com.mezze.language.truffleir.PrintType;
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
            throw new NativeTypeMismatchException("__native_inspect_to_str expects 'val' field");
        }
        Object value = args.getField(index);
        return valueToString(value);
    }

    /**
     * Type-driven render (#394): walk (value, printType) in lockstep. This is
     * the sole entry for `x.to_dbg {}` / `x.to_str {}` — the static PrintType
     * carries the information the runtime value alone cannot: Nat vs Int, Frac,
     * Newtype (erased to its underlying value), and single-char Str-as-Char.
     *
     * `dbgStyle` distinguishes to_dbg (quote strings/chars) from to_str; it is
     * threaded to the string/char leaves only.
     */
    @TruffleBoundary
    public static String render(Object value, PrintType printType, boolean dbgStyle) {
        if (printType instanceof PrintType.Scalar sc) {
            return renderScalar(value, sc.getKind(), dbgStyle);
        }
        if (printType instanceof PrintType.Frac) {
            return renderFrac(value);
        }
        if (printType instanceof PrintType.Newtype nt) {
            // Newtype is erased at runtime — `value` IS the underlying value.
            return nt.getName() + ".(" + render(value, nt.getInner(), dbgStyle) + ")";
        }
        if (printType instanceof PrintType.Record rec) {
            return renderRecord(value, rec, dbgStyle);
        }
        if (printType instanceof PrintType.Variant var) {
            return renderVariant(value, var, dbgStyle);
        }
        if (printType instanceof PrintType.MzList l) {
            // List renders `List(1, 2, 3)` / `List()` — distinct from Seq, whose
            // `[...]` syntax is Seq's alone (matches the NonEmptyList(...) form).
            return renderElements(iterableOf(value), l.getInner(), dbgStyle, "List(", ")");
        }
        if (printType instanceof PrintType.MzSeq s) {
            return renderElements(iterableOf(value), s.getInner(), dbgStyle, "[", "]");
        }
        if (printType instanceof PrintType.MzSet s) {
            return renderElements(iterableOf(value), s.getInner(), dbgStyle, "Set{", "}");
        }
        if (printType instanceof PrintType.MzSortedSet s) {
            return renderElements(iterableOf(value), s.getInner(), dbgStyle, "SortedSet{", "}");
        }
        if (printType instanceof PrintType.MzDeque d) {
            return renderElements(iterableOf(value), d.getInner(), dbgStyle, "Deque[", "]");
        }
        if (printType instanceof PrintType.MzNonEmptyList nel) {
            return renderElements(iterableOf(value), nel.getInner(), dbgStyle, "NonEmptyList(", ")");
        }
        if (printType instanceof PrintType.MzMap m) {
            return renderMap(value, m.getKey(), m.getValue(), dbgStyle, "Map");
        }
        if (printType instanceof PrintType.MzSortedMap m) {
            return renderMap(value, m.getKey(), m.getValue(), dbgStyle, "SortedMap");
        }
        if (printType instanceof PrintType.Function) {
            return renderFunction(value);
        }
        if (printType instanceof PrintType.Dynamic) {
            // No static type at this position (an unconstrained ADT type
            // parameter). The runtime value-only path renders every
            // runtime-distinguishable type correctly. `to_str` on a bare Str
            // still wants unquoted output; valueToString always quotes, so
            // strip for the to_str-on-String case.
            if (!dbgStyle && value instanceof String s) return s;
            return valueToString(value);
        }
        // PrintType is a sealed hierarchy fully covered above. A gap here is a
        // renderer bug, not a printable value.
        throw new NativeTypeMismatchException(
            "Inspect: unhandled PrintType " + printType);
    }

    private static String renderScalar(Object value, PrintType.ScalarKind kind, boolean dbgStyle) {
        switch (kind) {
            case Int:
                return ((Long) value).toString();
            case Nat:
                // Nat is a Long at runtime; the `n` suffix distinguishes it.
                return value + "n";
            case Float:
                return ((Double) value).toString();
            case Bool:
                return ((Boolean) value) ? "True" : "False";
            case Bytes:
                return BytesNatives.toHex((BytesNatives.MezzeBytes) value);
            case Char: {
                // Char has two runtime reps: a boxed Character (from a literal)
                // and a single-char String (from char::from_int/from_str).
                // Both render single-quoted, uniformly.
                char c = (value instanceof Character)
                    ? (Character) value
                    : ((String) value).charAt(0);
                return "'" + c + "'";
            }
            case Str: {
                String s = (String) value;
                return dbgStyle ? "\"" + s + "\"" : s;
            }
        }
        throw new NativeTypeMismatchException("Inspect: unhandled scalar kind " + kind);
    }

    private static String renderFrac(Object value) {
        MezzeRecord rec = (MezzeRecord) value;
        Object num = rec.getField(rec.getFieldIndex("numerator"));
        Object den = rec.getField(rec.getFieldIndex("denominator"));
        return num + "//" + den;
    }

    private static String renderRecord(Object value, PrintType.Record rec, boolean dbgStyle) {
        java.util.List<PrintType.Field> fields = rec.getFields();
        if (fields.isEmpty()) {
            return "{}";
        }
        MezzeRecord record = (MezzeRecord) value;
        StringBuilder sb = new StringBuilder("{ ");
        boolean first = true;
        for (PrintType.Field f : fields) {
            if (!first) sb.append(", ");
            first = false;
            Object fv = record.getField(record.getFieldIndex(f.getName()));
            sb.append(f.getName()).append(" = ").append(render(fv, f.getType(), dbgStyle));
        }
        sb.append(" }");
        return sb.toString();
    }

    private static String renderVariant(Object value, PrintType.Variant var, boolean dbgStyle) {
        MezzeVariant variant = (MezzeVariant) value;
        String ctor = variant.getConstructorName();
        for (PrintType.Arm arm : var.getArms()) {
            if (arm.getConstructor().equals(ctor)) {
                if (arm.getPayload() == null || !variant.hasPayload()) {
                    return ctor;
                }
                return ctor + " " + render(variant.getPayloadValue(), arm.getPayload(), dbgStyle);
            }
        }
        // Constructor not in the compile-time PrintType — the value came
        // through a polymorphic catch-all arm or an open variant type.
        // Fall back to dynamic rendering so we never crash on unknown ctors.
        return valueToString(value);
    }

    private static String renderElements(Iterable<?> elems, PrintType inner, boolean dbgStyle,
                                         String open, String close) {
        StringBuilder sb = new StringBuilder(open);
        boolean first = true;
        for (Object e : elems) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(render(e, inner, dbgStyle));
        }
        sb.append(close);
        return sb.toString();
    }

    private static String renderMap(Object value, PrintType keyType, PrintType valType,
                                    boolean dbgStyle, String tag) {
        if (value instanceof MezzeMap map) {
            if (map.isEmpty()) return tag + "{}";
            StringBuilder sb = new StringBuilder(tag).append("{ ");
            boolean first = true;
            for (MezzeMap.Entry entry : (Iterable<MezzeMap.Entry>) (Object) map) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(render(entry.key(), keyType, dbgStyle))
                  .append(" = ")
                  .append(render(entry.value(), valType, dbgStyle));
            }
            sb.append(" }");
            return sb.toString();
        }
        if (value instanceof MezzeSortedMap<?, ?> map) {
            if (map.isEmpty()) return tag + "{}";
            StringBuilder sb = new StringBuilder(tag).append("{ ");
            boolean first = true;
            for (MezzeSortedMap.RawEntry entry : map.rawEntries()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(render(entry.key(), keyType, dbgStyle))
                  .append(" = ")
                  .append(render(entry.value(), valType, dbgStyle));
            }
            sb.append(" }");
            return sb.toString();
        }
        throw new NativeTypeMismatchException(
            "Inspect: expected a map runtime value, got "
                + (value == null ? "null" : value.getClass().getName()));
    }

    private static String renderFunction(Object value) {
        if (value instanceof MezzeFunction func) {
            String name = func.getName();
            return name != null ? "<function:" + name + ">" : "<function>";
        }
        throw new NativeTypeMismatchException(
            "Inspect: expected a function runtime value, got "
                + (value == null ? "null" : value.getClass().getName()));
    }

    /** Element view for the ordered collection runtime types. */
    private static Iterable<?> iterableOf(Object value) {
        if (value instanceof MezzeList list) {
            java.util.ArrayList<Object> out = new java.util.ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) out.add(list.get(i));
            return out;
        }
        if (value instanceof MezzeSeq<?> seq) {
            java.util.ArrayList<Object> out = new java.util.ArrayList<>(seq.size());
            for (int i = 0; i < seq.size(); i++) out.add(seq.get(i));
            return out;
        }
        if (value instanceof NonEmptyListNatives.MezzeNonEmptyList nel) {
            MezzeList list = NonEmptyListNatives.toList(nel);
            java.util.ArrayList<Object> out = new java.util.ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) out.add(list.get(i));
            return out;
        }
        if (value instanceof MezzeSet set) {
            return set;
        }
        if (value instanceof MezzeSortedSet set) {
            return set;
        }
        if (value instanceof MezzeDeque deque) {
            return deque;
        }
        throw new NativeTypeMismatchException(
            "Inspect: expected an ordered collection runtime value, got "
                + (value == null ? "null" : value.getClass().getName()));
    }

    /**
     * Convert a value to a human-readable string representation.
     */
    @TruffleBoundary
    public static String valueToString(Object value) {
        // Primitive types
        if (value instanceof Long l) {
            return l.toString();
        }
        if (value instanceof Double d) {
            return d.toString();
        }
        if (value instanceof Character c) {
            return "'" + c + "'";  // Char renders single-quoted
        }
        if (value instanceof String s) {
            return "\"" + s + "\"";  // Include quotes for strings
        }
        if (value instanceof Boolean b) {
            return b ? "True" : "False";
        }

        // Records - show field names and values
        // (unit is a 0-field record and renders as `{}` via this branch)
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

        // Lists — `List(1, 2, 3)` / `List()`, distinct from Seq's `[...]`.
        if (value instanceof MezzeList list) {
            StringBuilder sb = new StringBuilder("List(");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(valueToString(list.get(i)));
            }
            sb.append(")");
            return sb.toString();
        }

        // Seqs — the runtime value for a `[a, b, c]` literal. `[...]` is Seq's
        // own syntax; MezzeSeq.toString gives an opaque `<MezzeSeq>`.
        if (value instanceof MezzeSeq<?> seq) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < seq.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(valueToString(seq.get(i)));
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

        // Maps — always `Map{ .. }` / `Map{}` (keep `=` between key and value).
        if (value instanceof MezzeMap map) {
            if (map.isEmpty()) {
                return "Map{}";
            }
            StringBuilder sb = new StringBuilder("Map{ ");
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

        // Sets — always `Set{ .. }` / `Set{}`.
        if (value instanceof MezzeSet set) {
            if (set.isEmpty()) {
                return "Set{}";
            }
            StringBuilder sb = new StringBuilder("Set{");
            boolean first = true;
            for (Object elem : set) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(valueToString(elem));
            }
            sb.append("}");
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

        // No fallback: every real Mezze value type has a branch above. An
        // unknown runtime object is a compiler/native bug, not a printable
        // value — surface it rather than laundering it into `<Class>`.
        throw new NativeTypeMismatchException(
            "Inspect: no rendering for runtime value of type "
                + (value == null ? "null" : value.getClass().getName()));
    }
}
