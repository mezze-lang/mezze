package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD-accelerated sum node for long sequences.
 *
 * Uses:
 * - Java Vector API for SIMD operations
 * - Unboxed long[] throughout
 * - Vectorized reduction for large sequences
 * - Scalar fallback for small sequences
 *
 * NOTE: {@code @GenerateUncached} is required — the static
 * {@link #getUncached()} below delegates to the generated
 * {@code SeqSumLongSIMDNodeGen.getUncached()}. Without the
 * annotation Truffle doesn't emit that method on the Gen class,
 * so the delegating call resolves back to this class's static and
 * infinite-recurses into a StackOverflowError (bug found via
 * `bench/cases/sum_native` — {@code seq::sum} on any input).
 */
@NodeInfo(shortName = "seq.sum.long.simd")
@GenerateUncached
public abstract class SeqSumLongSIMDNode extends Node {

    private static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;
    private static final int VECTOR_LENGTH = SPECIES.length();

    public abstract long execute(LongSeqStorage storage);

    @Specialization(guards = "storage.size() == 0")
    protected long sumEmpty(LongSeqStorage storage) {
        return 0L;
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 4"})
    @ExplodeLoop
    protected long sumTiny(LongSeqStorage storage) {
        int size = storage.size();
        long[] data = storage.getArray();
        long sum = 0L;
        for (int i = 0; i < size; i++) {
            sum += data[i];
        }
        return sum;
    }

    @Specialization(guards = {"storage.size() > 4", "storage.size() < 64"})
    protected long sumSmall(LongSeqStorage storage) {
        long[] data = storage.getArray();
        int size = storage.size();
        long sum = 0L;
        for (int i = 0; i < size; i++) {
            sum += data[i];
        }
        return sum;
    }

    @Specialization(guards = "storage.size() >= 64")
    @TruffleBoundary
    protected long sumSIMD(LongSeqStorage storage) {
        long[] data = storage.getArray();
        int size = storage.size();

        // Use SIMD for the bulk of the array
        LongVector sumVector = LongVector.zero(SPECIES);
        int i = 0;
        int limit = SPECIES.loopBound(size);

        for (; i < limit; i += VECTOR_LENGTH) {
            LongVector v = LongVector.fromArray(SPECIES, data, i);
            sumVector = sumVector.add(v);
        }

        // Reduce vector to scalar
        long sum = sumVector.reduceLanes(jdk.incubator.vector.VectorOperators.ADD);

        // Handle remaining elements
        for (; i < size; i++) {
            sum += data[i];
        }

        return sum;
    }

    public static SeqSumLongSIMDNode create() {
        return SeqSumLongSIMDNodeGen.create();
    }

    public static SeqSumLongSIMDNode getUncached() {
        return SeqSumLongSIMDNodeGen.getUncached();
    }
}
