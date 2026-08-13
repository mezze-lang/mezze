package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD-accelerated sum node for double sequences.
 *
 * Uses:
 * - Java Vector API for SIMD operations
 * - Unboxed double[] throughout
 * - Vectorized reduction for large sequences
 * - Scalar fallback for small sequences
 *
 * See {@link SeqSumLongSIMDNode} for the {@code @GenerateUncached}
 * rationale — same SO bug pattern if omitted.
 */
@NodeInfo(shortName = "seq.sum.double.simd")
@GenerateUncached
public abstract class SeqSumDoubleSIMDNode extends Node {

    private static final VectorSpecies<Double> SPECIES = DoubleVector.SPECIES_PREFERRED;
    private static final int VECTOR_LENGTH = SPECIES.length();

    public abstract double execute(DoubleSeqStorage storage);

    @Specialization(guards = "storage.size() == 0")
    protected double sumEmpty(DoubleSeqStorage storage) {
        return 0.0;
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 4"})
    @ExplodeLoop
    protected double sumTiny(DoubleSeqStorage storage) {
        int size = storage.size();
        double[] data = storage.getArray();
        double sum = 0.0;
        for (int i = 0; i < size; i++) {
            sum += data[i];
        }
        return sum;
    }

    @Specialization(guards = {"storage.size() > 4", "storage.size() < 64"})
    protected double sumSmall(DoubleSeqStorage storage) {
        double[] data = storage.getArray();
        int size = storage.size();
        double sum = 0.0;
        for (int i = 0; i < size; i++) {
            sum += data[i];
        }
        return sum;
    }

    @Specialization(guards = "storage.size() >= 64")
    @TruffleBoundary
    protected double sumSIMD(DoubleSeqStorage storage) {
        double[] data = storage.getArray();
        int size = storage.size();

        // Use SIMD for the bulk of the array
        DoubleVector sumVector = DoubleVector.zero(SPECIES);
        int i = 0;
        int limit = SPECIES.loopBound(size);

        for (; i < limit; i += VECTOR_LENGTH) {
            DoubleVector v = DoubleVector.fromArray(SPECIES, data, i);
            sumVector = sumVector.add(v);
        }

        // Reduce vector to scalar
        double sum = sumVector.reduceLanes(jdk.incubator.vector.VectorOperators.ADD);

        // Handle remaining elements
        for (; i < size; i++) {
            sum += data[i];
        }

        return sum;
    }

    public static SeqSumDoubleSIMDNode create() {
        return SeqSumDoubleSIMDNodeGen.create();
    }

    public static SeqSumDoubleSIMDNode getUncached() {
        return SeqSumDoubleSIMDNodeGen.getUncached();
    }
}
