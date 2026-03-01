package com.mezze.interpreter.v2.nodes.builtin;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;

/**
 * Optimized builtin binary operations that extract {l, r} from record
 * and perform the operation WITHOUT @TruffleBoundary.
 *
 * These nodes take a record argument node and extract l/r fields for typed operations.
 * Field access is optimized using the record's typed field arrays.
 */

/**
 * Base class for builtin binary operations.
 * Extracts l and r from MezzeRecord argument efficiently.
 */
@NodeChild(value = "args", type = MezzeNode.class)
@NodeInfo(shortName = "builtin-add")
public abstract class BuiltinBinaryNodes {

    // Field indices for {l, r} record - always 0 and 1
    private static final int L_INDEX = 0;
    private static final int R_INDEX = 1;

    /**
     * Builtin add: {l, r} -> l + r
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-add")
    public static abstract class BuiltinAddNode extends MezzeNode {

        @Specialization(guards = "isLongLongRecord(args)")
        protected long addLongLong(MezzeRecord args) {
            return args.getLongField(L_INDEX) + args.getLongField(R_INDEX);
        }

        @Specialization(guards = "isDoubleDoubleRecord(args)", replaces = "addLongLong")
        protected double addDoubleDouble(MezzeRecord args) {
            return args.getDoubleField(L_INDEX) + args.getDoubleField(R_INDEX);
        }

        @Specialization(replaces = {"addLongLong", "addDoubleDouble"})
        protected Object addGeneric(MezzeRecord args) {
            Object l = args.getObjectField(L_INDEX);
            Object r = args.getObjectField(R_INDEX);

            if (l instanceof Long && r instanceof Long) {
                return (Long) l + (Long) r;
            }
            if (l instanceof Double && r instanceof Double) {
                return (Double) l + (Double) r;
            }
            if (l instanceof Long && r instanceof Double) {
                return ((Long) l).doubleValue() + (Double) r;
            }
            if (l instanceof Double && r instanceof Long) {
                return (Double) l + ((Long) r).doubleValue();
            }
            if (l instanceof String || r instanceof String) {
                return String.valueOf(l) + String.valueOf(r);
            }
            throw new RuntimeException("Cannot add " + l + " and " + r);
        }

        protected static boolean isLongLongRecord(MezzeRecord args) {
            return args.getLongFieldCount() >= 2;
        }

        protected static boolean isDoubleDoubleRecord(MezzeRecord args) {
            return args.getDoubleFieldCount() >= 2;
        }

        public static BuiltinAddNode create(MezzeNode argsNode) {
            return BuiltinBinaryNodesFactory.BuiltinAddNodeGen.create(argsNode);
        }
    }

    /**
     * Builtin subtract: {l, r} -> l - r
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-sub")
    public static abstract class BuiltinSubNode extends MezzeNode {

        @Specialization(guards = "isLongLongRecord(args)")
        protected long subLongLong(MezzeRecord args) {
            return args.getLongField(L_INDEX) - args.getLongField(R_INDEX);
        }

        @Specialization(guards = "isDoubleDoubleRecord(args)", replaces = "subLongLong")
        protected double subDoubleDouble(MezzeRecord args) {
            return args.getDoubleField(L_INDEX) - args.getDoubleField(R_INDEX);
        }

        @Specialization(replaces = {"subLongLong", "subDoubleDouble"})
        protected Object subGeneric(MezzeRecord args) {
            Object l = args.getObjectField(L_INDEX);
            Object r = args.getObjectField(R_INDEX);

            if (l instanceof Long && r instanceof Long) {
                return (Long) l - (Long) r;
            }
            if (l instanceof Double && r instanceof Double) {
                return (Double) l - (Double) r;
            }
            if (l instanceof Long && r instanceof Double) {
                return ((Long) l).doubleValue() - (Double) r;
            }
            if (l instanceof Double && r instanceof Long) {
                return (Double) l - ((Long) r).doubleValue();
            }
            throw new RuntimeException("Cannot subtract " + l + " and " + r);
        }

        protected static boolean isLongLongRecord(MezzeRecord args) {
            return args.getLongFieldCount() >= 2;
        }

        protected static boolean isDoubleDoubleRecord(MezzeRecord args) {
            return args.getDoubleFieldCount() >= 2;
        }

        public static BuiltinSubNode create(MezzeNode argsNode) {
            return BuiltinBinaryNodesFactory.BuiltinSubNodeGen.create(argsNode);
        }
    }

    /**
     * Builtin multiply: {l, r} -> l * r
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-mul")
    public static abstract class BuiltinMulNode extends MezzeNode {

        @Specialization(guards = "isLongLongRecord(args)")
        protected long mulLongLong(MezzeRecord args) {
            return args.getLongField(L_INDEX) * args.getLongField(R_INDEX);
        }

        @Specialization(guards = "isDoubleDoubleRecord(args)", replaces = "mulLongLong")
        protected double mulDoubleDouble(MezzeRecord args) {
            return args.getDoubleField(L_INDEX) * args.getDoubleField(R_INDEX);
        }

        @Specialization(replaces = {"mulLongLong", "mulDoubleDouble"})
        protected Object mulGeneric(MezzeRecord args) {
            Object l = args.getObjectField(L_INDEX);
            Object r = args.getObjectField(R_INDEX);

            if (l instanceof Long && r instanceof Long) {
                return (Long) l * (Long) r;
            }
            if (l instanceof Double && r instanceof Double) {
                return (Double) l * (Double) r;
            }
            if (l instanceof Long && r instanceof Double) {
                return ((Long) l).doubleValue() * (Double) r;
            }
            if (l instanceof Double && r instanceof Long) {
                return (Double) l * ((Long) r).doubleValue();
            }
            throw new RuntimeException("Cannot multiply " + l + " and " + r);
        }

        protected static boolean isLongLongRecord(MezzeRecord args) {
            return args.getLongFieldCount() >= 2;
        }

        protected static boolean isDoubleDoubleRecord(MezzeRecord args) {
            return args.getDoubleFieldCount() >= 2;
        }

        public static BuiltinMulNode create(MezzeNode argsNode) {
            return BuiltinBinaryNodesFactory.BuiltinMulNodeGen.create(argsNode);
        }
    }

    /**
     * Builtin divide: {l, r} -> l / r
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-div")
    public static abstract class BuiltinDivNode extends MezzeNode {

        @Specialization(guards = "isLongLongRecord(args)")
        protected long divLongLong(MezzeRecord args) {
            return args.getLongField(L_INDEX) / args.getLongField(R_INDEX);
        }

        @Specialization(guards = "isDoubleDoubleRecord(args)", replaces = "divLongLong")
        protected double divDoubleDouble(MezzeRecord args) {
            return args.getDoubleField(L_INDEX) / args.getDoubleField(R_INDEX);
        }

        @Specialization(replaces = {"divLongLong", "divDoubleDouble"})
        protected Object divGeneric(MezzeRecord args) {
            Object l = args.getObjectField(L_INDEX);
            Object r = args.getObjectField(R_INDEX);

            if (l instanceof Long && r instanceof Long) {
                return (Long) l / (Long) r;
            }
            if (l instanceof Double && r instanceof Double) {
                return (Double) l / (Double) r;
            }
            if (l instanceof Long && r instanceof Double) {
                return ((Long) l).doubleValue() / (Double) r;
            }
            if (l instanceof Double && r instanceof Long) {
                return (Double) l / ((Long) r).doubleValue();
            }
            throw new RuntimeException("Cannot divide " + l + " and " + r);
        }

        protected static boolean isLongLongRecord(MezzeRecord args) {
            return args.getLongFieldCount() >= 2;
        }

        protected static boolean isDoubleDoubleRecord(MezzeRecord args) {
            return args.getDoubleFieldCount() >= 2;
        }

        public static BuiltinDivNode create(MezzeNode argsNode) {
            return BuiltinBinaryNodesFactory.BuiltinDivNodeGen.create(argsNode);
        }
    }

    /**
     * Builtin modulo: {l, r} -> l % r
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-mod")
    public static abstract class BuiltinModNode extends MezzeNode {

        @Specialization(guards = "isLongLongRecord(args)")
        protected long modLongLong(MezzeRecord args) {
            return args.getLongField(L_INDEX) % args.getLongField(R_INDEX);
        }

        @Specialization(guards = "isDoubleDoubleRecord(args)", replaces = "modLongLong")
        protected double modDoubleDouble(MezzeRecord args) {
            return args.getDoubleField(L_INDEX) % args.getDoubleField(R_INDEX);
        }

        @Specialization(replaces = {"modLongLong", "modDoubleDouble"})
        protected Object modGeneric(MezzeRecord args) {
            Object l = args.getObjectField(L_INDEX);
            Object r = args.getObjectField(R_INDEX);

            if (l instanceof Long && r instanceof Long) {
                return (Long) l % (Long) r;
            }
            if (l instanceof Double && r instanceof Double) {
                return (Double) l % (Double) r;
            }
            if (l instanceof Long && r instanceof Double) {
                return ((Long) l).doubleValue() % (Double) r;
            }
            if (l instanceof Double && r instanceof Long) {
                return (Double) l % ((Long) r).doubleValue();
            }
            throw new RuntimeException("Cannot modulo " + l + " and " + r);
        }

        protected static boolean isLongLongRecord(MezzeRecord args) {
            return args.getLongFieldCount() >= 2;
        }

        protected static boolean isDoubleDoubleRecord(MezzeRecord args) {
            return args.getDoubleFieldCount() >= 2;
        }

        public static BuiltinModNode create(MezzeNode argsNode) {
            return BuiltinBinaryNodesFactory.BuiltinModNodeGen.create(argsNode);
        }
    }

    /**
     * Builtin equal: {l, r} -> l == r
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-eq")
    public static abstract class BuiltinEqNode extends MezzeNode {

        @Specialization(guards = "isLongLongRecord(args)")
        protected boolean eqLongLong(MezzeRecord args) {
            return args.getLongField(L_INDEX) == args.getLongField(R_INDEX);
        }

        @Specialization(guards = "isDoubleDoubleRecord(args)")
        protected boolean eqDoubleDouble(MezzeRecord args) {
            return args.getDoubleField(L_INDEX) == args.getDoubleField(R_INDEX);
        }

        @Specialization(replaces = {"eqLongLong", "eqDoubleDouble"})
        protected boolean eqGeneric(MezzeRecord args) {
            Object l = args.getObjectField(L_INDEX);
            Object r = args.getObjectField(R_INDEX);
            return java.util.Objects.equals(l, r);
        }

        protected static boolean isLongLongRecord(MezzeRecord args) {
            return args.getLongFieldCount() >= 2;
        }

        protected static boolean isDoubleDoubleRecord(MezzeRecord args) {
            return args.getDoubleFieldCount() >= 2;
        }

        public static BuiltinEqNode create(MezzeNode argsNode) {
            return BuiltinBinaryNodesFactory.BuiltinEqNodeGen.create(argsNode);
        }
    }

    /**
     * Builtin not-equal: {l, r} -> l != r
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-neq")
    public static abstract class BuiltinNeqNode extends MezzeNode {

        @Specialization(guards = "isLongLongRecord(args)")
        protected boolean neqLongLong(MezzeRecord args) {
            return args.getLongField(L_INDEX) != args.getLongField(R_INDEX);
        }

        @Specialization(guards = "isDoubleDoubleRecord(args)")
        protected boolean neqDoubleDouble(MezzeRecord args) {
            return args.getDoubleField(L_INDEX) != args.getDoubleField(R_INDEX);
        }

        @Specialization(replaces = {"neqLongLong", "neqDoubleDouble"})
        protected boolean neqGeneric(MezzeRecord args) {
            Object l = args.getObjectField(L_INDEX);
            Object r = args.getObjectField(R_INDEX);
            return !java.util.Objects.equals(l, r);
        }

        protected static boolean isLongLongRecord(MezzeRecord args) {
            return args.getLongFieldCount() >= 2;
        }

        protected static boolean isDoubleDoubleRecord(MezzeRecord args) {
            return args.getDoubleFieldCount() >= 2;
        }

        public static BuiltinNeqNode create(MezzeNode argsNode) {
            return BuiltinBinaryNodesFactory.BuiltinNeqNodeGen.create(argsNode);
        }
    }

    /**
     * Builtin less-than: {l, r} -> l < r
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-lt")
    public static abstract class BuiltinLtNode extends MezzeNode {

        @Specialization(guards = "isLongLongRecord(args)")
        protected boolean ltLongLong(MezzeRecord args) {
            return args.getLongField(L_INDEX) < args.getLongField(R_INDEX);
        }

        @Specialization(guards = "isDoubleDoubleRecord(args)")
        protected boolean ltDoubleDouble(MezzeRecord args) {
            return args.getDoubleField(L_INDEX) < args.getDoubleField(R_INDEX);
        }

        @Specialization(replaces = {"ltLongLong", "ltDoubleDouble"})
        protected boolean ltGeneric(MezzeRecord args) {
            Object l = args.getObjectField(L_INDEX);
            Object r = args.getObjectField(R_INDEX);
            return compareGeneric(l, r) < 0;
        }

        @SuppressWarnings("unchecked")
        private static int compareGeneric(Object l, Object r) {
            if (l instanceof Long && r instanceof Long) {
                return Long.compare((Long) l, (Long) r);
            }
            if (l instanceof Double && r instanceof Double) {
                return Double.compare((Double) l, (Double) r);
            }
            if (l instanceof Comparable && r != null) {
                return ((Comparable<Object>) l).compareTo(r);
            }
            throw new RuntimeException("Cannot compare " + l + " and " + r);
        }

        protected static boolean isLongLongRecord(MezzeRecord args) {
            return args.getLongFieldCount() >= 2;
        }

        protected static boolean isDoubleDoubleRecord(MezzeRecord args) {
            return args.getDoubleFieldCount() >= 2;
        }

        public static BuiltinLtNode create(MezzeNode argsNode) {
            return BuiltinBinaryNodesFactory.BuiltinLtNodeGen.create(argsNode);
        }
    }

    /**
     * Builtin greater-than: {l, r} -> l > r
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-gt")
    public static abstract class BuiltinGtNode extends MezzeNode {

        @Specialization(guards = "isLongLongRecord(args)")
        protected boolean gtLongLong(MezzeRecord args) {
            return args.getLongField(L_INDEX) > args.getLongField(R_INDEX);
        }

        @Specialization(guards = "isDoubleDoubleRecord(args)")
        protected boolean gtDoubleDouble(MezzeRecord args) {
            return args.getDoubleField(L_INDEX) > args.getDoubleField(R_INDEX);
        }

        @Specialization(replaces = {"gtLongLong", "gtDoubleDouble"})
        protected boolean gtGeneric(MezzeRecord args) {
            Object l = args.getObjectField(L_INDEX);
            Object r = args.getObjectField(R_INDEX);
            return compareGeneric(l, r) > 0;
        }

        @SuppressWarnings("unchecked")
        private static int compareGeneric(Object l, Object r) {
            if (l instanceof Long && r instanceof Long) {
                return Long.compare((Long) l, (Long) r);
            }
            if (l instanceof Double && r instanceof Double) {
                return Double.compare((Double) l, (Double) r);
            }
            if (l instanceof Comparable && r != null) {
                return ((Comparable<Object>) l).compareTo(r);
            }
            throw new RuntimeException("Cannot compare " + l + " and " + r);
        }

        protected static boolean isLongLongRecord(MezzeRecord args) {
            return args.getLongFieldCount() >= 2;
        }

        protected static boolean isDoubleDoubleRecord(MezzeRecord args) {
            return args.getDoubleFieldCount() >= 2;
        }

        public static BuiltinGtNode create(MezzeNode argsNode) {
            return BuiltinBinaryNodesFactory.BuiltinGtNodeGen.create(argsNode);
        }
    }

    /**
     * Builtin less-equal: {l, r} -> l <= r
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-le")
    public static abstract class BuiltinLeNode extends MezzeNode {

        @Specialization(guards = "isLongLongRecord(args)")
        protected boolean leLongLong(MezzeRecord args) {
            return args.getLongField(L_INDEX) <= args.getLongField(R_INDEX);
        }

        @Specialization(guards = "isDoubleDoubleRecord(args)")
        protected boolean leDoubleDouble(MezzeRecord args) {
            return args.getDoubleField(L_INDEX) <= args.getDoubleField(R_INDEX);
        }

        @Specialization(replaces = {"leLongLong", "leDoubleDouble"})
        protected boolean leGeneric(MezzeRecord args) {
            Object l = args.getObjectField(L_INDEX);
            Object r = args.getObjectField(R_INDEX);
            return compareGeneric(l, r) <= 0;
        }

        @SuppressWarnings("unchecked")
        private static int compareGeneric(Object l, Object r) {
            if (l instanceof Long && r instanceof Long) {
                return Long.compare((Long) l, (Long) r);
            }
            if (l instanceof Double && r instanceof Double) {
                return Double.compare((Double) l, (Double) r);
            }
            if (l instanceof Comparable && r != null) {
                return ((Comparable<Object>) l).compareTo(r);
            }
            throw new RuntimeException("Cannot compare " + l + " and " + r);
        }

        protected static boolean isLongLongRecord(MezzeRecord args) {
            return args.getLongFieldCount() >= 2;
        }

        protected static boolean isDoubleDoubleRecord(MezzeRecord args) {
            return args.getDoubleFieldCount() >= 2;
        }

        public static BuiltinLeNode create(MezzeNode argsNode) {
            return BuiltinBinaryNodesFactory.BuiltinLeNodeGen.create(argsNode);
        }
    }

    /**
     * Builtin greater-equal: {l, r} -> l >= r
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-ge")
    public static abstract class BuiltinGeNode extends MezzeNode {

        @Specialization(guards = "isLongLongRecord(args)")
        protected boolean geLongLong(MezzeRecord args) {
            return args.getLongField(L_INDEX) >= args.getLongField(R_INDEX);
        }

        @Specialization(guards = "isDoubleDoubleRecord(args)")
        protected boolean geDoubleDouble(MezzeRecord args) {
            return args.getDoubleField(L_INDEX) >= args.getDoubleField(R_INDEX);
        }

        @Specialization(replaces = {"geLongLong", "geDoubleDouble"})
        protected boolean geGeneric(MezzeRecord args) {
            Object l = args.getObjectField(L_INDEX);
            Object r = args.getObjectField(R_INDEX);
            return compareGeneric(l, r) >= 0;
        }

        @SuppressWarnings("unchecked")
        private static int compareGeneric(Object l, Object r) {
            if (l instanceof Long && r instanceof Long) {
                return Long.compare((Long) l, (Long) r);
            }
            if (l instanceof Double && r instanceof Double) {
                return Double.compare((Double) l, (Double) r);
            }
            if (l instanceof Comparable && r != null) {
                return ((Comparable<Object>) l).compareTo(r);
            }
            throw new RuntimeException("Cannot compare " + l + " and " + r);
        }

        protected static boolean isLongLongRecord(MezzeRecord args) {
            return args.getLongFieldCount() >= 2;
        }

        protected static boolean isDoubleDoubleRecord(MezzeRecord args) {
            return args.getDoubleFieldCount() >= 2;
        }

        public static BuiltinGeNode create(MezzeNode argsNode) {
            return BuiltinBinaryNodesFactory.BuiltinGeNodeGen.create(argsNode);
        }
    }

    /**
     * Builtin logical AND: {l, r} -> l && r
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-and")
    public static abstract class BuiltinAndNode extends MezzeNode {

        @Specialization
        protected boolean andBool(MezzeRecord args) {
            Object l = args.getObjectField(L_INDEX);
            Object r = args.getObjectField(R_INDEX);
            return ((Boolean) l) && ((Boolean) r);
        }

        public static BuiltinAndNode create(MezzeNode argsNode) {
            return BuiltinBinaryNodesFactory.BuiltinAndNodeGen.create(argsNode);
        }
    }

    /**
     * Builtin logical OR: {l, r} -> l || r
     */
    @NodeChild(value = "args", type = MezzeNode.class)
    @NodeInfo(shortName = "builtin-or")
    public static abstract class BuiltinOrNode extends MezzeNode {

        @Specialization
        protected boolean orBool(MezzeRecord args) {
            Object l = args.getObjectField(L_INDEX);
            Object r = args.getObjectField(R_INDEX);
            return ((Boolean) l) || ((Boolean) r);
        }

        public static BuiltinOrNode create(MezzeNode argsNode) {
            return BuiltinBinaryNodesFactory.BuiltinOrNodeGen.create(argsNode);
        }
    }
}
