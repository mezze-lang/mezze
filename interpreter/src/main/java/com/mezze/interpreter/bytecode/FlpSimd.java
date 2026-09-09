package com.mezze.interpreter.bytecode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD-accelerated fast paths for {@link MezzeBytecodeRootNode.FusedLongPipeline}
 * and {@link MezzeBytecodeRootNode.FusedLongRangePipeline}.
 *
 * <p>Uses the Java Vector API ({@code jdk.incubator.vector}) to process
 * VECTOR_LENGTH elements per cycle, achieving near-Java-IntStream throughput
 * for filter+map+fold chains on large ranges and materialized long[].
 *
 * <p>Supports:
 * <ul>
 *   <li>SUM sink — masked vector accumulation with reduceLanes</li>
 *   <li>COUNT sink — mask trueCount per vector</li>
 *   <li>REDUCE sink — same as SUM but init from first passing element</li>
 * </ul>
 *
 * <p>Does NOT support early-exit sinks (ANY/ALL/FIND) or materialization
 * sinks (TO_SEQ/TO_LIST) — those fall back to the scalar PE-optimized loop.
 */
final class FlpSimd {

    /**
     * True when the Vector API is available AND we're not on SVM.
     * SVM's JIT does not intrinsify the Vector API — operations fall back to
     * per-lane scalar code with Vector object overhead, which is strictly slower
     * than the PE-optimized scalar loop.  Also false when jdk.incubator.vector
     * is simply not on the module path (e.g. {@code mez dev} without
     * {@code --add-modules}).
     *
     * <p>Callers gate on this field; when false, the Vector API classes in the
     * lazy {@link Vectors} holder are never touched, so no
     * {@code NoClassDefFoundError} is thrown.
     */
    static final boolean AVAILABLE = probeVectorApi();

    private static boolean probeVectorApi() {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) return false;
        try {
            Class.forName("jdk.incubator.vector.LongVector");
            return true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        }
    }

    /** Lazy holder — Vector API classes loaded only when first accessed. */
    private static final class Vectors {
        static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;
        static final int VL = SPECIES.length();
    }

    /** Below this, scalar PE-optimized loop wins on overhead.
     *  MAX_VALUE when SIMD is unavailable so callers' threshold check always fails. */
    static final int SIMD_THRESHOLD = AVAILABLE ? Vectors.VL * 4 : Integer.MAX_VALUE;

    // FLP op constants (mirrored from FusedLongPipeline.flpOp)
    private static final int OP_ADD = 0, OP_SUB = 1, OP_MUL = 2, OP_DIV = 3, OP_MOD = 4;
    // FLP cmp constants (mirrored from FusedLongPipeline.flpCmp)
    private static final int CMP_EQ = 5, CMP_NEQ = 6, CMP_LT = 7, CMP_LE = 8, CMP_GT = 9, CMP_GE = 10;

    private FlpSimd() {}

    // ── Range pipeline: generate values on-the-fly ──

    /**
     * SIMD fast path for {@code iter::range{start, end}.filter{}.map{}.fold/sum/count{}}.
     * Generates values vectorially ({@code v[i] = start + base + lane}) instead of
     * loading from an array.
     */
    @TruffleBoundary
    static Object flpRangeSimd(long start, int n, int sinkKind,
                               int mapOp, long mapOperand, boolean selfMap,
                               int filterPreOp, long filterPreRhs,
                               int filterCmpOp, long filterCmpRhs,
                               int foldOp, long foldInit, boolean filterFirst) {
        // Pre-compute broadcast vectors
        LongVector filterPreRhsVec = LongVector.broadcast(Vectors.SPECIES, filterPreRhs);
        LongVector filterCmpRhsVec = LongVector.broadcast(Vectors.SPECIES, filterCmpRhs);
        LongVector mapOperandVec   = LongVector.broadcast(Vectors.SPECIES, mapOperand);

        // Iota vector: [0, 1, 2, ..., VL-1]
        long[] iotaArr = new long[Vectors.VL];
        for (int j = 0; j < Vectors.VL; j++) iotaArr[j] = j;
        LongVector iota = LongVector.fromArray(Vectors.SPECIES, iotaArr, 0);

        LongVector accVec = LongVector.zero(Vectors.SPECIES);
        long countAcc = 0;
        boolean reduceNeedInit = sinkKind == MezzeBytecodeRootNode.FLP_SINK_REDUCE;
        long scalarAcc = foldInit;

        int bound = Vectors.SPECIES.loopBound(n);
        int i = 0;

        // ── Main SIMD loop ──
        for (; i < bound; i += Vectors.VL) {
            // Generate: v[lane] = start + i + lane
            LongVector v = LongVector.broadcast(Vectors.SPECIES, start + i).add(iota);

            // ── Filter ──
            VectorMask<Long> mask;
            if (filterCmpOp >= 0) {
                LongVector fv = filterPreOp >= 0
                        ? simdOp(filterPreOp, v, filterPreRhsVec) : v;
                mask = simdCmp(filterCmpOp, fv, filterCmpRhsVec);
            } else {
                mask = Vectors.SPECIES.maskAll(true);
            }

            // ── Map ──
            if (mapOp >= 0) {
                v = simdOp(mapOp, v, selfMap ? v : mapOperandVec);
            }

            // ── Sink ──
            if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_SUM) {
                accVec = accVec.add(v, mask);
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_COUNT) {
                countAcc += mask.trueCount();
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_REDUCE) {
                // For reduce: accumulate with the fold op.
                // First passing element initializes the accumulator.
                if (reduceNeedInit && mask.anyTrue()) {
                    // Find first passing lane, use it to init, mask it out
                    int firstLane = mask.firstTrue();
                    scalarAcc = v.lane(firstLane);
                    reduceNeedInit = false;
                    // Process remaining lanes in this vector
                    VectorMask<Long> rest = mask.and(
                            Vectors.SPECIES.indexInRange(firstLane + 1, Vectors.VL));
                    accVec = LongVector.broadcast(Vectors.SPECIES, 0);
                    if (foldOp == OP_ADD) {
                        accVec = accVec.add(v, rest);
                    } else if (foldOp == OP_MUL) {
                        // For mul reduce, can't use vector accumulation as easily.
                        // Fall back to scalar for the remaining lanes.
                        for (int lane = firstLane + 1; lane < Vectors.VL; lane++) {
                            if (mask.laneIsSet(lane)) {
                                scalarAcc = flpOp(foldOp, scalarAcc, v.lane(lane));
                            }
                        }
                    }
                } else if (!reduceNeedInit) {
                    if (foldOp == OP_ADD) {
                        accVec = accVec.add(v, mask);
                    } else {
                        // Non-add reduce: scalar fallback per lane
                        for (int lane = 0; lane < Vectors.VL; lane++) {
                            if (mask.laneIsSet(lane)) {
                                scalarAcc = flpOp(foldOp, scalarAcc, v.lane(lane));
                            }
                        }
                    }
                }
            }
        }

        // ── Scalar tail ──
        for (; i < n; i++) {
            long v = start + i;
            if (filterCmpOp >= 0) {
                long fv = filterPreOp >= 0
                        ? flpOp(filterPreOp, v, filterPreRhs) : v;
                if (!flpCmp(filterCmpOp, fv, filterCmpRhs)) continue;
            }
            if (mapOp >= 0) v = flpOp(mapOp, v, selfMap ? v : mapOperand);
            if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_SUM) {
                scalarAcc = flpOp(foldOp, scalarAcc, v);
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_COUNT) {
                countAcc++;
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_REDUCE) {
                if (reduceNeedInit) { scalarAcc = v; reduceNeedInit = false; }
                else scalarAcc = flpOp(foldOp, scalarAcc, v);
            }
        }

        // ── Reduce vectors to scalar ──
        // scalarAcc holds foldInit + any scalar-tail contributions.
        // accVec holds the vectorized partial sums. Combine them.
        if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_SUM) {
            long sum = scalarAcc + accVec.reduceLanes(VectorOperators.ADD);
            return MezzeBytecodeRootNode.FusedLongPipeline.flpResult(sinkKind, sum, false, 0);
        } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_COUNT) {
            return MezzeBytecodeRootNode.FusedLongPipeline.flpResult(sinkKind, countAcc, false, 0);
        } else { // REDUCE
            if (reduceNeedInit) {
                return MezzeBytecodeRootNode.FusedLongPipeline.flpResultReduceEmpty();
            }
            long reduced = scalarAcc + accVec.reduceLanes(VectorOperators.ADD);
            return MezzeBytecodeRootNode.FusedLongPipeline.flpResult(sinkKind, reduced, false, 0);
        }
    }

    // ── Array pipeline: read from materialized long[] ──

    /**
     * SIMD fast path for {@code seq.filter{}.map{}.fold/sum/count{}} on a
     * materialized {@code long[]} (LongSeqStorage).
     */
    @TruffleBoundary
    static Object flpArraySimd(long[] data, int n, int sinkKind,
                               int mapOp, long mapOperand, boolean selfMap,
                               int filterPreOp, long filterPreRhs,
                               int filterCmpOp, long filterCmpRhs,
                               int foldOp, long foldInit, boolean filterFirst) {
        LongVector filterPreRhsVec = LongVector.broadcast(Vectors.SPECIES, filterPreRhs);
        LongVector filterCmpRhsVec = LongVector.broadcast(Vectors.SPECIES, filterCmpRhs);
        LongVector mapOperandVec   = LongVector.broadcast(Vectors.SPECIES, mapOperand);

        LongVector accVec = LongVector.zero(Vectors.SPECIES);
        long countAcc = 0;
        boolean reduceNeedInit = sinkKind == MezzeBytecodeRootNode.FLP_SINK_REDUCE;
        long scalarAcc = foldInit;

        int bound = Vectors.SPECIES.loopBound(n);
        int i = 0;

        for (; i < bound; i += Vectors.VL) {
            LongVector v = LongVector.fromArray(Vectors.SPECIES, data, i);

            VectorMask<Long> mask;
            if (filterCmpOp >= 0) {
                LongVector fv = filterPreOp >= 0
                        ? simdOp(filterPreOp, v, filterPreRhsVec) : v;
                mask = simdCmp(filterCmpOp, fv, filterCmpRhsVec);
            } else {
                mask = Vectors.SPECIES.maskAll(true);
            }

            if (mapOp >= 0) {
                v = simdOp(mapOp, v, selfMap ? v : mapOperandVec);
            }

            if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_SUM) {
                accVec = accVec.add(v, mask);
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_COUNT) {
                countAcc += mask.trueCount();
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_REDUCE) {
                if (reduceNeedInit && mask.anyTrue()) {
                    int firstLane = mask.firstTrue();
                    scalarAcc = v.lane(firstLane);
                    reduceNeedInit = false;
                    VectorMask<Long> rest = mask.and(
                            Vectors.SPECIES.indexInRange(firstLane + 1, Vectors.VL));
                    accVec = LongVector.broadcast(Vectors.SPECIES, 0);
                    if (foldOp == OP_ADD) {
                        accVec = accVec.add(v, rest);
                    } else {
                        for (int lane = firstLane + 1; lane < Vectors.VL; lane++) {
                            if (mask.laneIsSet(lane)) {
                                scalarAcc = flpOp(foldOp, scalarAcc, v.lane(lane));
                            }
                        }
                    }
                } else if (!reduceNeedInit) {
                    if (foldOp == OP_ADD) {
                        accVec = accVec.add(v, mask);
                    } else {
                        for (int lane = 0; lane < Vectors.VL; lane++) {
                            if (mask.laneIsSet(lane)) {
                                scalarAcc = flpOp(foldOp, scalarAcc, v.lane(lane));
                            }
                        }
                    }
                }
            }
        }

        // Scalar tail
        for (; i < n; i++) {
            long v = data[i];
            if (filterCmpOp >= 0) {
                long fv = filterPreOp >= 0 ? flpOp(filterPreOp, v, filterPreRhs) : v;
                if (!flpCmp(filterCmpOp, fv, filterCmpRhs)) continue;
            }
            if (mapOp >= 0) v = flpOp(mapOp, v, selfMap ? v : mapOperand);
            if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_SUM) {
                scalarAcc = flpOp(foldOp, scalarAcc, v);
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_COUNT) {
                countAcc++;
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_REDUCE) {
                if (reduceNeedInit) { scalarAcc = v; reduceNeedInit = false; }
                else scalarAcc = flpOp(foldOp, scalarAcc, v);
            }
        }

        if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_SUM) {
            long sum = scalarAcc + accVec.reduceLanes(VectorOperators.ADD);
            return MezzeBytecodeRootNode.FusedLongPipeline.flpResult(sinkKind, sum, false, 0);
        } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_COUNT) {
            return MezzeBytecodeRootNode.FusedLongPipeline.flpResult(sinkKind, countAcc, false, 0);
        } else {
            if (reduceNeedInit) {
                return MezzeBytecodeRootNode.FusedLongPipeline.flpResultReduceEmpty();
            }
            long reduced = scalarAcc + accVec.reduceLanes(VectorOperators.ADD);
            return MezzeBytecodeRootNode.FusedLongPipeline.flpResult(sinkKind, reduced, false, 0);
        }
    }

    // ── SIMD operation helpers ──

    private static LongVector simdOp(int op, LongVector a, LongVector b) {
        return switch (op) {
            case OP_ADD -> a.add(b);
            case OP_SUB -> a.sub(b);
            case OP_MUL -> a.mul(b);
            case OP_DIV -> a.div(b);
            case OP_MOD -> simdMod(a, b);
            default -> a;
        };
    }

    /** Vectorized modulo with power-of-2 fast path.
     *  x86 has no vector integer division (pre-AVX512), so
     *  a.div(b) falls back to per-lane scalar ÷ — disastrous
     *  for throughput. For power-of-2 divisors (common: x%2==0),
     *  use bitwise AND: a%(2^k) == a &amp; (2^k - 1). */
    private static LongVector simdMod(LongVector a, LongVector b) {
        // All lanes of b are the same value (broadcast).
        long divisor = b.lane(0);
        if (divisor > 0 && (divisor & (divisor - 1)) == 0) {
            // Power of 2: a % divisor == a & (divisor - 1)
            return a.and(divisor - 1);
        }
        // General case: a - (a/b)*b
        LongVector q = a.div(b);
        return a.sub(q.mul(b));
    }

    private static VectorMask<Long> simdCmp(int op, LongVector a, LongVector b) {
        return switch (op) {
            case CMP_EQ  -> a.eq(b);
            case CMP_NEQ -> a.eq(b).not();
            case CMP_LT  -> a.lt(b);
            case CMP_LE  -> a.lt(b).or(a.eq(b));
            case CMP_GT  -> b.lt(a);
            case CMP_GE  -> b.lt(a).or(a.eq(b));
            default -> Vectors.SPECIES.maskAll(false);
        };
    }

    // Scalar op/cmp mirrors of FusedLongPipeline (for tail elements)
    private static long flpOp(int op, long a, long b) {
        return switch (op) {
            case OP_ADD -> a + b;
            case OP_SUB -> a - b;
            case OP_MUL -> a * b;
            case OP_DIV -> a / b;
            case OP_MOD -> a % b;
            default -> a;
        };
    }

    private static boolean flpCmp(int op, long a, long b) {
        return switch (op) {
            case CMP_EQ  -> a == b;
            case CMP_NEQ -> a != b;
            case CMP_LT  -> a < b;
            case CMP_LE  -> a <= b;
            case CMP_GT  -> a > b;
            case CMP_GE  -> a >= b;
            default -> false;
        };
    }
}
