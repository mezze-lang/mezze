package com.mezze.interpreter.nodes.builtin;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.runtime.NativeTypeMismatchException;
import com.mezze.interpreter.values.*;
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

    // BuiltinInspectToStrNode was removed (#394): it was dead (no wired
    // caller) and duplicated the sole renderer InspectNatives.valueToString.
}
