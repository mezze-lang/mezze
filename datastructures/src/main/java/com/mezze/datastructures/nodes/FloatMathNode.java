package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Optimized floating-point math operations.
 *
 * All Math.* calls are intrinsified by the JIT to single instructions
 * where hardware supports them (most x86-64 and ARM64 processors).
 */
public final class FloatMathNode {

    @NodeInfo(shortName = "float.floor")
    public static abstract class FloorNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double floor(double value) {
            return Math.floor(value);
        }

        public static FloorNode create() {
            return FloatMathNodeFactory.FloorNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.ceil")
    public static abstract class CeilNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double ceil(double value) {
            return Math.ceil(value);
        }

        public static CeilNode create() {
            return FloatMathNodeFactory.CeilNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.round")
    public static abstract class RoundNode extends Node {
        public abstract long execute(double value);

        @Specialization
        protected long round(double value) {
            return Math.round(value);
        }

        public static RoundNode create() {
            return FloatMathNodeFactory.RoundNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.trunc")
    public static abstract class TruncNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double trunc(double value) {
            return value >= 0 ? Math.floor(value) : Math.ceil(value);
        }

        public static TruncNode create() {
            return FloatMathNodeFactory.TruncNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.sin")
    public static abstract class SinNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double sin(double value) {
            return Math.sin(value);
        }

        public static SinNode create() {
            return FloatMathNodeFactory.SinNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.cos")
    public static abstract class CosNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double cos(double value) {
            return Math.cos(value);
        }

        public static CosNode create() {
            return FloatMathNodeFactory.CosNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.tan")
    public static abstract class TanNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double tan(double value) {
            return Math.tan(value);
        }

        public static TanNode create() {
            return FloatMathNodeFactory.TanNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.asin")
    public static abstract class AsinNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double asin(double value) {
            return Math.asin(value);
        }

        public static AsinNode create() {
            return FloatMathNodeFactory.AsinNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.acos")
    public static abstract class AcosNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double acos(double value) {
            return Math.acos(value);
        }

        public static AcosNode create() {
            return FloatMathNodeFactory.AcosNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.atan")
    public static abstract class AtanNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double atan(double value) {
            return Math.atan(value);
        }

        public static AtanNode create() {
            return FloatMathNodeFactory.AtanNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.atan2")
    public static abstract class Atan2Node extends Node {
        public abstract double execute(double y, double x);

        @Specialization
        protected double atan2(double y, double x) {
            return Math.atan2(y, x);
        }

        public static Atan2Node create() {
            return FloatMathNodeFactory.Atan2NodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.exp")
    public static abstract class ExpNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double exp(double value) {
            return Math.exp(value);
        }

        public static ExpNode create() {
            return FloatMathNodeFactory.ExpNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.log")
    public static abstract class LogNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double log(double value) {
            return Math.log(value);
        }

        public static LogNode create() {
            return FloatMathNodeFactory.LogNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.log10")
    public static abstract class Log10Node extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double log10(double value) {
            return Math.log10(value);
        }

        public static Log10Node create() {
            return FloatMathNodeFactory.Log10NodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.pow")
    public static abstract class PowNode extends Node {
        public abstract double execute(double base, double exponent);

        @Specialization
        protected double pow(double base, double exponent) {
            return Math.pow(base, exponent);
        }

        public static PowNode create() {
            return FloatMathNodeFactory.PowNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.hypot")
    public static abstract class HypotNode extends Node {
        public abstract double execute(double x, double y);

        @Specialization
        protected double hypot(double x, double y) {
            return Math.hypot(x, y);
        }

        public static HypotNode create() {
            return FloatMathNodeFactory.HypotNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.cbrt")
    public static abstract class CbrtNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double cbrt(double value) {
            return Math.cbrt(value);
        }

        public static CbrtNode create() {
            return FloatMathNodeFactory.CbrtNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.sinh")
    public static abstract class SinhNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double sinh(double value) {
            return Math.sinh(value);
        }

        public static SinhNode create() {
            return FloatMathNodeFactory.SinhNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.cosh")
    public static abstract class CoshNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double cosh(double value) {
            return Math.cosh(value);
        }

        public static CoshNode create() {
            return FloatMathNodeFactory.CoshNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.tanh")
    public static abstract class TanhNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double tanh(double value) {
            return Math.tanh(value);
        }

        public static TanhNode create() {
            return FloatMathNodeFactory.TanhNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.expm1")
    public static abstract class Expm1Node extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double expm1(double value) {
            return Math.expm1(value);
        }

        public static Expm1Node create() {
            return FloatMathNodeFactory.Expm1NodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.log1p")
    public static abstract class Log1pNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double log1p(double value) {
            return Math.log1p(value);
        }

        public static Log1pNode create() {
            return FloatMathNodeFactory.Log1pNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.signum")
    public static abstract class SignumNode extends Node {
        public abstract double execute(double value);

        @Specialization
        protected double signum(double value) {
            return Math.signum(value);
        }

        public static SignumNode create() {
            return FloatMathNodeFactory.SignumNodeGen.create();
        }
    }

    @NodeInfo(shortName = "float.copysign")
    public static abstract class CopysignNode extends Node {
        public abstract double execute(double magnitude, double sign);

        @Specialization
        protected double copysign(double magnitude, double sign) {
            return Math.copySign(magnitude, sign);
        }

        public static CopysignNode create() {
            return FloatMathNodeFactory.CopysignNodeGen.create();
        }
    }

    private FloatMathNode() {
        // Utility class
    }
}
