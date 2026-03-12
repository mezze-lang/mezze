package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized type conversion nodes.
 *
 * Single instruction conversions for primitive types.
 */
public final class ConversionNode {

    /**
     * Long to double conversion.
     */
    @NodeInfo(shortName = "conv.longToDouble")
    public static abstract class LongToDoubleNode extends Node {
        public abstract double execute(long value);

        @Specialization
        protected double convert(long value) {
            return (double) value;
        }

        public static LongToDoubleNode create() {
            return ConversionNodeFactory.LongToDoubleNodeGen.create();
        }
    }

    /**
     * Double to long conversion (truncates toward zero).
     */
    @NodeInfo(shortName = "conv.doubleToLong")
    public static abstract class DoubleToLongNode extends Node {
        public abstract long execute(double value);

        @Specialization
        protected long convert(double value) {
            return (long) value;
        }

        public static DoubleToLongNode create() {
            return ConversionNodeFactory.DoubleToLongNodeGen.create();
        }
    }

    /**
     * Long to int conversion (truncates).
     */
    @NodeInfo(shortName = "conv.longToInt")
    public static abstract class LongToIntNode extends Node {
        public abstract int execute(long value);

        @Specialization
        protected int convert(long value) {
            return (int) value;
        }

        public static LongToIntNode create() {
            return ConversionNodeFactory.LongToIntNodeGen.create();
        }
    }

    /**
     * Int to long conversion (sign-extends).
     */
    @NodeInfo(shortName = "conv.intToLong")
    public static abstract class IntToLongNode extends Node {
        public abstract long execute(int value);

        @Specialization
        protected long convert(int value) {
            return value;
        }

        public static IntToLongNode create() {
            return ConversionNodeFactory.IntToLongNodeGen.create();
        }
    }

    /**
     * Boolean to long conversion (false=0, true=1).
     */
    @NodeInfo(shortName = "conv.boolToLong")
    public static abstract class BoolToLongNode extends Node {
        public abstract long execute(boolean value);

        @Specialization
        protected long convert(boolean value) {
            return value ? 1L : 0L;
        }

        public static BoolToLongNode create() {
            return ConversionNodeFactory.BoolToLongNodeGen.create();
        }
    }

    /**
     * Long to boolean conversion (0=false, non-zero=true).
     */
    @NodeInfo(shortName = "conv.longToBool")
    public static abstract class LongToBoolNode extends Node {
        public abstract boolean execute(long value);

        @Specialization
        protected boolean convert(long value) {
            return value != 0;
        }

        public static LongToBoolNode create() {
            return ConversionNodeFactory.LongToBoolNodeGen.create();
        }
    }

    /**
     * Char to long conversion.
     */
    @NodeInfo(shortName = "conv.charToLong")
    public static abstract class CharToLongNode extends Node {
        public abstract long execute(char value);

        @Specialization
        protected long convert(char value) {
            return value;
        }

        public static CharToLongNode create() {
            return ConversionNodeFactory.CharToLongNodeGen.create();
        }
    }

    /**
     * Long to char conversion.
     */
    @NodeInfo(shortName = "conv.longToChar")
    public static abstract class LongToCharNode extends Node {
        public abstract char execute(long value);

        @Specialization
        protected char convert(long value) {
            return (char) value;
        }

        public static LongToCharNode create() {
            return ConversionNodeFactory.LongToCharNodeGen.create();
        }
    }

    /**
     * Double bits to long (reinterpret).
     */
    @NodeInfo(shortName = "conv.doubleBitsToLong")
    public static abstract class DoubleBitsToLongNode extends Node {
        public abstract long execute(double value);

        @Specialization
        protected long convert(double value) {
            return Double.doubleToRawLongBits(value);
        }

        public static DoubleBitsToLongNode create() {
            return ConversionNodeFactory.DoubleBitsToLongNodeGen.create();
        }
    }

    /**
     * Long bits to double (reinterpret).
     */
    @NodeInfo(shortName = "conv.longBitsToDouble")
    public static abstract class LongBitsToDoubleNode extends Node {
        public abstract double execute(long value);

        @Specialization
        protected double convert(long value) {
            return Double.longBitsToDouble(value);
        }

        public static LongBitsToDoubleNode create() {
            return ConversionNodeFactory.LongBitsToDoubleNodeGen.create();
        }
    }

    private ConversionNode() {
        // Utility class
    }
}
