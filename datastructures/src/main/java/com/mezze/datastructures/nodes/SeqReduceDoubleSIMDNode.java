package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.DoubleSeqStorage;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD-accelerated fold-with-primitive-op over {@link DoubleSeqStorage}.
 *
 * <p>Double-precision equivalent of {@link SeqReduceLongSIMDNode}.
 * Only associative-ish ops: ADD/MUL are associative modulo FP
 * reassociation (a widely-accepted trade for SIMD reductions in
 * numerical code, matching JDK Stream.reduce and NumPy behaviour);
 * MIN/MAX are truly associative.
 */
@NodeInfo(shortName = "seq.reduce.double.simd")
@GenerateUncached  // required — see SeqSumLongSIMDNode for rationale (missing → getUncached() infinite-recurses)
public abstract class SeqReduceDoubleSIMDNode extends Node {

    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;
    private static final int VECTOR_LENGTH = SPECIES.length();

    public static final int OP_ADD = 0;
    public static final int OP_MUL = 1;
    public static final int OP_MIN = 2;
    public static final int OP_MAX = 3;

    private static double identity(int op) {
        switch (op) {
            case OP_ADD: return 0.0;
            case OP_MUL: return 1.0;
            case OP_MIN: return Double.POSITIVE_INFINITY;
            case OP_MAX: return Double.NEGATIVE_INFINITY;
            default:
                throw new IllegalStateException("SeqReduceDoubleSIMDNode: unsupported op " + op);
        }
    }

    public abstract double execute(int op, double init, DoubleSeqStorage storage);

    @Specialization(guards = "storage.size() == 0")
    protected double doEmpty(int op, double init, DoubleSeqStorage storage) {
        return init;
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 4"})
    @ExplodeLoop
    protected double doTiny(int op, double init, DoubleSeqStorage storage) {
        int size = storage.size();
        double[] data = storage.getArray();
        double acc = init;
        for (int i = 0; i < size; i++) {
            acc = apply(op, acc, data[i]);
        }
        return acc;
    }

    @Specialization(guards = {"storage.size() > 4", "storage.size() < 64"})
    protected double doSmall(int op, double init, DoubleSeqStorage storage) {
        double[] data = storage.getArray();
        int size = storage.size();
        double acc = init;
        for (int i = 0; i < size; i++) {
            acc = apply(op, acc, data[i]);
        }
        return acc;
    }

    @Specialization(guards = "storage.size() >= 64")
    @TruffleBoundary
    protected double doSIMD(int op, double init, DoubleSeqStorage storage) {
        double[] data = storage.getArray();
        int size = storage.size();

        DoubleVector acc = DoubleVector.broadcast(SPECIES, identity(op));
        int i = 0;
        int limit = SPECIES.loopBound(size);

        switch (op) {
            case OP_ADD:
                for (; i < limit; i += VECTOR_LENGTH) {
                    acc = acc.add(DoubleVector.fromArray(SPECIES, data, i));
                }
                break;
            case OP_MUL:
                for (; i < limit; i += VECTOR_LENGTH) {
                    acc = acc.mul(DoubleVector.fromArray(SPECIES, data, i));
                }
                break;
            case OP_MIN:
                for (; i < limit; i += VECTOR_LENGTH) {
                    acc = acc.min(DoubleVector.fromArray(SPECIES, data, i));
                }
                break;
            case OP_MAX:
                for (; i < limit; i += VECTOR_LENGTH) {
                    acc = acc.max(DoubleVector.fromArray(SPECIES, data, i));
                }
                break;
            default:
                throw new IllegalStateException("SeqReduceDoubleSIMDNode: unsupported op " + op);
        }

        double result = reduceLanes(op, acc);
        result = apply(op, init, result);
        for (; i < size; i++) {
            result = apply(op, result, data[i]);
        }
        return result;
    }

    private static double reduceLanes(int op, DoubleVector v) {
        switch (op) {
            case OP_ADD: return v.reduceLanes(VectorOperators.ADD);
            case OP_MUL: return v.reduceLanes(VectorOperators.MUL);
            case OP_MIN: return v.reduceLanes(VectorOperators.MIN);
            case OP_MAX: return v.reduceLanes(VectorOperators.MAX);
            default: throw new IllegalStateException("reduceLanes: op " + op);
        }
    }

    private static double apply(int op, double a, double b) {
        switch (op) {
            case OP_ADD: return a + b;
            case OP_MUL: return a * b;
            case OP_MIN: return Math.min(a, b);
            case OP_MAX: return Math.max(a, b);
            default: throw new IllegalStateException("apply: op " + op);
        }
    }

    public static SeqReduceDoubleSIMDNode create() {
        return SeqReduceDoubleSIMDNodeGen.create();
    }

    public static SeqReduceDoubleSIMDNode getUncached() {
        return SeqReduceDoubleSIMDNodeGen.getUncached();
    }
}
