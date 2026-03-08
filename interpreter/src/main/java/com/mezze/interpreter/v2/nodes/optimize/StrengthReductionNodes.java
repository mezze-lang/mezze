package com.mezze.interpreter.v2.nodes.optimize;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.nodes.literal.IntLiteralNode;
import com.mezze.interpreter.v2.nodes.literal.FloatLiteralNode;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * STRENGTH REDUCTION optimizations.
 *
 * These nodes implement algebraic optimizations that transform
 * expensive operations into cheaper equivalents:
 *
 * 1. x * 2 → x << 1 (shift instead of multiply)
 * 2. x * 4 → x << 2
 * 3. x / 2 → x >> 1 (for positive integers)
 * 4. x % 2 → x & 1
 * 5. x * 0 → 0
 * 6. x * 1 → x
 * 7. x + 0 → x
 * 8. x - 0 → x
 *
 * The JIT would do some of these anyway, but doing them early:
 * - Reduces interpreter overhead
 * - Enables further constant folding
 * - Makes the node tree smaller
 */
public final class StrengthReductionNodes {

    private StrengthReductionNodes() {}

    // ==================== OPTIMIZED MULTIPLY ====================

    /**
     * Multiply with strength reduction.
     * Detects powers of 2 and uses bit shifts.
     */
    @NodeChild(value = "leftNode", type = MezzeNode.class)
    @NodeChild(value = "rightNode", type = MezzeNode.class)
    @NodeInfo(shortName = "opt-mul")
    public abstract static class OptimizedMulNode extends MezzeNode {

        /**
         * x * 0 = 0
         */
        @Specialization(guards = "right == 0")
        protected long mulByZero(long left, long right) {
            return 0L;
        }

        /**
         * x * 1 = x
         */
        @Specialization(guards = "right == 1")
        protected long mulByOne(long left, long right) {
            return left;
        }

        /**
         * x * 2 = x << 1
         */
        @Specialization(guards = "right == 2")
        protected long mulByTwo(long left, long right) {
            return left << 1;
        }

        /**
         * x * 4 = x << 2
         */
        @Specialization(guards = "right == 4")
        protected long mulByFour(long left, long right) {
            return left << 2;
        }

        /**
         * x * 8 = x << 3
         */
        @Specialization(guards = "right == 8")
        protected long mulByEight(long left, long right) {
            return left << 3;
        }

        /**
         * x * 16 = x << 4
         */
        @Specialization(guards = "right == 16")
        protected long mulBySixteen(long left, long right) {
            return left << 4;
        }

        /**
         * x * power_of_2 = x << log2(power_of_2)
         */
        @Specialization(guards = "isPowerOfTwo(right)")
        protected long mulByPowerOfTwo(long left, long right) {
            int shift = Long.numberOfTrailingZeros(right);
            return left << shift;
        }

        /**
         * General multiplication.
         */
        @Specialization(replaces = {"mulByZero", "mulByOne", "mulByTwo", "mulByFour",
                                    "mulByEight", "mulBySixteen", "mulByPowerOfTwo"})
        protected long mulGeneral(long left, long right) {
            return left * right;
        }

        /**
         * Double multiplication (no strength reduction).
         */
        @Specialization
        protected double mulDoubles(double left, double right) {
            return left * right;
        }

        protected static boolean isPowerOfTwo(long n) {
            return n > 0 && (n & (n - 1)) == 0;
        }

        public static OptimizedMulNode create(MezzeNode left, MezzeNode right) {
            return StrengthReductionNodesFactory.OptimizedMulNodeGen.create(left, right);
        }
    }

    // ==================== OPTIMIZED DIVIDE ====================

    /**
     * Division with strength reduction.
     * Detects powers of 2 and uses bit shifts for positive integers.
     */
    @NodeChild(value = "leftNode", type = MezzeNode.class)
    @NodeChild(value = "rightNode", type = MezzeNode.class)
    @NodeInfo(shortName = "opt-div")
    public abstract static class OptimizedDivNode extends MezzeNode {

        /**
         * x / 1 = x
         */
        @Specialization(guards = "right == 1")
        protected long divByOne(long left, long right) {
            return left;
        }

        /**
         * x / 2 = x >> 1 (arithmetic right shift preserves sign)
         * Note: This is exact for positive even numbers, rounds toward -inf for negatives.
         */
        @Specialization(guards = {"right == 2", "left >= 0"})
        protected long divByTwoPositive(long left, long right) {
            return left >> 1;
        }

        /**
         * x / 4 = x >> 2 (for non-negative)
         */
        @Specialization(guards = {"right == 4", "left >= 0"})
        protected long divByFourPositive(long left, long right) {
            return left >> 2;
        }

        /**
         * x / power_of_2 = x >> log2(power_of_2) (for non-negative)
         */
        @Specialization(guards = {"isPowerOfTwo(right)", "left >= 0"})
        protected long divByPowerOfTwoPositive(long left, long right) {
            int shift = Long.numberOfTrailingZeros(right);
            return left >> shift;
        }

        /**
         * General division.
         */
        @Specialization(replaces = {"divByOne", "divByTwoPositive", "divByFourPositive", "divByPowerOfTwoPositive"})
        protected long divGeneral(long left, long right) {
            return left / right;
        }

        @Specialization
        protected double divDoubles(double left, double right) {
            return left / right;
        }

        protected static boolean isPowerOfTwo(long n) {
            return n > 0 && (n & (n - 1)) == 0;
        }

        public static OptimizedDivNode create(MezzeNode left, MezzeNode right) {
            return StrengthReductionNodesFactory.OptimizedDivNodeGen.create(left, right);
        }
    }

    // ==================== OPTIMIZED MODULO ====================

    /**
     * Modulo with strength reduction.
     * x % power_of_2 = x & (power_of_2 - 1)
     */
    @NodeChild(value = "leftNode", type = MezzeNode.class)
    @NodeChild(value = "rightNode", type = MezzeNode.class)
    @NodeInfo(shortName = "opt-mod")
    public abstract static class OptimizedModNode extends MezzeNode {

        /**
         * x % 2 = x & 1
         */
        @Specialization(guards = {"right == 2", "left >= 0"})
        protected long modByTwo(long left, long right) {
            return left & 1L;
        }

        /**
         * x % 4 = x & 3
         */
        @Specialization(guards = {"right == 4", "left >= 0"})
        protected long modByFour(long left, long right) {
            return left & 3L;
        }

        /**
         * x % 8 = x & 7
         */
        @Specialization(guards = {"right == 8", "left >= 0"})
        protected long modByEight(long left, long right) {
            return left & 7L;
        }

        /**
         * x % power_of_2 = x & (power_of_2 - 1) for non-negative
         */
        @Specialization(guards = {"isPowerOfTwo(right)", "left >= 0"})
        protected long modByPowerOfTwo(long left, long right) {
            return left & (right - 1);
        }

        /**
         * General modulo.
         */
        @Specialization(replaces = {"modByTwo", "modByFour", "modByEight", "modByPowerOfTwo"})
        protected long modGeneral(long left, long right) {
            return left % right;
        }

        protected static boolean isPowerOfTwo(long n) {
            return n > 0 && (n & (n - 1)) == 0;
        }

        public static OptimizedModNode create(MezzeNode left, MezzeNode right) {
            return StrengthReductionNodesFactory.OptimizedModNodeGen.create(left, right);
        }
    }

    // ==================== CONSTANT FOLDING ====================

    /**
     * Constant folding node - evaluates constant expressions at compile time.
     */
    @NodeInfo(shortName = "const-fold")
    public static final class ConstantFoldNode extends MezzeNode {

        @CompilationFinal
        private final Object constantValue;

        public ConstantFoldNode(Object constantValue) {
            this.constantValue = constantValue;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return constantValue;
        }

        @Override
        public long executeLong(VirtualFrame frame) {
            if (constantValue instanceof Long l) {
                return l;
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Expected long constant");
        }

        @Override
        public double executeDouble(VirtualFrame frame) {
            if (constantValue instanceof Double d) {
                return d;
            }
            if (constantValue instanceof Long l) {
                return l.doubleValue();
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Expected double constant");
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) {
            if (constantValue instanceof Boolean b) {
                return b;
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Expected boolean constant");
        }

        public static ConstantFoldNode create(Object value) {
            return new ConstantFoldNode(value);
        }

        /**
         * Try to fold a binary operation on two constants.
         * Returns null if folding is not possible.
         */
        public static MezzeNode tryFoldBinary(String op, MezzeNode left, MezzeNode right) {
            // Check if both are constants
            Long leftConst = extractLongConstant(left);
            Long rightConst = extractLongConstant(right);

            if (leftConst != null && rightConst != null) {
                Long result = switch (op) {
                    case "+" -> leftConst + rightConst;
                    case "-" -> leftConst - rightConst;
                    case "*" -> leftConst * rightConst;
                    case "/" -> rightConst != 0 ? leftConst / rightConst : null;
                    case "%" -> rightConst != 0 ? leftConst % rightConst : null;
                    default -> null;
                };
                if (result != null) {
                    return new ConstantFoldNode(result);
                }
            }

            // Try double constants
            Double leftDouble = extractDoubleConstant(left);
            Double rightDouble = extractDoubleConstant(right);

            if (leftDouble != null && rightDouble != null) {
                double result = switch (op) {
                    case "+" -> leftDouble + rightDouble;
                    case "-" -> leftDouble - rightDouble;
                    case "*" -> leftDouble * rightDouble;
                    case "/" -> leftDouble / rightDouble;
                    default -> Double.NaN;
                };
                if (!Double.isNaN(result)) {
                    return new ConstantFoldNode(result);
                }
            }

            return null;  // Cannot fold
        }

        private static Long extractLongConstant(MezzeNode node) {
            if (node instanceof IntLiteralNode lit) {
                // Access the value - need to execute with null frame for literals
                try {
                    return lit.executeLong(null);
                } catch (Exception e) {
                    return null;
                }
            }
            if (node instanceof ConstantFoldNode fold && fold.constantValue instanceof Long l) {
                return l;
            }
            return null;
        }

        private static Double extractDoubleConstant(MezzeNode node) {
            if (node instanceof FloatLiteralNode lit) {
                try {
                    return lit.executeDouble(null);
                } catch (Exception e) {
                    return null;
                }
            }
            if (node instanceof ConstantFoldNode fold && fold.constantValue instanceof Double d) {
                return d;
            }
            return null;
        }
    }

    // ==================== ALGEBRAIC IDENTITY NODES ====================

    /**
     * Identity addition: x + 0 = x
     */
    @NodeInfo(shortName = "identity-add")
    public static final class IdentityAddNode extends MezzeNode {

        @Child private MezzeNode valueNode;

        public IdentityAddNode(MezzeNode valueNode) {
            this.valueNode = valueNode;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return valueNode.execute(frame);
        }

        @Override
        public long executeLong(VirtualFrame frame) throws com.oracle.truffle.api.nodes.UnexpectedResultException {
            return valueNode.executeLong(frame);
        }

        @Override
        public double executeDouble(VirtualFrame frame) throws com.oracle.truffle.api.nodes.UnexpectedResultException {
            return valueNode.executeDouble(frame);
        }

        public static IdentityAddNode create(MezzeNode value) {
            return new IdentityAddNode(value);
        }
    }

    /**
     * Identity multiply: x * 1 = x
     */
    @NodeInfo(shortName = "identity-mul")
    public static final class IdentityMulNode extends MezzeNode {

        @Child private MezzeNode valueNode;

        public IdentityMulNode(MezzeNode valueNode) {
            this.valueNode = valueNode;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return valueNode.execute(frame);
        }

        @Override
        public long executeLong(VirtualFrame frame) throws com.oracle.truffle.api.nodes.UnexpectedResultException {
            return valueNode.executeLong(frame);
        }

        @Override
        public double executeDouble(VirtualFrame frame) throws com.oracle.truffle.api.nodes.UnexpectedResultException {
            return valueNode.executeDouble(frame);
        }

        public static IdentityMulNode create(MezzeNode value) {
            return new IdentityMulNode(value);
        }
    }

    /**
     * Zero multiply: x * 0 = 0
     */
    @NodeInfo(shortName = "zero-mul")
    public static final class ZeroMulNode extends MezzeNode {

        @CompilationFinal
        private final boolean isLong;

        public ZeroMulNode(boolean isLong) {
            this.isLong = isLong;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return isLong ? 0L : 0.0;
        }

        @Override
        public long executeLong(VirtualFrame frame) {
            return 0L;
        }

        @Override
        public double executeDouble(VirtualFrame frame) {
            return 0.0;
        }

        public static ZeroMulNode createLong() {
            return new ZeroMulNode(true);
        }

        public static ZeroMulNode createDouble() {
            return new ZeroMulNode(false);
        }
    }
}
