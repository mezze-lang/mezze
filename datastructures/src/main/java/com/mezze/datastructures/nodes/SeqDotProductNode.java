package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD-accelerated dot product node for double sequences.
 *
 * Computes the sum of element-wise products of two sequences.
 * Critical for numerical computations, ML, and linear algebra.
 *
 * Uses:
 * - Java Vector API for SIMD multiply-accumulate
 * - FMA (fused multiply-add) where available
 * - Unboxed double[] throughout
 */
@NodeInfo(shortName = "seq.dotproduct.double")
public abstract class SeqDotProductNode extends Node {

    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;
    private static final int VECTOR_LENGTH = SPECIES.length();

    public abstract double execute(DoubleSeqStorage a, DoubleSeqStorage b);

    @Specialization(guards = "a.size() == 0 || b.size() == 0")
    protected double dotEmpty(DoubleSeqStorage a, DoubleSeqStorage b) {
        return 0.0;
    }

    @Specialization(guards = {"a.size() > 0", "a.size() <= 4", "a.size() == b.size()"})
    @ExplodeLoop
    protected double dotTiny(DoubleSeqStorage a, DoubleSeqStorage b) {
        int size = a.size();
        double[] dataA = a.getArray();
        double[] dataB = b.getArray();
        double sum = 0.0;
        for (int i = 0; i < size; i++) {
            sum += dataA[i] * dataB[i];
        }
        return sum;
    }

    @Specialization(guards = {"a.size() > 4", "a.size() < 64", "a.size() == b.size()"})
    protected double dotSmall(DoubleSeqStorage a, DoubleSeqStorage b) {
        int size = a.size();
        double[] dataA = a.getArray();
        double[] dataB = b.getArray();
        double sum = 0.0;
        for (int i = 0; i < size; i++) {
            sum += dataA[i] * dataB[i];
        }
        return sum;
    }

    @Specialization(guards = {"a.size() >= 64", "a.size() == b.size()"})
    @TruffleBoundary
    protected double dotSIMD(DoubleSeqStorage a, DoubleSeqStorage b) {
        double[] dataA = a.getArray();
        double[] dataB = b.getArray();
        int size = a.size();

        // Use SIMD for the bulk
        DoubleVector sumVector = DoubleVector.zero(SPECIES);
        int i = 0;
        int limit = SPECIES.loopBound(size);

        for (; i < limit; i += VECTOR_LENGTH) {
            DoubleVector va = DoubleVector.fromArray(SPECIES, dataA, i);
            DoubleVector vb = DoubleVector.fromArray(SPECIES, dataB, i);
            // Use FMA if available: sum += a * b
            sumVector = va.fma(vb, sumVector);
        }

        // Reduce vector to scalar
        double sum = sumVector.reduceLanes(jdk.incubator.vector.VectorOperators.ADD);

        // Handle remaining elements
        for (; i < size; i++) {
            sum += dataA[i] * dataB[i];
        }

        return sum;
    }

    @Specialization(guards = "a.size() != b.size()")
    protected double dotMismatch(DoubleSeqStorage a, DoubleSeqStorage b) {
        throw new IllegalArgumentException("Sequences must have same length for dot product");
    }

    public static SeqDotProductNode create() {
        return SeqDotProductNodeGen.create();
    }

    public static SeqDotProductNode getUncached() {
        return SeqDotProductNodeGen.getUncached();
    }
}
