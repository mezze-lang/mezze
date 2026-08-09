package com.mezze.datastructures.nodes;

import com.mezze.datastructures.seq.LongSeqStorage;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD-accelerated fold-with-primitive-op over a {@link LongSeqStorage}.
 *
 * <p>Generalizes {@link SeqSumLongSIMDNode} across every associative
 * primitive op. Once the compiler proves a user's `xs.fold {init, f = {a,x} -> a op x}`
 * has (a) unboxed long storage and (b) a folder body that collapses to
 * a supported InlineOp, it can route the call directly here — same
 * zero-abstraction perf as `seq::sum`.
 *
 * <p>Supported ops (only associative + vectorizable via Vector API):
 * ADD, MUL, MIN, MAX, AND, OR, XOR. Non-associative ops (SUB, DIV,
 * MOD) can't be freely reordered so no SIMD form is exposed — the
 * fusion path stays on the scalar counted loop for those.
 *
 * <p>The op is a compile-time constant (int ordinal) — Truffle
 * specializes per-op so the JIT sees straight-line SIMD code per op
 * without runtime dispatch inside the loop.
 */
@NodeInfo(shortName = "seq.reduce.long.simd")
public abstract class SeqReduceLongSIMDNode extends Node {

    private static final VectorSpecies<Long> SPECIES = LongVector.SPECIES_PREFERRED;
    private static final int VECTOR_LENGTH = SPECIES.length();

    /** Op enum — kept in sync with {@code InlineOp} on the Mezze side.
     *  Only associative-reducible ops are listed. */
    public static final int OP_ADD = 0;
    public static final int OP_MUL = 1;
    public static final int OP_MIN = 2;
    public static final int OP_MAX = 3;
    public static final int OP_AND = 4;
    public static final int OP_OR  = 5;
    public static final int OP_XOR = 6;

    /** Identity value for each op — used both as SIMD-vector seed and
     *  as the return value on empty input (matches user's `init` when
     *  init equals the op's identity; otherwise the caller passes
     *  init separately via the initial merge). */
    private static long identity(int op) {
        switch (op) {
            case OP_ADD: return 0L;
            case OP_MUL: return 1L;
            case OP_MIN: return Long.MAX_VALUE;
            case OP_MAX: return Long.MIN_VALUE;
            case OP_AND: return -1L;
            case OP_OR:  return 0L;
            case OP_XOR: return 0L;
            default:
                throw new IllegalStateException("SeqReduceLongSIMDNode: unsupported op " + op);
        }
    }

    public abstract long execute(int op, long init, LongSeqStorage storage);

    @Specialization(guards = "storage.size() == 0")
    protected long doEmpty(int op, long init, LongSeqStorage storage) {
        return init;
    }

    @Specialization(guards = {"storage.size() > 0", "storage.size() <= 4"})
    @ExplodeLoop
    protected long doTiny(int op, long init, LongSeqStorage storage) {
        int size = storage.size();
        long[] data = storage.getArray();
        long acc = init;
        for (int i = 0; i < size; i++) {
            acc = apply(op, acc, data[i]);
        }
        return acc;
    }

    @Specialization(guards = {"storage.size() > 4", "storage.size() < 64"})
    protected long doSmall(int op, long init, LongSeqStorage storage) {
        long[] data = storage.getArray();
        int size = storage.size();
        long acc = init;
        for (int i = 0; i < size; i++) {
            acc = apply(op, acc, data[i]);
        }
        return acc;
    }

    @Specialization(guards = "storage.size() >= 64")
    @TruffleBoundary
    protected long doSIMD(int op, long init, LongSeqStorage storage) {
        long[] data = storage.getArray();
        int size = storage.size();
        long id = identity(op);

        // Bulk SIMD reduction seeded with op's identity.
        LongVector acc = LongVector.broadcast(SPECIES, id);
        int i = 0;
        int limit = SPECIES.loopBound(size);

        switch (op) {
            case OP_ADD:
                for (; i < limit; i += VECTOR_LENGTH) {
                    acc = acc.add(LongVector.fromArray(SPECIES, data, i));
                }
                break;
            case OP_MUL:
                for (; i < limit; i += VECTOR_LENGTH) {
                    acc = acc.mul(LongVector.fromArray(SPECIES, data, i));
                }
                break;
            case OP_MIN:
                for (; i < limit; i += VECTOR_LENGTH) {
                    acc = acc.min(LongVector.fromArray(SPECIES, data, i));
                }
                break;
            case OP_MAX:
                for (; i < limit; i += VECTOR_LENGTH) {
                    acc = acc.max(LongVector.fromArray(SPECIES, data, i));
                }
                break;
            case OP_AND:
                for (; i < limit; i += VECTOR_LENGTH) {
                    acc = acc.lanewise(VectorOperators.AND, LongVector.fromArray(SPECIES, data, i));
                }
                break;
            case OP_OR:
                for (; i < limit; i += VECTOR_LENGTH) {
                    acc = acc.lanewise(VectorOperators.OR, LongVector.fromArray(SPECIES, data, i));
                }
                break;
            case OP_XOR:
                for (; i < limit; i += VECTOR_LENGTH) {
                    acc = acc.lanewise(VectorOperators.XOR, LongVector.fromArray(SPECIES, data, i));
                }
                break;
            default:
                throw new IllegalStateException("SeqReduceLongSIMDNode: unsupported op " + op);
        }

        long result = reduceLanes(op, acc);

        // Merge user's init and tail elements into the final result.
        result = apply(op, init, result);
        for (; i < size; i++) {
            result = apply(op, result, data[i]);
        }
        return result;
    }

    /** Reduce a Vector to a scalar for the given op. */
    private static long reduceLanes(int op, LongVector v) {
        switch (op) {
            case OP_ADD: return v.reduceLanes(VectorOperators.ADD);
            case OP_MUL: return v.reduceLanes(VectorOperators.MUL);
            case OP_MIN: return v.reduceLanes(VectorOperators.MIN);
            case OP_MAX: return v.reduceLanes(VectorOperators.MAX);
            case OP_AND: return v.reduceLanes(VectorOperators.AND);
            case OP_OR:  return v.reduceLanes(VectorOperators.OR);
            case OP_XOR: return v.reduceLanes(VectorOperators.XOR);
            default: throw new IllegalStateException("reduceLanes: op " + op);
        }
    }

    /** Scalar apply for the tail / small-size paths. */
    private static long apply(int op, long a, long b) {
        switch (op) {
            case OP_ADD: return a + b;
            case OP_MUL: return a * b;
            case OP_MIN: return Math.min(a, b);
            case OP_MAX: return Math.max(a, b);
            case OP_AND: return a & b;
            case OP_OR:  return a | b;
            case OP_XOR: return a ^ b;
            default: throw new IllegalStateException("apply: op " + op);
        }
    }

    public static SeqReduceLongSIMDNode create() {
        return SeqReduceLongSIMDNodeGen.create();
    }

    public static SeqReduceLongSIMDNode getUncached() {
        return SeqReduceLongSIMDNodeGen.getUncached();
    }
}
