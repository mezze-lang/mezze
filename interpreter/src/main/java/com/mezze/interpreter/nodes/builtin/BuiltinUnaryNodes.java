package com.mezze.interpreter.nodes.builtin;

import com.mezze.datastructures.list.MezzeList;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.runtime.NativeTypeMismatchException;
import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized builtin unary operations.
 * Extract field from record and perform operation WITHOUT @TruffleBoundary.
 */
public abstract class BuiltinUnaryNodes {

    // Field index for unary operations
    private static final int N_INDEX = 0;  // NumSub.neg uses 'n'
    private static final int VAL_INDEX = 0;  // boolean not uses 'val'

    /**
     * Builtin negate: {n} -> -n
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-neg")
    public static abstract class BuiltinNegNode extends MezzeNode {

        @Specialization(guards = "isLongRecord(args)")
        protected long negateLong(MezzeRecord args) {
            return -args.getLongField(N_INDEX);
        }

        @Specialization(guards = "isDoubleRecord(args)")
        protected double negateDouble(MezzeRecord args) {
            return -args.getDoubleField(N_INDEX);
        }

        @Specialization(replaces = {"negateLong", "negateDouble"})
        protected Object negateGeneric(MezzeRecord args) {
            Object n = args.getObjectField(N_INDEX);
            if (n instanceof Long) {
                return -((Long) n);
            }
            if (n instanceof Double) {
                return -((Double) n);
            }
            throw new NativeTypeMismatchException("[internal] operand shape not handled by builtin negate node — cannot negate " + n);
        }

        protected static boolean isLongRecord(MezzeRecord args) {
            return args.getLongFieldCount() >= 1;
        }

        protected static boolean isDoubleRecord(MezzeRecord args) {
            return args.getDoubleFieldCount() >= 1;
        }

        public static BuiltinNegNode create(MezzeNode argsNode) {
            return BuiltinUnaryNodesFactory.BuiltinNegNodeGen.create(argsNode);
        }
    }

    /**
     * Builtin not: {val} -> !val
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-not")
    public static abstract class BuiltinNotNode extends MezzeNode {

        @Specialization
        protected boolean notBool(MezzeRecord args) {
            Object val = args.getObjectField(VAL_INDEX);
            return !((Boolean) val);
        }

        public static BuiltinNotNode create(MezzeNode argsNode) {
            return BuiltinUnaryNodesFactory.BuiltinNotNodeGen.create(argsNode);
        }
    }

    /**
     * Builtin Inspect.to_str: {val} -> String representation of val
     *
     * Converts any Mezze value to its string representation.
     * This is similar to toString() but works with all Mezze types.
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-inspect-to-str")
    public static abstract class BuiltinInspectToStrNode extends MezzeNode {

        @Specialization
        @TruffleBoundary
        protected String inspectToStr(MezzeRecord args) {
            Object val = args.getObjectField(VAL_INDEX);
            return valueToString(val);
        }

        @TruffleBoundary
        private static String valueToString(Object value) {
            if (value == null) {
                return "null";
            }

            // Primitive types
            if (value instanceof Long) {
                return value.toString();
            }
            if (value instanceof Double) {
                return value.toString();
            }
            if (value instanceof Boolean) {
                return ((Boolean) value) ? "True" : "False";
            }
            if (value instanceof String) {
                return "\"" + value + "\"";
            }

            // Unit
            if (value == MezzeUnit.INSTANCE) {
                return "{}";
            }

            // Record
            if (value instanceof MezzeRecord record) {
                int fieldCount = record.getFieldCount();
                if (fieldCount == 0) {
                    return "{}";
                }
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

            // Variant
            if (value instanceof MezzeVariant variant) {
                if (!variant.hasPayload()) {
                    return variant.getConstructorName();
                }
                return variant.getConstructorName() + " " + valueToString(variant.getPayloadValue());
            }

            // List
            if (value instanceof MezzeList list) {
                int size = list.size();
                if (size == 0) {
                    return "[]";
                }
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

            // Raw Object[] array (used for some list literals)
            if (value instanceof Object[] array) {
                if (array.length == 0) {
                    return "[]";
                }
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

            // Function
            if (value instanceof MezzeFunction func) {
                String name = func.getName();
                return name != null ? "<function:" + name + ">" : "<function>";
            }

            // Fallback
            return value.toString();
        }

        public static BuiltinInspectToStrNode create(MezzeNode argsNode) {
            return BuiltinUnaryNodesFactory.BuiltinInspectToStrNodeGen.create(argsNode);
        }
    }
}
