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

    /**
     * The SINK predicate as a lane mask, ANDed with the stage mask.
     *
     * <p>The sink predicate is NOT the filter stage: `iter::any { it, p }`
     * carries `p` on the sink, and a chain can have both. It is applied to the
     * POST-MAP value, which is where the scalar loop applies it.
     *
     * <p>A sink with no predicate at all — `any` over a bare chain — is "every
     * surviving element qualifies", so the stage mask is the answer.
     */
    private static VectorMask<Long> sinkMask(LongVector v, VectorMask<Long> mask,
                                             int sinkPredPreOp, LongVector sinkPreRhsVec,
                                             int sinkPredCmpOp, LongVector sinkCmpRhsVec) {
        if (sinkPredCmpOp < 0) return mask;
        LongVector sv = sinkPredPreOp >= 0 ? simdOp(sinkPredPreOp, v, sinkPreRhsVec) : v;
        return simdCmp(sinkPredCmpOp, sv, sinkCmpRhsVec).and(mask);
    }

    // ── Range pipeline: generate values on-the-fly ──

    /**
     * SIMD fast path for {@code iter::range{start, end}.filter{}.map{}.fold/sum/count{}}.
     * Generates values vectorially ({@code v[i] = start + base + lane}) instead of
     * loading from an array.
     */
    @TruffleBoundary
    static Object flpRangeSimd(long start, int n, int sinkKind,
                               int mapOp, long mapOperand, boolean selfMap,
                               int extraOps, long[] extraLits,
                               int filterPreOp, long filterPreRhs,
                               int filterCmpOp, long filterCmpRhs,
                               int foldOp, long foldInit, boolean filterFirst,
                               int sinkPredPreOp, long sinkPredPreRhs,
                               int sinkPredCmpOp, long sinkPredCmpRhs) {
        // Pre-compute broadcast vectors
        LongVector filterPreRhsVec = LongVector.broadcast(Vectors.SPECIES, filterPreRhs);
        LongVector filterCmpRhsVec = LongVector.broadcast(Vectors.SPECIES, filterCmpRhs);
        LongVector mapOperandVec   = LongVector.broadcast(Vectors.SPECIES, mapOperand);
        LongVector sinkPreRhsVec   = LongVector.broadcast(Vectors.SPECIES, sinkPredPreRhs);
        LongVector sinkCmpRhsVec   = LongVector.broadcast(Vectors.SPECIES, sinkPredCmpRhs);
        LongVector[] extraVecs     = broadcastExtras(extraLits);

        // Iota vector: [0, 1, 2, ..., VL-1]
        long[] iotaArr = new long[Vectors.VL];
        for (int j = 0; j < Vectors.VL; j++) iotaArr[j] = j;
        LongVector iota = LongVector.fromArray(Vectors.SPECIES, iotaArr, 0);

        LongVector accVec = LongVector.zero(Vectors.SPECIES);
        long countAcc = 0;
        // Short-circuit state, with the SAME meaning the scalar loop gives it:
        // for ANY and FIND `found` is a match, for ALL and NONE it is a
        // COUNTEREXAMPLE, and `flpResult` reads it that way for each kind.
        boolean found = false;
        long foundVal = 0;
        boolean reduceNeedInit = sinkKind == MezzeBytecodeRootNode.FLP_SINK_REDUCE;
        long scalarAcc = foldInit;

        int bound = Vectors.SPECIES.loopBound(n);
        int i = 0;

        // ── Main SIMD loop ──
        for (; i < bound; i += Vectors.VL) {
            // Generate: v[lane] = start + i + lane
            LongVector v = LongVector.broadcast(Vectors.SPECIES, start + i).add(iota);

            // ── Filter, when the predicate sees the RAW element ──
            // STAGE ORDER. `filterFirst` says the user wrote the filter before
            // the map. This loop used to ignore it and always mask on the raw
            // value, which is right for `filter(p).map(f)` and wrong for
            // `map(f).filter(p)`: the predicate then tested a value the user
            // wrote no predicate for. Every element passed, and because SIMD
            // only engages past SIMD_THRESHOLD the same program was CORRECT at
            // small sizes and wrong at scale.
            VectorMask<Long> mask;
            if (filterCmpOp >= 0 && filterFirst) {
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
            if (extraOps != 0) v = simdApplyExtraMaps(extraOps, extraVecs, v);

            // ── Filter, when the predicate sees the MAPPED element ──
            if (filterCmpOp >= 0 && !filterFirst) {
                LongVector fv = filterPreOp >= 0
                        ? simdOp(filterPreOp, v, filterPreRhsVec) : v;
                mask = simdCmp(filterCmpOp, fv, filterCmpRhsVec);
            }

            // ── Sink ──
            if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_SUM) {
                accVec = accVec.add(v, mask);
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_COUNT) {
                countAcc += mask.trueCount();
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_ANY
                    || sinkKind == MezzeBytecodeRootNode.FLP_SINK_FIND
                    || sinkKind == MezzeBytecodeRootNode.FLP_SINK_NONE) {
                // A HIT in any lane ends it. Lanes ascend, so the lowest set
                // lane is the first match in SOURCE order, which is what
                // `find` has to return.
                VectorMask<Long> hit = sinkMask(v, mask, sinkPredPreOp,
                        sinkPreRhsVec, sinkPredCmpOp, sinkCmpRhsVec);
                if (hit.anyTrue()) {
                    found = true;
                    if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_FIND) {
                        foundVal = v.lane(hit.firstTrue());
                    }
                    break;
                }
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_ALL) {
                // `found` is a COUNTEREXAMPLE here, as in the scalar loop: a
                // surviving lane that FAILS the predicate. A lane the filter
                // dropped is not a counterexample, which is why the stage mask
                // is ANDed in rather than ignored.
                VectorMask<Long> pass = sinkMask(v, mask, sinkPredPreOp,
                        sinkPreRhsVec, sinkPredCmpOp, sinkCmpRhsVec);
                if (mask.andNot(pass).anyTrue()) { found = true; break; }
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
        // `found` means the vector loop already answered; the remaining
        // elements cannot change it, for any of these four sinks.
        for (; i < n && !found; i++) {
            long v = start + i;
            if (filterCmpOp >= 0 && filterFirst) {
                long fv = filterPreOp >= 0
                        ? flpOp(filterPreOp, v, filterPreRhs) : v;
                if (!flpCmp(filterCmpOp, fv, filterCmpRhs)) continue;
            }
            if (mapOp >= 0) v = flpOp(mapOp, v, selfMap ? v : mapOperand);
            if (extraOps != 0) v = MezzeBytecodeRootNode.flpApplyExtraMaps(
                    extraOps, extraLits, v);
            if (filterCmpOp >= 0 && !filterFirst) {
                long fv = filterPreOp >= 0
                        ? flpOp(filterPreOp, v, filterPreRhs) : v;
                if (!flpCmp(filterCmpOp, fv, filterCmpRhs)) continue;
            }
            if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_SUM) {
                scalarAcc = flpOp(foldOp, scalarAcc, v);
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_COUNT) {
                countAcc++;
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_REDUCE) {
                if (reduceNeedInit) { scalarAcc = v; reduceNeedInit = false; }
                else scalarAcc = flpOp(foldOp, scalarAcc, v);
            } else {
                // The same four sinks, element at a time, for the elements
                // past the vector bound. Written exactly as the scalar
                // pipeline writes them so the two cannot drift.
                boolean hit;
                if (sinkPredCmpOp < 0) {
                    hit = true;
                } else {
                    long sv = sinkPredPreOp >= 0
                            ? flpOp(sinkPredPreOp, v, sinkPredPreRhs) : v;
                    hit = flpCmp(sinkPredCmpOp, sv, sinkPredCmpRhs);
                }
                if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_ALL) {
                    if (!hit) found = true;
                } else if (hit) {
                    found = true;
                    if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_FIND) foundVal = v;
                }
            }
        }

        // ── Reduce vectors to scalar ──
        // scalarAcc holds foldInit + any scalar-tail contributions.
        // accVec holds the vectorized partial sums. Combine them.
        if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_ANY
                || sinkKind == MezzeBytecodeRootNode.FLP_SINK_ALL
                || sinkKind == MezzeBytecodeRootNode.FLP_SINK_FIND
                || sinkKind == MezzeBytecodeRootNode.FLP_SINK_NONE) {
            return MezzeBytecodeRootNode.FusedLongPipeline.flpResult(
                    sinkKind, 0, found, foundVal);
        }
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
                               int extraOps, long[] extraLits,
                               int filterPreOp, long filterPreRhs,
                               int filterCmpOp, long filterCmpRhs,
                               int foldOp, long foldInit, boolean filterFirst,
                               int sinkPredPreOp, long sinkPredPreRhs,
                               int sinkPredCmpOp, long sinkPredCmpRhs) {
        LongVector filterPreRhsVec = LongVector.broadcast(Vectors.SPECIES, filterPreRhs);
        LongVector filterCmpRhsVec = LongVector.broadcast(Vectors.SPECIES, filterCmpRhs);
        LongVector mapOperandVec   = LongVector.broadcast(Vectors.SPECIES, mapOperand);
        LongVector sinkPreRhsVec   = LongVector.broadcast(Vectors.SPECIES, sinkPredPreRhs);
        LongVector sinkCmpRhsVec   = LongVector.broadcast(Vectors.SPECIES, sinkPredCmpRhs);
        LongVector[] extraVecs     = broadcastExtras(extraLits);

        LongVector accVec = LongVector.zero(Vectors.SPECIES);
        long countAcc = 0;
        // Short-circuit state, with the SAME meaning the scalar loop gives it:
        // for ANY and FIND `found` is a match, for ALL and NONE it is a
        // COUNTEREXAMPLE, and `flpResult` reads it that way for each kind.
        boolean found = false;
        long foundVal = 0;
        boolean reduceNeedInit = sinkKind == MezzeBytecodeRootNode.FLP_SINK_REDUCE;
        long scalarAcc = foldInit;

        int bound = Vectors.SPECIES.loopBound(n);
        int i = 0;

        for (; i < bound; i += Vectors.VL) {
            LongVector v = LongVector.fromArray(Vectors.SPECIES, data, i);

            // Filter before or after the map, as written — see the range
            // pipeline above for what ignoring this cost.
            VectorMask<Long> mask;
            if (filterCmpOp >= 0 && filterFirst) {
                LongVector fv = filterPreOp >= 0
                        ? simdOp(filterPreOp, v, filterPreRhsVec) : v;
                mask = simdCmp(filterCmpOp, fv, filterCmpRhsVec);
            } else {
                mask = Vectors.SPECIES.maskAll(true);
            }

            if (mapOp >= 0) {
                v = simdOp(mapOp, v, selfMap ? v : mapOperandVec);
            }
            if (extraOps != 0) v = simdApplyExtraMaps(extraOps, extraVecs, v);

            if (filterCmpOp >= 0 && !filterFirst) {
                LongVector fv = filterPreOp >= 0
                        ? simdOp(filterPreOp, v, filterPreRhsVec) : v;
                mask = simdCmp(filterCmpOp, fv, filterCmpRhsVec);
            }

            if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_SUM) {
                accVec = accVec.add(v, mask);
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_COUNT) {
                countAcc += mask.trueCount();
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_ANY
                    || sinkKind == MezzeBytecodeRootNode.FLP_SINK_FIND
                    || sinkKind == MezzeBytecodeRootNode.FLP_SINK_NONE) {
                // A HIT in any lane ends it. Lanes ascend, so the lowest set
                // lane is the first match in SOURCE order, which is what
                // `find` has to return.
                VectorMask<Long> hit = sinkMask(v, mask, sinkPredPreOp,
                        sinkPreRhsVec, sinkPredCmpOp, sinkCmpRhsVec);
                if (hit.anyTrue()) {
                    found = true;
                    if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_FIND) {
                        foundVal = v.lane(hit.firstTrue());
                    }
                    break;
                }
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_ALL) {
                // `found` is a COUNTEREXAMPLE here, as in the scalar loop: a
                // surviving lane that FAILS the predicate. A lane the filter
                // dropped is not a counterexample, which is why the stage mask
                // is ANDed in rather than ignored.
                VectorMask<Long> pass = sinkMask(v, mask, sinkPredPreOp,
                        sinkPreRhsVec, sinkPredCmpOp, sinkCmpRhsVec);
                if (mask.andNot(pass).anyTrue()) { found = true; break; }
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
        // `found` means the vector loop already answered; the remaining
        // elements cannot change it, for any of these four sinks.
        for (; i < n && !found; i++) {
            long v = data[i];
            if (filterCmpOp >= 0 && filterFirst) {
                long fv = filterPreOp >= 0 ? flpOp(filterPreOp, v, filterPreRhs) : v;
                if (!flpCmp(filterCmpOp, fv, filterCmpRhs)) continue;
            }
            if (mapOp >= 0) v = flpOp(mapOp, v, selfMap ? v : mapOperand);
            if (extraOps != 0) v = MezzeBytecodeRootNode.flpApplyExtraMaps(
                    extraOps, extraLits, v);
            if (filterCmpOp >= 0 && !filterFirst) {
                long fv = filterPreOp >= 0 ? flpOp(filterPreOp, v, filterPreRhs) : v;
                if (!flpCmp(filterCmpOp, fv, filterCmpRhs)) continue;
            }
            if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_SUM) {
                scalarAcc = flpOp(foldOp, scalarAcc, v);
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_COUNT) {
                countAcc++;
            } else if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_REDUCE) {
                if (reduceNeedInit) { scalarAcc = v; reduceNeedInit = false; }
                else scalarAcc = flpOp(foldOp, scalarAcc, v);
            } else {
                // The same four sinks, element at a time, for the elements
                // past the vector bound. Written exactly as the scalar
                // pipeline writes them so the two cannot drift.
                boolean hit;
                if (sinkPredCmpOp < 0) {
                    hit = true;
                } else {
                    long sv = sinkPredPreOp >= 0
                            ? flpOp(sinkPredPreOp, v, sinkPredPreRhs) : v;
                    hit = flpCmp(sinkPredCmpOp, sv, sinkPredCmpRhs);
                }
                if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_ALL) {
                    if (!hit) found = true;
                } else if (hit) {
                    found = true;
                    if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_FIND) foundVal = v;
                }
            }
        }

        if (sinkKind == MezzeBytecodeRootNode.FLP_SINK_ANY
                || sinkKind == MezzeBytecodeRootNode.FLP_SINK_ALL
                || sinkKind == MezzeBytecodeRootNode.FLP_SINK_FIND
                || sinkKind == MezzeBytecodeRootNode.FLP_SINK_NONE) {
            return MezzeBytecodeRootNode.FusedLongPipeline.flpResult(
                    sinkKind, 0, found, foundVal);
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

    /**
     * Map stages TWO AND LATER, lanewise.
     *
     * <p>WHY THE VECTOR LOOPS NEEDED THIS AT ALL. When maps two and later were
     * first fused they went into the SCALAR pipeline only, and these two vector
     * loops declined whenever a second map was present because they carry ONE
     * map positionally. That left the measured gap exactly where it started:
     * `bench/cases/iterstage` put a one-stage pipeline at 75 ps/elem and a
     * two-stage one at ~423, and disabling the vector path moved the ONE-STAGE
     * row from 75 to 292 -- so the floor was never the scalar loop, it was
     * this file, and the ~3.9x it carries was the whole cliff.
     *
     * <p>A RUNTIME SWITCH IS AFFORDABLE HERE, unlike in the partial-evaluated
     * scalar loop where reading an op out of an array cost ~760 ps a stage.
     * These methods are `@TruffleBoundary`: nothing is PE-folded, `mapOp` is
     * ALREADY a runtime int, and {@link #simdOp}'s switch already runs per
     * vector iteration -- so its cost is divided by the vector length rather
     * than paid per element.
     */
    private static LongVector simdApplyExtraMaps(
            int extraOps, LongVector[] litVecs, LongVector v) {
        for (int s = 0; s < MezzeBytecodeRootNode.FLP_MAX_EXTRA_MAPS; s++) {
            int field = (extraOps >>> (s * 5)) & 0x1F;
            if (field == 0) break;
            v = simdOp((field & 0xF) - 1, v, (field & 0x10) != 0 ? v : litVecs[s]);
        }
        return v;
    }

    /** Broadcast each extra stage's literal ONCE, outside the loop. */
    private static LongVector[] broadcastExtras(long[] extraLits) {
        LongVector[] out = new LongVector[extraLits.length];
        for (int s = 0; s < extraLits.length; s++) {
            out[s] = LongVector.broadcast(Vectors.SPECIES, extraLits[s]);
        }
        return out;
    }

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
