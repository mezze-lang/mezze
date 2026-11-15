package com.mezze.interpreter.bytecode;

/**
 * THE AVAILABILITY GATE for the SIMD fused-long-pipeline kernels.
 *
 * <p><b>This class names no {@code jdk.incubator.vector} type, and that is its
 * entire reason for existing.</b> Reading {@link #AVAILABLE} LINKS this class,
 * and linking verifies its methods — so a vector type anywhere in a signature
 * here is resolved before the guard can be consulted. That is what happened:
 * {@code FlpSimd} held both the probe and the kernels, including
 * {@code VectorMask<Long> sinkMask(LongVector v, ...)}, so a jar run WITHOUT the
 * module died at the gate instead of taking the scalar loop:
 *
 * <pre>
 * $ java -jar app.jar          # no --add-modules=jdk.incubator.vector
 * Error: java.lang.NoClassDefFoundError: jdk/incubator/vector/Vector
 * </pre>
 *
 * on a program as small as {@code range.map{...}.fold{...}}. The old comment
 * claimed the lazy {@code Vectors} holder kept the API untouched; a holder defers
 * a CONSTANT, not the verification of the methods around it.
 *
 * <p>Every vector-typed member now lives in {@link FlpSimdKernels}, which is
 * named only from inside a branch this gate has already allowed — so it links
 * when the API is really present and never otherwise. KEEP IT THAT WAY: adding a
 * vector import to this file re-breaks every runtime without the module, and the
 * failure is a hard error at first use rather than a slower path.
 */
final class FlpSimd {

    /**
     * True when the Vector API is available AND we are not on SVM.
     * SVM's JIT does not intrinsify the Vector API — operations fall back to
     * per-lane scalar code with Vector object overhead, which is strictly slower
     * than the PE-optimized scalar loop. Also false when jdk.incubator.vector is
     * simply not on the module path (e.g. {@code mez dev} without
     * {@code --add-modules}, or a built jar run plainly).
     */
    static final boolean AVAILABLE = com.mezze.datastructures.util.VectorApi.AVAILABLE;

    /**
     * Below this, the scalar PE-optimized loop wins on overhead; MAX_VALUE when
     * SIMD is unavailable so every caller's threshold check fails.
     *
     * <p>The kernels class is named only in the branch taken when
     * {@link #AVAILABLE}, so an unavailable runtime never executes that
     * reference and never resolves it.
     */
    static final int SIMD_THRESHOLD = AVAILABLE ? FlpSimdKernels.vectorLength() * 4 : Integer.MAX_VALUE;

    /** Delegates to the kernels. Callers gate on {@link #AVAILABLE} first. */
    static Object flpRangeSimd(long start, int n, int sinkKind,
                               int mapOp, long mapOperand, boolean selfMap,
                               int extraOps, long[] extraLits,
                               int filterPreOp, long filterPreRhs,
                               int filterCmpOp, long filterCmpRhs,
                               int foldOp, long foldInit, boolean filterFirst,
                               int sinkPredPreOp, long sinkPredPreRhs,
                               int sinkPredCmpOp, long sinkPredCmpRhs) {
        return FlpSimdKernels.flpRange(start, n, sinkKind, mapOp, mapOperand, selfMap, extraOps, extraLits, filterPreOp, filterPreRhs, filterCmpOp, filterCmpRhs, foldOp, foldInit, filterFirst, sinkPredPreOp, sinkPredPreRhs, sinkPredCmpOp, sinkPredCmpRhs);
    }

    /** Delegates to the kernels. Callers gate on {@link #AVAILABLE} first. */
    static Object flpArraySimd(long[] data, int n, int sinkKind,
                               int mapOp, long mapOperand, boolean selfMap,
                               int extraOps, long[] extraLits,
                               int filterPreOp, long filterPreRhs,
                               int filterCmpOp, long filterCmpRhs,
                               int foldOp, long foldInit, boolean filterFirst,
                               int sinkPredPreOp, long sinkPredPreRhs,
                               int sinkPredCmpOp, long sinkPredCmpRhs) {
        return FlpSimdKernels.flpArray(data, n, sinkKind, mapOp, mapOperand, selfMap, extraOps, extraLits, filterPreOp, filterPreRhs, filterCmpOp, filterCmpRhs, foldOp, foldInit, filterFirst, sinkPredPreOp, sinkPredPreRhs, sinkPredCmpOp, sinkPredCmpRhs);
    }

    private FlpSimd() {}
}
