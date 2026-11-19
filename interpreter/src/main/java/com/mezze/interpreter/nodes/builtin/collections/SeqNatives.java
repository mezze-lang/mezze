package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.deque.MezzeDeque;
import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.*;
import com.mezze.datastructures.set.MezzeSet;
import com.mezze.datastructures.set.ElementHashing;
import com.mezze.datastructures.sorted.MezzeSortedMap;
import com.mezze.datastructures.sorted.MezzeSortedSet;
import com.mezze.datastructures.nodes.*;
import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Arrays;

/**
 * Native Seq operations - backed by optimized MezzeSeq.
 *
 * Seq is represented as MezzeSeq (from the datastructures module).
 * All operations are designed for JIT compilation:
 * - Primitive operations are inline-able
 * - Complex operations use @TruffleBoundary
 * - Immutable with structural sharing
 */
public final class SeqNatives {

    private SeqNatives() {}

    // ==================== Cached Field Name Arrays ====================
    // Avoid per-call allocation of common field name patterns
    // Using short, consistent names: fst/snd for pairs, pass/fail for partitions
    private static final String[] FIELD_X = {"x"};
    private static final String[] FIELD_I = {"i"};
    private static final String[] FIELD_ACC_X = {"acc", "x"};
    private static final String[] FIELD_A_B = {"a", "b"};
    private static final String[] FIELD_FST_SND =
        {com.mezze.language.truffleir.NativeHofNames.PAIR_FST, com.mezze.language.truffleir.NativeHofNames.PAIR_SND};
    private static final String[] FIELD_VALUE_INDEX = {"value", "index"};
    private static final String[] FIELD_FSTS_SNDS = {"fsts", "snds"};
    private static final String[] FIELD_PASS_FAIL = {"pass", "fail"};
    private static final String[] FIELD_KEY_VALUES = {"key", "values"};
    private static final String[] FIELD_L_R = {"l", "r"};
    private static final String[] FIELD_START_END = {"start", "end"};
    private static final String[] FIELD_FST_SND_THD =
        {com.mezze.language.truffleir.NativeHofNames.PAIR_FST, com.mezze.language.truffleir.NativeHofNames.PAIR_SND, com.mezze.language.truffleir.NativeHofNames.PAIR_THD};
    private static final String[] FIELD_FST_SND_THD_FTH =
        {com.mezze.language.truffleir.NativeHofNames.PAIR_FST, com.mezze.language.truffleir.NativeHofNames.PAIR_SND, com.mezze.language.truffleir.NativeHofNames.PAIR_THD, com.mezze.language.truffleir.NativeHofNames.PAIR_FTH};

    // ==================== Basic Operations ====================

    public static MezzeSeq empty() {
        return MezzeSeq.empty();
    }

    public static long length(MezzeSeq seq) {
        return seq.size();
    }

    public static boolean isEmpty(MezzeSeq seq) {
        return seq.isEmpty();
    }

    public static MezzeVariant get(MezzeSeq seq, long index) {
        int idx = (int) index;
        if (idx < 0 || idx >= seq.size()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(seq.get(idx));
    }

    /**
     * #418: Bounds-unchecked scalar getter. Skips the Option-alloc that
     * {@link #get} pays for; index is expected in-range because the
     * caller has already read `size()` and controls the loop bounds.
     *
     * <p>This is the primitive `seq.fold`/`seq.map`/`seq.reduce` in
     * Mezze needs to run without a per-iter allocation. User code
     * should keep using {@link #get} unless they've enforced the
     * bounds themselves.
     */
    public static Object getUnchecked(MezzeSeq seq, long index) {
        return seq.get((int) index);
    }

    // Simple delegation - no @TruffleBoundary needed for JIT inlining
    public static MezzeVariant set(MezzeSeq seq, long index, Object value) {
        int idx = (int) index;
        if (idx < 0 || idx >= seq.size()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(seq.update(idx, value));
    }

    // Simple delegation - no @TruffleBoundary needed for JIT inlining
    public static MezzeSeq add(MezzeSeq seq, Object element) {
        return seq.append(element);
    }

    // Simple delegation - no @TruffleBoundary needed for JIT inlining
    public static MezzeSeq push(MezzeSeq seq, Object element) {
        return seq.append(element);
    }

    // Simple delegation - no @TruffleBoundary needed for JIT inlining
    public static MezzeSeq pushFront(MezzeSeq seq, Object element) {
        return seq.prepend(element);
    }

    // Simple delegation - no @TruffleBoundary needed for JIT inlining
    public static MezzeVariant remove(MezzeSeq seq, long index) {
        int idx = (int) index;
        if (idx < 0 || idx >= seq.size()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(seq.remove(idx));
    }

    // Simple delegation - no @TruffleBoundary needed for JIT inlining
    public static MezzeSeq concat(MezzeSeq seq1, MezzeSeq seq2) {
        return seq1.concat(seq2);
    }

    // ==================== Access Operations ====================

    public static MezzeVariant head(MezzeSeq seq) {
        if (seq.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(seq.head());
    }

    public static MezzeVariant first(MezzeSeq seq) {
        return head(seq);
    }

    public static MezzeVariant last(MezzeSeq seq) {
        if (seq.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(seq.last());
    }

    // Simple delegation - no @TruffleBoundary needed for JIT inlining
    public static MezzeSeq tail(MezzeSeq seq) {
        if (seq.isEmpty()) {
            return MezzeSeq.empty();
        }
        return seq.tail();
    }

    // Simple delegation - no @TruffleBoundary needed for JIT inlining
    public static MezzeSeq init(MezzeSeq seq) {
        if (seq.isEmpty()) {
            return MezzeSeq.empty();
        }
        return seq.init();
    }

    // Simple delegation - no @TruffleBoundary needed for JIT inlining
    public static MezzeSeq slice(MezzeSeq seq, long start, long end) {
        int s = Math.max(0, (int) start);
        int e = Math.min(seq.size(), (int) end);
        if (s >= e) {
            return MezzeSeq.empty();
        }
        return seq.slice(s, e);
    }

    // Simple delegation - no @TruffleBoundary needed for JIT inlining
    public static MezzeSeq take(MezzeSeq seq, long n) {
        return seq.take((int) n);
    }

    // Simple delegation - no @TruffleBoundary needed for JIT inlining
    public static MezzeSeq drop(MezzeSeq seq, long n) {
        return seq.drop((int) n);
    }

    // ==================== Search Operations ====================

    @TruffleBoundary
    public static boolean contains(MezzeSeq seq, Object element) {
        return seq.contains(element);
    }

    @TruffleBoundary
    public static MezzeVariant indexOf(MezzeSeq seq, Object element) {
        int idx = seq.indexOf(element);
        if (idx < 0) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some((long) idx);
    }

    public static Object findIndex(MezzeSeq seq, MezzeFunction predicate,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "x");
        return CallbackLoops.run(0, seq.size(), new CallbackLoops.Body() {
            Object answer;
            public Object call(int i) { return ctx.call1(callNode, seq.get(i)); }
            public Object accept(int i, Object r) { if ((r instanceof Boolean && (Boolean) r)) { answer = MezzeVariant.some((long) i); return CallbackLoops.STOP; } return CallbackLoops.CONTINUE; }
            public Object finish() { return answer != null ? answer : MezzeVariant.none(); }
        });
    }

    public static Object find(MezzeSeq seq, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return searchFrom(seq, predicate, callNode, 0, SEARCH_FIND);
    }

    private static final int SEARCH_ANY = 0, SEARCH_ALL = 1, SEARCH_FIND = 2;
    private static final Object CONTINUE = new Object();

    /** Predicate scan shared by any/all/find: stops at the first element
     *  that decides the answer; a suspended predicate resumes the scan with the
     *  predicate's eventual answer for the same element. */
    private static Object searchFrom(MezzeSeq seq, MezzeFunction predicate,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode,
                                     int start, int mode) {
        Object[] closureValues = predicate.getClosureValues();
        int closureLen = closureValues.length;
        CallTarget innerTarget = ScalarBypass.tryResolve(predicate);
        CallTarget target = innerTarget != null ? innerTarget : predicate.getCallTarget();
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        int size = seq.size();
        for (int i = start; i < size; i++) {
            Object elem = seq.get(i);
            args[closureLen] = innerTarget != null ? elem : MezzeRecord.wrapX(elem);
            Object result = callNode.call(target, args);
            Object decided = searchDecide(mode, elem, result);
            if (decided != CONTINUE) return decided;
        }
        return searchDone(mode);
    }

    private static Object searchDecide(int mode, Object elem, Object answer) {
        boolean yes = answer instanceof Boolean && (Boolean) answer;
        switch (mode) {
            case SEARCH_ANY: return yes ? Boolean.TRUE : CONTINUE;
            case SEARCH_ALL: return yes ? CONTINUE : Boolean.FALSE;
            default: return yes ? MezzeVariant.some(elem) : CONTINUE;
        }
    }

    private static Object searchDone(int mode) {
        switch (mode) {
            case SEARCH_ANY: return Boolean.FALSE;
            case SEARCH_ALL: return Boolean.TRUE;
            default: return MezzeVariant.none();
        }
    }

    // ==================== Transform Operations ====================

    // Simple delegation - no @TruffleBoundary needed for JIT inlining
    public static MezzeSeq reverse(MezzeSeq seq) {
        return seq.reverse();
    }

    public static Object map(MezzeSeq seq, MezzeFunction mapper,
                             com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return mapFrom(seq, mapper, callNode, new Object[seq.size()], 0);
    }

    /** The map loop from element {@code start} into {@code result}. */
    private static Object mapFrom(MezzeSeq seq, MezzeFunction mapper,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode,
                                  Object[] result, int start) {
        Object[] closureValues = mapper.getClosureValues();
        int closureLen = closureValues.length;
        int size = seq.size();
        CallTarget innerTarget = ScalarBypass.tryResolve(mapper);
        CallTarget target = innerTarget != null ? innerTarget : mapper.getCallTarget();
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        for (int i = start; i < size; i++) {
            Object elem = seq.get(i);
            args[closureLen] = innerTarget != null ? elem : MezzeRecord.wrapX(elem);
            Object r = callNode.call(target, args);
            result[i] = r;
        }
        return MezzeSeq.fromArray(result);
    }

    public static Object filter(MezzeSeq seq, MezzeFunction predicate,
                                com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int size = seq.size();
        if (size == 0) return MezzeSeq.empty();
        return filterFrom(seq, predicate, callNode, new Object[Math.min(size, 16)], 0, 0);
    }

    /** The filter loop: {@code buf[0..count)} holds the kept elements so far. */
    private static Object filterFrom(MezzeSeq seq, MezzeFunction predicate,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode,
                                     Object[] buf, int count, int start) {
        int size = seq.size();
        Object[] closureValues = predicate.getClosureValues();
        int closureLen = closureValues.length;
        CallTarget innerTarget = ScalarBypass.tryResolve(predicate);
        CallTarget target = innerTarget != null ? innerTarget : predicate.getCallTarget();
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        for (int i = start; i < size; i++) {
            Object elem = seq.get(i);
            args[closureLen] = innerTarget != null ? elem : MezzeRecord.wrapX(elem);
            Object result = callNode.call(target, args);
            if (result instanceof Boolean && (Boolean) result) {
                if (count == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                buf[count++] = elem;
            }
        }
        return MezzeSeq.fromArray(count == buf.length ? buf : java.util.Arrays.copyOf(buf, count));
    }

    /**
     * `try_map`: `xs: [T], f: T -> Result U E → Result [U] E`.
     *
     * Applies `f` to every element in order. Short-circuits on the FIRST `Err`
     * — subsequent elements are not evaluated. Emitted by the codec generator
     * for `[T]` / `Seq T` decode (each element decode returns a Result; a
     * single element failure fails the whole list).
     *
     * The callback must return a `MezzeVariant` in the standard Result shape
     * (`Ok { val = ... }` / `Err { error = ... }`). Anything else is
     * propagated verbatim as a successful element.
     */
    public static Object tryMap(MezzeSeq seq, MezzeFunction mapper,
                                com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int size = seq.size();
        Object[] closureValues = mapper.getClosureValues();
        int closureLen = closureValues.length;
        com.oracle.truffle.api.CallTarget target = mapper.getCallTarget();
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        Object[] result = new Object[size];
        return CallbackLoops.run(0, size, new CallbackLoops.Body() {
            Object err;
            public Object call(int i) { args[closureLen] = MezzeRecord.wrapX(seq.get(i)); return callNode.call(target, args); }
            public Object accept(int i, Object elemResult) {
                if (elemResult instanceof MezzeVariant v) {
                    if (v.isErr()) { err = v; return CallbackLoops.STOP; }
                    if (v.isOk()) {
                        Object payload = v.getPayloadValue();
                        if (payload instanceof MezzeRecord r) {
                            try { result[i] = r.getFieldByName("val"); } catch (Exception e) { result[i] = payload; }
                        } else result[i] = payload;
                        return CallbackLoops.CONTINUE;
                    }
                }
                result[i] = elemResult;
                return CallbackLoops.CONTINUE;
            }
            public Object finish() { return err != null ? err : MezzeVariant.ok(MezzeSeq.fromArray(result)); }
        });
    }

    public static Object flatMap(MezzeSeq seq, MezzeFunction mapper,
                                 com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(mapper, "x");
        java.util.ArrayList<Object> results = new java.util.ArrayList<>();
        return CallbackLoops.run(0, seq.size(), new CallbackLoops.Body() {
            public Object call(int i) { return ctx.call1(callNode, seq.get(i)); }
            public Object accept(int i, Object mapped) {
                if (mapped instanceof MezzeSeq innerSeq) for (int j = 0; j < innerSeq.size(); j++) results.add(innerSeq.get(j));
                return CallbackLoops.CONTINUE;
            }
            public Object finish() { return MezzeSeq.fromArray(results.toArray()); }
        });
    }

    /** #403: fold hot loop — see {@link #foldTwoArg} for design notes. */
    public static Object fold(MezzeSeq seq, Object init, MezzeFunction folder,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return foldTwoArg(seq, init, folder, callNode, /*startIndex=*/0, /*scanOut=*/null);
    }

    /**
     * #417: SIMD-accelerated fold for `Seq Int + primitive op`. Emitted
     * by the compiler's fold-fusion classifier when it can prove:
     *   - xs is a materialized Seq (LongSeqStorage fast path, else boxed
     *     fallback loop);
     *   - the folder body is a pure primitive op we can dispatch on
     *     ({@link SeqReduceLongSIMDNode}'s OP_* constants).
     *
     * <p>Zero abstraction: user writes `xs.fold {init, f = {a,x} -> a + x}`
     * and this runs the exact same vectorized code as `xs.sum {}`.
     */
    @TruffleBoundary
    public static long foldReduceLong(MezzeSeq seq, int opOrdinal, long init) {
        SeqStorage storage = seq.getStorage();
        if (storage instanceof LongSeqStorage longStorage) {
            // GATED: the node names `jdk.incubator.vector` types with no guard of
            // its own, so reaching it on a runtime without the module is the BUG 43
            // failure (`NoClassDefFoundError: jdk/incubator/vector/Vector`). The
            // scalar loop below computes the same answer, and `sum` right down this
            // file already records that it is FASTER on SVM anyway.
            if (com.mezze.datastructures.util.VectorApi.AVAILABLE) {
                return SeqReduceLongSIMDNode.getUncached().execute(opOrdinal, init, longStorage);
            }
            long accScalar = init;
            long[] dataScalar = longStorage.getArray();
            int nScalar = longStorage.size();
            for (int i = 0; i < nScalar; i++) accScalar = applyLong(opOrdinal, accScalar, dataScalar[i]);
            return accScalar;
        }
        // Boxed-storage fallback: scalar reduce so correctness matches
        // even when someone slipped a heterogeneous Seq through the pipe.
        long acc = init;
        int n = seq.size();
        for (int i = 0; i < n; i++) {
            Object elem = seq.get(i);
            long v = (elem instanceof Long l) ? l : ((Number) elem).longValue();
            acc = applyLong(opOrdinal, acc, v);
        }
        return acc;
    }

    private static long applyLong(int op, long a, long b) {
        switch (op) {
            case SeqReduceLongSIMDNode.OP_ADD: return a + b;
            case SeqReduceLongSIMDNode.OP_MUL: return a * b;
            case SeqReduceLongSIMDNode.OP_MIN: return Math.min(a, b);
            case SeqReduceLongSIMDNode.OP_MAX: return Math.max(a, b);
            case SeqReduceLongSIMDNode.OP_AND: return a & b;
            case SeqReduceLongSIMDNode.OP_OR:  return a | b;
            case SeqReduceLongSIMDNode.OP_XOR: return a ^ b;
            default: throw new IllegalStateException("foldReduceLong: op " + op);
        }
    }

    /** Double-precision variant of {@link #foldReduceLong}. Same
     *  fast-path/fallback structure over {@link DoubleSeqStorage}. */
    @TruffleBoundary
    public static double foldReduceDouble(MezzeSeq seq, int opOrdinal, double init) {
        SeqStorage storage = seq.getStorage();
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            // GATED for the same reason as `foldReduceLong` above — see BUG 43.
            if (com.mezze.datastructures.util.VectorApi.AVAILABLE) {
                return SeqReduceDoubleSIMDNode.getUncached().execute(opOrdinal, init, doubleStorage);
            }
            double accScalar = init;
            double[] dataScalar = doubleStorage.getArray();
            int nScalar = doubleStorage.size();
            for (int i = 0; i < nScalar; i++) accScalar = applyDouble(opOrdinal, accScalar, dataScalar[i]);
            return accScalar;
        }
        double acc = init;
        int n = seq.size();
        for (int i = 0; i < n; i++) {
            Object elem = seq.get(i);
            double v = (elem instanceof Double d) ? d : ((Number) elem).doubleValue();
            acc = applyDouble(opOrdinal, acc, v);
        }
        return acc;
    }

    private static double applyDouble(int op, double a, double b) {
        switch (op) {
            case SeqReduceDoubleSIMDNode.OP_ADD: return a + b;
            case SeqReduceDoubleSIMDNode.OP_MUL: return a * b;
            case SeqReduceDoubleSIMDNode.OP_MIN: return Math.min(a, b);
            case SeqReduceDoubleSIMDNode.OP_MAX: return Math.max(a, b);
            default: throw new IllegalStateException("foldReduceDouble: op " + op);
        }
    }

    public static Object reduce(MezzeSeq seq, MezzeFunction reducer,
                                com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (seq.isEmpty()) return MezzeVariant.none();
        return MezzeVariant.some(foldTwoArg(seq, seq.get(0), reducer, callNode, /*startIndex=*/1, /*scanOut=*/null));
    }

    // ==================== Predicate Operations ====================

    public static Object any(MezzeSeq seq, MezzeFunction predicate,
                             com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return searchFrom(seq, predicate, callNode, 0, SEARCH_ANY);
    }

    public static Object all(MezzeSeq seq, MezzeFunction predicate,
                             com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return searchFrom(seq, predicate, callNode, 0, SEARCH_ALL);
    }

    public static Object none(MezzeSeq seq, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return !(Boolean) (any(seq, predicate, callNode));
    }

    public static Object count(MezzeSeq seq, MezzeFunction predicate,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "x");
        return CallbackLoops.run(0, seq.size(), new CallbackLoops.Body() {
            long n;
            public Object call(int i) { return ctx.call1(callNode, seq.get(i)); }
            public Object accept(int i, Object r) { if ((r instanceof Boolean && (Boolean) r)) n++; return CallbackLoops.CONTINUE; }
            public Object finish() { return n; }
        });
    }

    // ==================== Zip/Unzip Operations ====================

    @TruffleBoundary
    public static MezzeSeq zip(MezzeSeq seq1, MezzeSeq seq2) {
        int len = Math.min(seq1.size(), seq2.size());
        Object[] result = new Object[len];
        for (int i = 0; i < len; i++) {
            result[i] = MezzeRecord.fromFields(
                FIELD_FST_SND,
                new Object[]{seq1.get(i), seq2.get(i)}
            );
        }
        return MezzeSeq.fromArray(result);
    }

    @TruffleBoundary
    public static MezzeSeq zipWithIndex(MezzeSeq seq) {
        Object[] result = new Object[seq.size()];
        for (int i = 0; i < seq.size(); i++) {
            result[i] = MezzeRecord.fromFields(
                FIELD_VALUE_INDEX,
                new Object[]{seq.get(i), (long) i}
            );
        }
        return MezzeSeq.fromArray(result);
    }

    @TruffleBoundary
    public static MezzeRecord unzip(MezzeSeq seq) {
        Object[] firsts = new Object[seq.size()];
        Object[] seconds = new Object[seq.size()];
        for (int i = 0; i < seq.size(); i++) {
            MezzeRecord pair = (MezzeRecord) seq.get(i);
            // Try both naming conventions: fst/snd and first/second
            int firstIdx = pair.getFieldIndex("fst");
            if (firstIdx < 0) firstIdx = pair.getFieldIndex("first");
            int secondIdx = pair.getFieldIndex("snd");
            if (secondIdx < 0) secondIdx = pair.getFieldIndex("second");
            firsts[i] = pair.getField(firstIdx);
            seconds[i] = pair.getField(secondIdx);
        }
        return MezzeRecord.fromFields(
            FIELD_FSTS_SNDS,
            new Object[]{MezzeSeq.fromArray(firsts), MezzeSeq.fromArray(seconds)}
        );
    }

    // ==================== Partition Operations ====================

    public static Object partition(MezzeSeq seq, MezzeFunction predicate,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int size = seq.size();
        if (size == 0) {
            return MezzeRecord.fromFields(FIELD_PASS_FAIL, new Object[]{MezzeSeq.empty(), MezzeSeq.empty()});
        }
        var ctx = ScalarCall.prepare1(predicate, "x");
        boolean[] matches = new boolean[size];
        return CallbackLoops.run(0, size, new CallbackLoops.Body() {
            int matchCount;
            public Object call(int i) { return ctx.call1(callNode, seq.get(i)); }
            public Object accept(int i, Object r) { if ((r instanceof Boolean && (Boolean) r)) { matches[i] = true; matchCount++; } return CallbackLoops.CONTINUE; }
            public Object finish() {
                Object[] matchingArr = new Object[matchCount];
                Object[] nonMatchingArr = new Object[size - matchCount];
                int m = 0, nm = 0;
                for (int i = 0; i < size; i++) { if (matches[i]) matchingArr[m++] = seq.get(i); else nonMatchingArr[nm++] = seq.get(i); }
                return MezzeRecord.fromFields(FIELD_PASS_FAIL, new Object[]{MezzeSeq.fromArray(matchingArr), MezzeSeq.fromArray(nonMatchingArr)});
            }
        });
    }

    // ==================== Sort Operations ====================

    /**
     * Natural-order sort with NO guest call and NO boxing — the whole point.
     *
     * <p>WHAT THIS REPLACES. `seq::sort` routed through `sort_by`, so each of the
     * roughly n log n comparisons ran a guest lambda. MEASURED at a hundred
     * descending elements: 1 291 024 ps, against 68 387 for `Arrays.sort(long[])`
     * over the same input and 266 065 for boxed TimSort. The comparator was the
     * cost, not the sort.
     *
     * <p>Reached ONLY when the compiler proved the element directly comparable —
     * {@code OrdKind.DIRECT}, which is Int, Float and Str and nothing else. For
     * every other element {@code TypedASTCompiler} rewrites the call to
     * {@code __native_seq_sort_by_ord} and hands over that element's own `le`.
     * That gate is not a nicety: a `newtype R = Int` with a reversed `Ord`
     * compares as a long here and would silently contradict the order its author
     * wrote. See known-bugs.md BUG 10, which is this bug in min_by's clothing.
     *
     * <p>THE PREVIOUS BODY WAS DEAD AND WRONG. `__native_seq_sort` was registered
     * in both {@code NativeDispatch} and {@code SeqOps.kt} and called from
     * nowhere, and its comparator ended {@code return 0} — so had anything
     * reached it with a record, TimSort would have treated every pair as
     * equal and handed back the original order as sorted. An unreachable
     * wrong answer is still a wrong answer waiting for a caller.
     *
     * <p>The tag switch is a carried decision, not a type guess: the storage
     * tier IS the element representation. Anything else throws, because the
     * compiler promised DIRECT and a broken promise is a compiler bug — there is
     * no sensible order to fall back to, and inventing one is what the old
     * {@code return 0} did.
     */
    @TruffleBoundary
    public static MezzeSeq sort(MezzeSeq seq) {
        int n = seq.size();
        if (n <= 1) return seq;
        byte tag = seq.getStrategyTag();
        if (tag == SeqStorage.TAG_LONG) {
            long[] arr = seq.toLongArray();        // already a defensive copy
            Arrays.sort(arr);                      // dual-pivot quicksort, no comparator
            return MezzeSeq.fromStorage(new LongSeqStorage(arr));
        }
        if (tag == SeqStorage.TAG_DOUBLE) {
            double[] arr = seq.toDoubleArray();
            Arrays.sort(arr);
            return MezzeSeq.fromStorage(new DoubleSeqStorage(arr));
        }
        // The object tier holds whatever the element is, so Str is CHECKED here
        // rather than assumed. The scan is O(n) against the sort's O(n log n),
        // and it buys an error that names the broken promise instead of a
        // ClassCastException from inside TimSort.
        Object[] arr = seq.toArray();
        for (Object elem : arr) {
            if (!(elem instanceof String)) {
                throw new IllegalStateException(
                    "__native_seq_sort reached a " + (elem == null ? "null" : elem.getClass().getName())
                    + " element: this native sorts only the directly comparable elements"
                    + " (Int, Float, Str). Anything else must have been rewritten to"
                    + " __native_seq_sort_by_ord by TypedASTCompiler, so this is a"
                    + " compiler bug, not a program error.");
            }
        }
        Arrays.sort(arr);
        return MezzeSeq.fromArray(arr);
    }

    /**
     * Natural-order sort through the element's OWN `Ord` — the general path.
     *
     * <p>This is where every element the compiler could not prove directly
     * comparable goes: records, variants, and above all a newtype whose `Ord`
     * disagrees with the type it wraps. `le` is `Ord`'s own operation and
     * answers True when `it` should come before `r`, which is exactly the
     * contract {@code sortBy} already documents, so the normalisation to
     * -1 / 1 happens here rather than in a guest wrapper.
     *
     * <p>ONE `le` per comparison, which is what this path already cost: the
     * stdlib wrapped the comparator as `if cmp { ... } then 0 - 1 else 1` and
     * `sort` went through it. That shape never answers 0, so two equal elements
     * compare as -1 in BOTH directions and TimSort's "Comparison method
     * violates its general contract" check can in principle fire. The property
     * is inherited, not introduced — `le(a,b) ? (le(b,a) ? 0 : -1) : 1` would
     * fix it at about 1.5 guest calls per comparison, which is a change to the
     * general path's cost and belongs with its own measurement, not here.
     *
     * <p>Through {@link ScalarCall} for the same reason {@code sortBy} is: it
     * resolves the scalar-ABI bypass once and reuses one args array for the
     * whole sort, where a per-comparison {@code prepareArgsComparator} allocates
     * an {@code Object[]} and a {@code MezzeRecord} every time.
     */
    public static MezzeSeq sortByOrd(MezzeSeq seq, MezzeFunction leFn,
                                       com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int n = seq.size();
        if (n <= 1) return seq;
        var ctx = ScalarCall.prepare2(leFn, "it", "r");
        Object[] arr = seq.toArray();
        Arrays.sort(arr, (a, b) -> ctx.callBool(callNode, a, b) ? -1 : 1);
        return MezzeSeq.fromArray(arr);
    }

    /**
     * Sort by a guest comparator — through {@link ScalarCall}, which is what
     * every other native HOF here uses and what this one skipped.
     *
     * <p>It used to call {@code prepareArgsComparator} per comparison, and that
     * allocates TWICE every time: a fresh {@code Object[] args} and a
     * {@code MezzeRecord.wrapAB} to hold the pair. A sort does about
     * {@code n log n} comparisons, so a thousand elements paid roughly twenty
     * thousand allocations for argument passing alone.
     *
     * <p>{@code ScalarCall} exists for exactly this: it resolves the scalar-ABI
     * bypass ONCE and then reuses one positional args array for the whole sort,
     * so the scalar path allocates nothing per comparison. {@link
     * ScalarCall#callCmp} is even written for this caller — "keeps a sort stable
     * instead of corrupting it", says its doc — and had every collection's
     * min/max/compare as users while the sort it names did not use it.
     *
     * <p>{@code callCmp} also normalises with {@code Long.signum} where this
     * read {@code intValue()}, which is strictly safer but is NOT a bug fix
     * today and should not be sold as one: {@code intValue()} narrows, so a
     * comparator answering a magnitude could have its sign flipped or zeroed —
     * but `std::collections::seq` wraps every comparator as
     * {@code if cmp {...} then 0 - 1 else 1}, so the only values reaching here
     * are -1 and 1. It matters if a caller ever hands this native a raw
     * three-way comparator.
     */
    public static MezzeSeq sortBy(MezzeSeq seq, MezzeFunction cmpFn,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(cmpFn, "a", "b");
        Object[] arr = seq.toArray();
        Arrays.sort(arr, (elemA, elemB) -> ctx.callCmp(callNode, elemA, elemB));
        return MezzeSeq.fromArray(arr);
    }

    // ==================== Unique/Flatten Operations ====================
    //
    // `seq::unique` WAS O(n^2), and its own comment said why: "Eq is not
    // required to be hashable (only equatable)", so every element was compared
    // against every survivor through a guest call. MEASURED at two element
    // counts, which is what distinguishes a quadratic from an expensive
    // constant: 40 243 498 ps at a hundred elements and 4 169 669 450 at a
    // thousand — 103.6x for a tenfold n, an exponent of 2.02, where
    // `seq::dedup` over the same input grew 9.45x.
    //
    // The signature is now `with Eq 'a, Hash 'a`. That is a user-facing
    // decision and not a performance one: a caller whose element has a
    // hand-written `Eq` and no `Hash` stops compiling, which is the honest
    // outcome, because that combination is precisely what the quadratic paid
    // for.

    /**
     * First occurrence of each element, bucketing STRUCTURALLY — O(n).
     *
     * <p>`LinkedHashSet` is not an approximation of the structural set here, it
     * is the same semantics: a structurally-bucketed `MezzeSet` hashes with
     * `hashCode()` through `TrieHash.mix32` and compares with
     * `Objects.equals`, which is what `HashSet` does, and `LinkedHashSet` adds
     * exactly the insertion order that "keeps the first occurrence" requires.
     *
     * <p>Correct only when the element's `Eq` and `Hash` are the compiler's
     * own. When the author wrote them, `TypedASTCompiler.evidenceNativeForm`
     * routes to {@link #uniqueHashedWithEvidence} instead, under the rule it
     * already applies to `set::from_seq`.
     */
    @TruffleBoundary
    public static MezzeSeq uniqueHashed(MezzeSeq seq) {
        int n = seq.size();
        if (n <= 1) return seq;
        java.util.LinkedHashSet<Object> seen = new java.util.LinkedHashSet<>(n * 2);
        for (int i = 0; i < n; i++) {
            seen.add(seq.get(i));
        }
        if (seen.size() == n) return seq;      // nothing to remove
        return MezzeSeq.fromArray(seen.toArray());
    }

    /**
     * The same, bucketing by the element's OWN `hash` and `eq` — O(n).
     *
     * <p>{@link ClosureHashing} supplies the pair or nothing: it returns null
     * unless both closures are present, because "a container that hashed by the
     * element's instance but compared structurally would be worse than either
     * alone" — equal values would land in one bucket and fail the comparison, or
     * land apart and never be compared. Null means the instances are derived
     * and the structural walk above is the right answer.
     *
     * <p>An open-addressed table of indices rather than a wrapper object per
     * element: the author's `hash` is then called once per element and the
     * author's `eq` only when two elements land in the same slot.
     */
    @TruffleBoundary
    public static MezzeSeq uniqueHashedWithEvidence(MezzeSeq seq, MezzeFunction hashFn,
                                                    MezzeFunction eqFn) {
        ElementHashing hashing = ClosureHashing.of(hashFn, eqFn);
        if (hashing == null) return uniqueHashed(seq);

        int n = seq.size();
        if (n <= 1) return seq;

        int cap = Integer.highestOneBit(Math.max(4, n)) << 2;   // load factor <= 0.5
        int mask = cap - 1;
        int[] slots = new int[cap];          // 0 = empty, else 1 + index into out
        int[] hashes = new int[n];
        Object[] out = new Object[n];
        int kept = 0;

        for (int i = 0; i < n; i++) {
            Object elem = seq.get(i);
            int h = hashing.hashOf(elem);
            int slot = (h ^ (h >>> 16)) & mask;
            boolean duplicate = false;
            while (slots[slot] != 0) {
                int at = slots[slot] - 1;
                // The stored hash is checked first so the author's `eq` is only
                // called for a genuine collision, not for every probe.
                if (hashes[at] == h && hashing.eq(out[at], elem)) {
                    duplicate = true;
                    break;
                }
                slot = (slot + 1) & mask;
            }
            if (!duplicate) {
                hashes[kept] = h;
                out[kept] = elem;
                slots[slot] = kept + 1;
                kept++;
            }
        }

        if (kept == n) return seq;
        return MezzeSeq.fromArray(java.util.Arrays.copyOf(out, kept));
    }

    @TruffleBoundary
    public static MezzeSeq flatten(MezzeSeq seq) {
        return seq.flatten();
    }

    // ==================== Additional Operations ====================

    @TruffleBoundary
    public static MezzeSeq intersperse(MezzeSeq seq, Object separator) {
        return seq.intersperse(separator);
    }

    @TruffleBoundary
    public static MezzeSeq chunk(MezzeSeq seq, long chunkSize) {
        return seq.chunk((int) chunkSize);
    }

    @TruffleBoundary
    public static MezzeSeq sliding(MezzeSeq seq, long windowSize) {
        return seq.sliding((int) windowSize);
    }

    public static Object takeWhile(MezzeSeq seq, MezzeFunction predicate,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "x");
        java.util.ArrayList<Object> results = new java.util.ArrayList<>();
        return CallbackLoops.run(0, seq.size(), new CallbackLoops.Body() {
            public Object call(int i) { return ctx.call1(callNode, seq.get(i)); }
            public Object accept(int i, Object r) { if ((r instanceof Boolean && (Boolean) r)) { results.add(seq.get(i)); return CallbackLoops.CONTINUE; } return CallbackLoops.STOP; }
            public Object finish() { return MezzeSeq.fromArray(results.toArray()); }
        });
    }

    public static Object dropWhile(MezzeSeq seq, MezzeFunction predicate,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "x");
        return CallbackLoops.run(0, seq.size(), new CallbackLoops.Body() {
            int dropCount;
            public Object call(int i) { return ctx.call1(callNode, seq.get(i)); }
            public Object accept(int i, Object r) { if ((r instanceof Boolean && (Boolean) r)) { dropCount++; return CallbackLoops.CONTINUE; } return CallbackLoops.STOP; }
            public Object finish() { return seq.drop(dropCount); }
        });
    }

    // ==================== Range ====================

    @TruffleBoundary
    public static MezzeSeq range(long start, long end) {
        return MezzeSeq.range(start, end);
    }

    @TruffleBoundary
    public static MezzeSeq rangeStep(long start, long end, long step) {
        return MezzeSeq.range(start, end, step);
    }

    // ==================== Equality ====================

    // Elementwise via the caller-supplied eq closure (the element's Eq impl), so
    // `[T] == [T]` agrees with `==` on the elements — structural MezzeSeq.equals
    // would ignore a custom Eq.
    public static boolean equals(MezzeSeq seq1, MezzeSeq seq2, MezzeFunction eqFn,
                                 com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (seq1.size() != seq2.size()) return false;
        for (int i = 0; i < seq1.size(); i++) {
            if (!eqCall(eqFn, seq1.get(i), seq2.get(i), callNode)) return false;
        }
        return true;
    }

    // ==================== Conversion ====================

    @TruffleBoundary
    public static Object[] toArray(MezzeSeq seq) {
        return seq.toArray();
    }

    @TruffleBoundary
    public static MezzeSeq fromArray(Object[] arr) {
        return MezzeSeq.fromArray(arr);
    }



    // Keys are compared via the threaded comparator (the key type's Ord impl),
    // not the primitive-only compare() — see SeqKeyOrdTest.
    public static MezzeVariant minBy(MezzeSeq seq, MezzeFunction keyFn, MezzeFunction cmpFn,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (seq.isEmpty()) return MezzeVariant.none();
        Object minElem = seq.get(0);
        Object minKey = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, minElem));
        for (int i = 1; i < seq.size(); i++) {
            Object elem = seq.get(i);
            Object key = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, elem));
            if (cmpInt(cmpFn, key, minKey, callNode) < 0) {
                minElem = elem;
                minKey = key;
            }
        }
        return MezzeVariant.some(minElem);
    }

    public static MezzeVariant maxBy(MezzeSeq seq, MezzeFunction keyFn, MezzeFunction cmpFn,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (seq.isEmpty()) return MezzeVariant.none();
        Object maxElem = seq.get(0);
        Object maxKey = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, maxElem));
        for (int i = 1; i < seq.size(); i++) {
            Object elem = seq.get(i);
            Object key = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, elem));
            if (cmpInt(cmpFn, key, maxKey, callNode) > 0) {
                maxElem = elem;
                maxKey = key;
            }
        }
        return MezzeVariant.some(maxElem);
    }

    @TruffleBoundary
    public static long sum(MezzeSeq seq) {
        SeqStorage storage = seq.getStorage();
        if (storage instanceof LongSeqStorage longStorage) {
            // Scalar array-loop over unboxed longs.
            // Prior versions routed through SeqSumLongSIMDNode / SeqSumSimd
            // (jdk.incubator.vector), but the Vector API has no SVM native
            // intrinsic — LongVector operations fall back to generic Java
            // and run ~7× slower than the plain scalar loop on the same
            // AOT binary (measured Apple M2, sum of 10M longs: SIMD 53ms,
            // scalar 7ms). On the JVM the JIT still intrinsifies both
            // paths so this is a no-op for `mez dev`/`mez build`.
            long[] data = longStorage.getArray();
            int n = longStorage.size();
            long total = 0;
            for (int i = 0; i < n; i++) total += data[i];
            return total;
        }
        // Fallback for boxed storage
        long total = 0;
        for (int i = 0; i < seq.size(); i++) {
            Object elem = seq.get(i);
            if (elem instanceof Long l) total += l;
            else if (elem instanceof Integer n) total += n;
        }
        return total;
    }

    @TruffleBoundary
    public static double sumFloat(MezzeSeq seq) {
        SeqStorage storage = seq.getStorage();
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            // See {@link #sum} for why we avoid the SIMD-node path.
            double[] data = doubleStorage.getArray();
            int n = doubleStorage.size();
            double total = 0.0;
            for (int i = 0; i < n; i++) total += data[i];
            return total;
        }
        // Fallback for boxed storage
        double total = 0.0;
        for (int i = 0; i < seq.size(); i++) {
            Object elem = seq.get(i);
            if (elem instanceof Double d) total += d;
            else if (elem instanceof Float f) total += f;
            else if (elem instanceof Long l) total += l;
            else if (elem instanceof Integer n) total += n;
        }
        return total;
    }

    @TruffleBoundary
    public static long product(MezzeSeq seq) {
        if (seq.isEmpty()) return 1L;
        // OPTIMIZED: Use specialized node for unboxed long storage
        SeqStorage storage = seq.getStorage();
        if (storage instanceof LongSeqStorage longStorage) {
            return SeqProductLongNode.getUncached().execute(longStorage);
        }
        // Fallback for boxed storage
        long result = 1;
        for (int i = 0; i < seq.size(); i++) {
            Object elem = seq.get(i);
            if (elem instanceof Long l) result *= l;
            else if (elem instanceof Integer n) result *= n;
        }
        return result;
    }

    @TruffleBoundary
    public static double productFloat(MezzeSeq seq) {
        if (seq.isEmpty()) return 1.0;
        // OPTIMIZED: Use specialized node for unboxed double storage
        SeqStorage storage = seq.getStorage();
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            return SeqProductDoubleNode.getUncached().execute(doubleStorage);
        }
        // Fallback for boxed storage
        double result = 1.0;
        for (int i = 0; i < seq.size(); i++) {
            Object elem = seq.get(i);
            if (elem instanceof Double d) result *= d;
            else if (elem instanceof Float f) result *= f;
            else if (elem instanceof Long l) result *= l;
            else if (elem instanceof Integer n) result *= n;
        }
        return result;
    }

    // ==================== Group By ====================

    public static Object groupBy(MezzeSeq seq, MezzeFunction keyFn,
                                 com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(keyFn, "x");
        java.util.LinkedHashMap<Object, java.util.ArrayList<Object>> groups = new java.util.LinkedHashMap<>();
        return CallbackLoops.run(0, seq.size(), new CallbackLoops.Body() {
            public Object call(int i) { return ctx.call1(callNode, seq.get(i)); }
            public Object accept(int i, Object key) { groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(seq.get(i)); return CallbackLoops.CONTINUE; }
            public Object finish() {
                Object[] result = new Object[groups.size()];
                int idx = 0;
                for (var entry : groups.entrySet()) {
                    result[idx++] = MezzeRecord.fromFields(FIELD_KEY_VALUES,
                        new Object[]{entry.getKey(), MezzeSeq.fromArray(entry.getValue().toArray())});
                }
                return MezzeSeq.fromArray(result);
            }
        });
    }

    /**
     * Group by key under the KEY's own instances (BUG 50, old series): two keys a
     * hand-written `Eq` calls equal land in one group, which a `LinkedHashMap`
     * keyed by Java `equals` never did. First-seen key order is kept, as the
     * structural form keeps it. Shared by the seq, list and set evidence forms;
     * each wraps the groups in its own container. Null when there is no evidence.
     */
    public static java.util.List<Object[]> groupKeysWithEvidence(
            Iterable<Object> elems, ScalarCall ctx,
            com.oracle.truffle.api.nodes.IndirectCallNode callNode, ElementHashing hashing) {
        java.util.ArrayList<Object[]> groups = new java.util.ArrayList<>();   // {key, ArrayList}
        java.util.HashMap<Integer, java.util.ArrayList<Integer>> byHash = new java.util.HashMap<>();
        for (Object elem : elems) {
            Object key = ctx.call1(callNode, elem);
            int h = hashing.hashOf(key);
            java.util.ArrayList<Integer> bucket = byHash.computeIfAbsent(h, k -> new java.util.ArrayList<>());
            Object[] found = null;
            for (int gi : bucket) {
                if (hashing.eq(groups.get(gi)[0], key)) { found = groups.get(gi); break; }
            }
            if (found == null) {
                found = new Object[]{key, new java.util.ArrayList<Object>()};
                bucket.add(groups.size());
                groups.add(found);
            }
            @SuppressWarnings("unchecked")
            java.util.ArrayList<Object> values = (java.util.ArrayList<Object>) found[1];
            values.add(elem);
        }
        return groups;
    }

    public static Object groupByWithEvidence(MezzeSeq seq, MezzeFunction keyFn,
                                               MezzeFunction hashFn, MezzeFunction eqFn,
                                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        ElementHashing hashing = ClosureHashing.of(hashFn, eqFn);
        if (hashing == null) return groupBy(seq, keyFn, callNode);
        var ctx = ScalarCall.prepare1(keyFn, "x");
        java.util.ArrayList<Object> elems = new java.util.ArrayList<>(seq.size());
        for (int i = 0; i < seq.size(); i++) elems.add(seq.get(i));
        var groups = groupKeysWithEvidence(elems, ctx, callNode, hashing);
        Object[] result = new Object[groups.size()];
        for (int i = 0; i < result.length; i++) {
            Object[] g = groups.get(i);
            result[i] = MezzeRecord.fromFields(FIELD_KEY_VALUES,
                new Object[]{g[0], MezzeSeq.fromArray(((java.util.ArrayList<?>) g[1]).toArray())});
        }
        return MezzeSeq.fromArray(result);
    }

    // ==================== Enumerate/ForEach/Scan ====================

    @TruffleBoundary
    public static MezzeSeq enumerate(MezzeSeq seq) {
        return zipWithIndex(seq);  // Same as zip_with_index
    }

    public static Object forEach(MezzeSeq seq, MezzeFunction fn,
                                 com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return forEachFrom(seq, fn, callNode, 0);
    }

    private static Object forEachFrom(MezzeSeq seq, MezzeFunction fn,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode, int start) {
        Object[] closureValues = fn.getClosureValues();
        int closureLen = closureValues.length;
        CallTarget innerTarget = ScalarBypass.tryResolve(fn);
        CallTarget target = innerTarget != null ? innerTarget : fn.getCallTarget();
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        for (int i = start; i < seq.size(); i++) {
            Object elem = seq.get(i);
            args[closureLen] = innerTarget != null ? elem : MezzeRecord.wrapX(elem);
            Object r = callNode.call(target, args);
        }
        return MezzeRecord.empty();  // Returns unit
    }

    public static Object scan(MezzeSeq seq, Object init, MezzeFunction fn,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] result = new Object[seq.size() + 1];
        result[0] = init;
        var f = new CallbackLoops.Call2(fn, callNode);
        return CallbackLoops.run(0, seq.size(), new CallbackLoops.Body() {
            Object acc = init;
            public Object call(int i) { return f.on(acc, seq.get(i)); }
            public Object accept(int i, Object r) { acc = r; result[i + 1] = acc; return CallbackLoops.CONTINUE; }
            public Object finish() { return MezzeSeq.fromArray(result); }
        });
    }

    // ==================== Split Operations ====================

    @TruffleBoundary
    public static MezzeRecord splitAt(MezzeSeq seq, long index) {
        int idx = Math.max(0, Math.min(seq.size(), (int) index));
        return MezzeRecord.fromFields(
            FIELD_L_R,
            new Object[]{seq.take(idx), seq.drop(idx)}
        );
    }

    public static MezzeRecord span(MezzeSeq seq, MezzeFunction predicate,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int splitIdx = 0;
        for (int i = 0; i < seq.size(); i++) {
            Object result = callNode.call(predicate.getCallTarget(), prepareArgs(predicate, seq.get(i)));
            if (result instanceof Boolean && (Boolean) result) {
                splitIdx++;
            } else {
                break;
            }
        }
        return MezzeRecord.fromFields(
            FIELD_L_R,
            new Object[]{seq.take(splitIdx), seq.drop(splitIdx)}
        );
    }

    // ==================== Dedup ====================

    @TruffleBoundary
    public static MezzeSeq dedup(MezzeSeq seq, MezzeFunction eqFn,
                                 com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (seq.isEmpty()) return seq;
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        Object last = seq.get(0);
        result.add(last);
        for (int i = 1; i < seq.size(); i++) {
            Object curr = seq.get(i);
            if (!eqCall(eqFn, curr, last, callNode)) {
                result.add(curr);
                last = curr;
            }
        }
        return MezzeSeq.fromArray(result.toArray());
    }

    public static MezzeSeq dedupBy(MezzeSeq seq, MezzeFunction keyFn, MezzeFunction eqFn,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (seq.isEmpty()) return seq;
        var eq = ScalarCall.prepare2(eqFn, "a", "b");
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        Object lastElem = seq.get(0);
        Object lastKey = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, lastElem));
        result.add(lastElem);
        for (int i = 1; i < seq.size(); i++) {
            Object elem = seq.get(i);
            Object key = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, elem));
            if (!eq.callEq(callNode, key, lastKey)) {
                result.add(elem);
                lastElem = elem;
                lastKey = key;
            }
        }
        return MezzeSeq.fromArray(result.toArray());
    }

    // ==================== Singleton/Nth/Fill ====================

    public static MezzeSeq singleton(Object element) {
        return MezzeSeq.of(element);
    }

    public static MezzeVariant nth(MezzeSeq seq, long index) {
        return get(seq, index);  // Same as get
    }

    @TruffleBoundary
    public static MezzeSeq fill(long count, Object value) {
        Object[] arr = new Object[(int) count];
        Arrays.fill(arr, value);
        return MezzeSeq.fromArray(arr);
    }

    public static MezzeSeq fillWith(long count, MezzeFunction fn,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] arr = new Object[(int) count];
        for (int i = 0; i < count; i++) {
            arr[i] = callNode.call(fn.getCallTarget(),
                prepareArgsIndex(fn, (long) i));
        }
        return MezzeSeq.fromArray(arr);
    }

    // ==================== Group Consecutive ====================

    @TruffleBoundary
    public static MezzeSeq groupConsecutive(MezzeSeq seq, MezzeFunction eqFn, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (seq.isEmpty()) return MezzeSeq.empty();
        var eq = ScalarCall.prepare2(eqFn, "a", "b");
        java.util.ArrayList<Object> groups = new java.util.ArrayList<>();
        java.util.ArrayList<Object> currentGroup = new java.util.ArrayList<>();
        Object currentKey = seq.get(0);
        currentGroup.add(currentKey);

        for (int i = 1; i < seq.size(); i++) {
            Object elem = seq.get(i);
            if (eq.callEq(callNode, elem, currentKey)) {
                currentGroup.add(elem);
            } else {
                groups.add(MezzeSeq.fromArray(currentGroup.toArray()));
                currentGroup = new java.util.ArrayList<>();
                currentGroup.add(elem);
                currentKey = elem;
            }
        }
        groups.add(MezzeSeq.fromArray(currentGroup.toArray()));
        return MezzeSeq.fromArray(groups.toArray());
    }

    public static MezzeSeq groupConsecutiveBy(MezzeSeq seq, MezzeFunction keyFn, MezzeFunction eqFn,
                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (seq.isEmpty()) return MezzeSeq.empty();
        java.util.ArrayList<Object> groups = new java.util.ArrayList<>();
        java.util.ArrayList<Object> currentGroup = new java.util.ArrayList<>();
        Object firstElem = seq.get(0);
        var eq = ScalarCall.prepare2(eqFn, "a", "b");
        Object currentKey = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, firstElem));
        currentGroup.add(firstElem);

        for (int i = 1; i < seq.size(); i++) {
            Object elem = seq.get(i);
            Object key = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, elem));
            if (eq.callEq(callNode, key, currentKey)) {
                currentGroup.add(elem);
            } else {
                groups.add(MezzeSeq.fromArray(currentGroup.toArray()));
                currentGroup = new java.util.ArrayList<>();
                currentGroup.add(elem);
                currentKey = key;
            }
        }
        groups.add(MezzeSeq.fromArray(currentGroup.toArray()));
        return MezzeSeq.fromArray(groups.toArray());
    }

    // ==================== Windows/Rotate ====================

    @TruffleBoundary
    public static MezzeSeq windows(MezzeSeq seq, long size) {
        return sliding(seq, size);  // Same as sliding
    }

    @TruffleBoundary
    public static MezzeSeq chunksExact(MezzeSeq seq, long chunkSize) {
        int cs = (int) chunkSize;
        int numChunks = seq.size() / cs;
        Object[] result = new Object[numChunks];
        for (int i = 0; i < numChunks; i++) {
            result[i] = seq.slice(i * cs, (i + 1) * cs);
        }
        return MezzeSeq.fromArray(result);
    }

    @TruffleBoundary
    public static MezzeSeq rotateLeft(MezzeSeq seq, long n) {
        if (seq.isEmpty()) return seq;
        int size = seq.size();
        int rot = ((int) n % size + size) % size;
        return seq.drop(rot).concat(seq.take(rot));
    }

    @TruffleBoundary
    public static MezzeSeq rotateRight(MezzeSeq seq, long n) {
        if (seq.isEmpty()) return seq;
        int size = seq.size();
        int rot = ((int) n % size + size) % size;
        return seq.drop(size - rot).concat(seq.take(size - rot));
    }


    public static MezzeVariant binarySearchBy(MezzeSeq seq, Object target, MezzeFunction cmpFn,
                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int lo = 0, hi = seq.size() - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            Object cmpResult = callNode.call(cmpFn.getCallTarget(),
                prepareArgsComparator(cmpFn, seq.get(mid), target));
            int cmp = cmpResult instanceof Long ? ((Long) cmpResult).intValue() : 0;
            if (cmp == 0) return MezzeVariant.some((long) mid);
            if (cmp < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return MezzeVariant.none();
    }

    // ==================== Sort By Key ====================

    public static MezzeSeq sortByKey(MezzeSeq seq, MezzeFunction keyFn, MezzeFunction cmpFn,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        // Both callbacks go through `ScalarCall`, which resolves the scalar-ABI
        // bypass ONCE and reuses one args array. This used to call
        // `prepareArgs` per element for the key and `cmpInt` per comparison,
        // and each of those allocates twice — an `Object[]` and a
        // `MezzeRecord` wrapper. At a hundred elements that is two hundred
        // allocations for the keys and about thirteen hundred for the
        // comparisons, which is most of what made this the worst seq operation
        // except `unique`.
        var keyCall = ScalarCall.prepare1(keyFn, "x");
        var cmp = ScalarCall.prepare2(cmpFn, "a", "b");

        // Pre-compute keys to avoid calling keyFn multiple times per element
        int size = seq.size();
        Object[] elements = new Object[size];
        Object[] keys = new Object[size];
        for (int i = 0; i < size; i++) {
            elements[i] = seq.get(i);
            keys[i] = keyCall.call1(callNode, elements[i]);
        }

        // Sort indices by key, comparing keys via the threaded comparator
        // (the key type's Ord impl), not the primitive-only compare().
        //
        // `Integer[]` stays: `indices[i] = i` looks like an allocation an
        // element and is not, because `Integer.valueOf` caches -128..127 —
        // checked on this JVM, identity holds at 99 and 127 and fails at 128.
        // Above 127 it would allocate, and `Arrays.sort` takes no comparator
        // over `int[]`, so replacing it means a hand-written index sort: its
        // own change, with its own measurement at a size where it bites.
        Integer[] indices = new Integer[size];
        for (int i = 0; i < size; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> cmp.callCmp(callNode, keys[a], keys[b]));

        // Reorder elements
        Object[] result = new Object[size];
        for (int i = 0; i < size; i++) {
            result[i] = elements[indices[i]];
        }
        return MezzeSeq.fromArray(result);
    }

    // ==================== Additional Search/Replace Operations ====================

    public static MezzeVariant lastIndexOf(MezzeSeq seq, Object element, MezzeFunction eqFn,
                                           com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (int i = seq.size() - 1; i >= 0; i--) {
            if (eqCall(eqFn, seq.get(i), element, callNode)) {
                return MezzeVariant.some((long) i);
            }
        }
        return MezzeVariant.none();
    }

    public static MezzeSeq replace(MezzeSeq seq, Object oldVal, Object newVal, MezzeFunction eqFn,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] result = seq.toArray();
        for (int i = 0; i < result.length; i++) {
            if (eqCall(eqFn, result[i], oldVal, callNode)) {
                result[i] = newVal;
                break;  // Only replace first occurrence
            }
        }
        return MezzeSeq.fromArray(result);
    }

    public static MezzeSeq replaceAll(MezzeSeq seq, Object oldVal, Object newVal, MezzeFunction eqFn,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] result = seq.toArray();
        for (int i = 0; i < result.length; i++) {
            if (eqCall(eqFn, result[i], oldVal, callNode)) {
                result[i] = newVal;
            }
        }
        return MezzeSeq.fromArray(result);
    }

    @TruffleBoundary
    public static MezzeSeq insertAt(MezzeSeq seq, long index, Object element) {
        int idx = (int) index;
        if (idx < 0) idx = 0;
        if (idx > seq.size()) idx = seq.size();
        Object[] arr = seq.toArray();
        Object[] result = new Object[arr.length + 1];
        System.arraycopy(arr, 0, result, 0, idx);
        result[idx] = element;
        System.arraycopy(arr, idx, result, idx + 1, arr.length - idx);
        return MezzeSeq.fromArray(result);
    }

    @TruffleBoundary
    public static MezzeVariant swap(MezzeSeq seq, long i, long j) {
        int idx1 = (int) i;
        int idx2 = (int) j;
        if (idx1 < 0 || idx1 >= seq.size() || idx2 < 0 || idx2 >= seq.size()) {
            return MezzeVariant.none();
        }
        Object[] result = seq.toArray();
        Object temp = result[idx1];
        result[idx1] = result[idx2];
        result[idx2] = temp;
        return MezzeVariant.some(MezzeSeq.fromArray(result));
    }

    public static boolean startsWith(MezzeSeq seq, MezzeSeq prefix, MezzeFunction eqFn,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (prefix.size() > seq.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!eqCall(eqFn, seq.get(i), prefix.get(i), callNode)) {
                return false;
            }
        }
        return true;
    }

    public static boolean endsWith(MezzeSeq seq, MezzeSeq suffix, MezzeFunction eqFn,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (suffix.size() > seq.size()) {
            return false;
        }
        int offset = seq.size() - suffix.size();
        for (int i = 0; i < suffix.size(); i++) {
            if (!eqCall(eqFn, seq.get(offset + i), suffix.get(i), callNode)) {
                return false;
            }
        }
        return true;
    }

    @TruffleBoundary
    public static String join(MezzeSeq seq, String separator) {
        if (seq.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seq.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            Object elem = seq.get(i);
            if (elem instanceof String s) {
                sb.append(s);
            } else {
                sb.append(String.valueOf(elem));
            }
        }
        return sb.toString();
    }


    private static Object[] prepareArgsIndex(MezzeFunction fn, long index) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        // Still use fromFields for {i: index} since it's less common
        args[closureValues.length] = MezzeRecord.fromFields(FIELD_I, new Object[]{index});
        return args;
    }

    private static Object[] prepareArgs(MezzeFunction fn, Object arg) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        // Use fast wrapper - no @TruffleBoundary
        args[closureValues.length] = MezzeRecord.wrapX(arg);
        return args;
    }

    /**
     * Shared 2-arg fold loop for fold, reduce, scan.
     *
     * Scalar-ABI bypass: when the folder is a bypassable BDSL function,
     * passes (closures..., acc, x) directly — no MezzeRecord allocation
     * per iteration. One-time parameter name check determines positional
     * permutation (the Mezze type system guarantees the names match but
     * the user can declare {x, acc} instead of {acc, x}).
     */
    private static Object foldTwoArg(MezzeSeq seq, Object init, MezzeFunction folder,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode,
                                     int startIndex, Object[] scanOut) {
        Object[] closureValues = folder.getClosureValues();
        int closureLen = closureValues.length;
        int size = seq.size();
        Object acc = init;
        // Scalar-ABI bypass
        CallTarget innerTarget = ScalarBypass.tryResolve(folder);
        if (innerTarget != null) {
            String[] params = folder.getParameterNames();
            boolean accFirst = params.length < 2 || "acc".equals(params[0]);
            int accSlot = accFirst ? closureLen : closureLen + 1;
            int elemSlot = accFirst ? closureLen + 1 : closureLen;
            Object[] args = new Object[closureLen + 2];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            for (int i = startIndex; i < size; i++) {
                args[accSlot] = acc;
                args[elemSlot] = seq.get(i);
                acc = callNode.call(innerTarget, args);
                if (scanOut != null) scanOut[i + 1] = acc;
            }
            return acc;
        }
        // Record path for non-BDSL callees
        CallTarget target = folder.getCallTarget();
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        for (int i = startIndex; i < size; i++) {
            args[closureLen] = MezzeRecord.wrapAccX(acc, seq.get(i));
            acc = callNode.call(target, args);
            if (scanOut != null) scanOut[i + 1] = acc;
        }
        return acc;
    }

    private static Object[] prepareArgsTwo(MezzeFunction fn, Object arg1, Object arg2) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        // Use fast wrapper - no @TruffleBoundary
        args[closureValues.length] = MezzeRecord.wrapAccX(arg1, arg2);
        return args;
    }

    private static Object[] prepareArgsComparator(MezzeFunction fn, Object arg1, Object arg2) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        // Use fast wrapper - no @TruffleBoundary
        args[closureValues.length] = MezzeRecord.wrapAB(arg1, arg2);
        return args;
    }

    /**
     * Element equality via the caller-supplied {@code eq} closure ({@code {a, b} ->
     * a == b}), which resolves the element type's Mezze {@code Eq} impl at the call
     * site. Used by contains/index_of/dedup/distinct/etc. instead of JVM
     * {@code Objects.equals}, so these functions AGREE with the {@code ==} operator
     * even when the element has a custom {@code Eq} (structural {@code .equals} would
     * silently diverge — e.g. a record whose {@code Eq} compares only some fields).
     * The {@code with Eq 'a} constraint on the stdlib signatures guarantees the
     * closure is always supplied for a type that actually has {@code Eq}.
     */
    private static boolean eqCall(MezzeFunction eqFn, Object a, Object b,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] args = prepareArgsComparator(eqFn, a, b);
        Object result = callNode.call(eqFn.getCallTarget(), args);
        return result instanceof Boolean && (Boolean) result;
    }

    // ==================== Advanced Zip Operations ====================

    public static MezzeSeq zipWith(MezzeSeq seq1, MezzeSeq seq2, MezzeFunction fn) {
        int minLen = Math.min(seq1.size(), seq2.size());
        Object[] result = new Object[minLen];
        for (int i = 0; i < minLen; i++) {
            Object[] args = prepareArgsComparator(fn, seq1.get(i), seq2.get(i));
            result[i] = fn.getCallTarget().call(args);
        }
        return MezzeSeq.fromArray(result);
    }

    @TruffleBoundary
    public static MezzeSeq zip3(MezzeSeq seq1, MezzeSeq seq2, MezzeSeq seq3) {
        int minLen = Math.min(seq1.size(), Math.min(seq2.size(), seq3.size()));
        Object[] result = new Object[minLen];
        for (int i = 0; i < minLen; i++) {
            result[i] = MezzeRecord.fromFields(
                FIELD_FST_SND_THD,
                new Object[]{seq1.get(i), seq2.get(i), seq3.get(i)}
            );
        }
        return MezzeSeq.fromArray(result);
    }

    @TruffleBoundary
    public static MezzeSeq zip4(MezzeSeq seq1, MezzeSeq seq2, MezzeSeq seq3, MezzeSeq seq4) {
        int minLen = Math.min(Math.min(seq1.size(), seq2.size()), Math.min(seq3.size(), seq4.size()));
        Object[] result = new Object[minLen];
        for (int i = 0; i < minLen; i++) {
            result[i] = MezzeRecord.fromFields(
                FIELD_FST_SND_THD_FTH,
                new Object[]{seq1.get(i), seq2.get(i), seq3.get(i), seq4.get(i)}
            );
        }
        return MezzeSeq.fromArray(result);
    }

    @TruffleBoundary
    public static MezzeSeq cartesianProduct(MezzeSeq seq1, MezzeSeq seq2) {
        // Pre-allocate exact size
        int size = seq1.size() * seq2.size();
        Object[] result = new Object[size];
        int idx = 0;
        for (int i = 0; i < seq1.size(); i++) {
            for (int j = 0; j < seq2.size(); j++) {
                result[idx++] = MezzeRecord.fromFields(
                    FIELD_FST_SND,
                    new Object[]{seq1.get(i), seq2.get(j)}
                );
            }
        }
        return MezzeSeq.fromArray(result);
    }

    // ==================== Combinatorics ====================

    @TruffleBoundary
    public static MezzeSeq permutations(MezzeSeq seq) {
        java.util.List<Object> result = new java.util.ArrayList<>();
        Object[] arr = seq.toArray();
        generatePermutations(arr, 0, result);
        return MezzeSeq.fromArray(result.toArray());
    }

    private static void generatePermutations(Object[] arr, int k, java.util.List<Object> result) {
        if (k == arr.length) {
            result.add(MezzeSeq.fromArray(arr.clone()));
            return;
        }
        for (int i = k; i < arr.length; i++) {
            Object temp = arr[k];
            arr[k] = arr[i];
            arr[i] = temp;
            generatePermutations(arr, k + 1, result);
            temp = arr[k];
            arr[k] = arr[i];
            arr[i] = temp;
        }
    }

    @TruffleBoundary
    public static MezzeSeq combinations(MezzeSeq seq, long k) {
        java.util.List<Object> result = new java.util.ArrayList<>();
        Object[] arr = seq.toArray();
        generateCombinations(arr, (int) k, 0, new Object[(int) k], 0, result);
        return MezzeSeq.fromArray(result.toArray());
    }

    private static void generateCombinations(Object[] arr, int k, int start, Object[] current, int idx, java.util.List<Object> result) {
        if (idx == k) {
            result.add(MezzeSeq.fromArray(current.clone()));
            return;
        }
        for (int i = start; i < arr.length && arr.length - i >= k - idx; i++) {
            current[idx] = arr[i];
            generateCombinations(arr, k, i + 1, current, idx + 1, result);
        }
    }

    @TruffleBoundary
    public static MezzeSeq subsequences(MezzeSeq seq) {
        java.util.List<Object> result = new java.util.ArrayList<>();
        Object[] arr = seq.toArray();
        int n = arr.length;
        // Generate all 2^n subsequences
        for (int mask = 0; mask < (1 << n); mask++) {
            java.util.List<Object> sub = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    sub.add(arr[i]);
                }
            }
            result.add(MezzeSeq.fromArray(sub.toArray()));
        }
        return MezzeSeq.fromArray(result.toArray());
    }

    // ==================== Statistical Operations ====================

    @TruffleBoundary
    public static MezzeVariant average(MezzeSeq seq) {
        if (seq.isEmpty()) {
            return MezzeVariant.none();
        }
        double sum = 0;
        for (int i = 0; i < seq.size(); i++) {
            Object elem = seq.get(i);
            if (elem instanceof Long l) {
                sum += l;
            } else if (elem instanceof Double d) {
                sum += d;
            }
        }
        return MezzeVariant.some(sum / seq.size());
    }

    @TruffleBoundary
    public static MezzeVariant variance(MezzeSeq seq) {
        if (seq.isEmpty()) {
            return MezzeVariant.none();
        }
        // Calculate mean
        double sum = 0;
        for (int i = 0; i < seq.size(); i++) {
            Object elem = seq.get(i);
            if (elem instanceof Long l) {
                sum += l;
            } else if (elem instanceof Double d) {
                sum += d;
            }
        }
        double mean = sum / seq.size();

        // Calculate variance
        double variance = 0;
        for (int i = 0; i < seq.size(); i++) {
            Object elem = seq.get(i);
            double val = elem instanceof Long l ? l : (Double) elem;
            variance += (val - mean) * (val - mean);
        }
        return MezzeVariant.some(variance / seq.size());
    }

    @TruffleBoundary
    public static MezzeVariant stddev(MezzeSeq seq) {
        MezzeVariant var = variance(seq);
        if (var.isNone()) {
            return var;
        }
        double v = (Double) var.getPayloadFieldByName("val");
        return MezzeVariant.some(Math.sqrt(v));
    }

    @TruffleBoundary
    public static MezzeVariant median(MezzeSeq seq) {
        if (seq.isEmpty()) {
            return MezzeVariant.none();
        }
        Object[] arr = seq.toArray();
        // `median` is declared over [Float]; ordering Doubles is not a decision
        // about any element's Ord instance, so compare them directly.
        java.util.Arrays.sort(arr, (a, b) -> Double.compare(
            a instanceof Long la ? la : (Double) a, b instanceof Long lb ? lb : (Double) b));
        int mid = arr.length / 2;
        if (arr.length % 2 == 0) {
            double v1 = arr[mid - 1] instanceof Long l ? l : (Double) arr[mid - 1];
            double v2 = arr[mid] instanceof Long l ? l : (Double) arr[mid];
            return MezzeVariant.some((v1 + v2) / 2);
        } else {
            return MezzeVariant.some(arr[mid]);
        }
    }

    // ==================== Sorting Checks ====================

    @TruffleBoundary
    // Threads the caller's element comparator so a custom Ord is honoured.
    public static boolean isSorted(MezzeSeq seq, MezzeFunction cmpFn,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        // `ScalarCall` hoists the args array out of the walk; `cmpInt`
        // allocated an `Object[]` and a `MezzeRecord` per PAIR. 16 086
        // ps/element before, against a 496 ps/element one-pass control.
        var cmp = ScalarCall.prepare2(cmpFn, "a", "b");
        for (int i = 1; i < seq.size(); i++) {
            if (cmp.callCmp(callNode, seq.get(i - 1), seq.get(i)) > 0) {
                return false;
            }
        }
        return true;
    }

    // Element comparison via the threaded cmp closure ({a, b} -> -1/0/1).
    private static int cmpInt(MezzeFunction cmpFn, Object a, Object b,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object r = callNode.call(cmpFn.getCallTarget(), prepareArgsComparator(cmpFn, a, b));
        return (r instanceof Long) ? ((Long) r).intValue() : 0;
    }

    // Keys are compared via the threaded comparator (the key type's Ord impl),
    // not the primitive-only compare() — see SeqKeyOrdTest.
    public static boolean isSortedBy(MezzeSeq seq, MezzeFunction keyFn, MezzeFunction cmpFn,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (seq.size() <= 1) return true;
        // FOUR allocations an element before this: `prepareArgs` for the key
        // and `cmpInt` for the comparison, each allocating an `Object[]` and a
        // `MezzeRecord`. At 34 109 ps/element it was the most expensive of the
        // five family members measured, and 2.12x `isSorted` — which is the
        // 4-against-2 allocation ratio showing up in the number.
        var keyCall = ScalarCall.prepare1(keyFn, "x");
        var cmp = ScalarCall.prepare2(cmpFn, "a", "b");
        Object prevKey = keyCall.call1(callNode, seq.get(0));
        for (int i = 1; i < seq.size(); i++) {
            Object currKey = keyCall.call1(callNode, seq.get(i));
            if (cmp.callCmp(callNode, prevKey, currKey) > 0) {
                return false;
            }
            prevKey = currKey;
        }
        return true;
    }

    public static boolean isSortedDescending(MezzeSeq seq, MezzeFunction cmpFn,
                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        // The twin of `isSorted`, and it gets the same treatment for the
        // same reason — it is unbenched, so no number is claimed for it, but
        // leaving one of an identical pair on the slow path is how the two
        // drift apart.
        var cmp = ScalarCall.prepare2(cmpFn, "a", "b");
        for (int i = 1; i < seq.size(); i++) {
            if (cmp.callCmp(callNode, seq.get(i - 1), seq.get(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    // ==================== Advanced Binary Search ====================

    // Threads the caller's element comparator so a custom Ord is honoured.
    public static long binarySearchInsertPoint(MezzeSeq seq, Object target, MezzeFunction cmpFn,
                                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int lo = 0, hi = seq.size();
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (cmpInt(cmpFn, seq.get(mid), target, callNode) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    public static long lowerBound(MezzeSeq seq, Object target, MezzeFunction cmpFn,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return binarySearchInsertPoint(seq, target, cmpFn, callNode);
    }

    public static long upperBound(MezzeSeq seq, Object target, MezzeFunction cmpFn,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int lo = 0, hi = seq.size();
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (cmpInt(cmpFn, seq.get(mid), target, callNode) <= 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    public static MezzeRecord equalRange(MezzeSeq seq, Object target, MezzeFunction cmpFn,
                                         com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return MezzeRecord.fromFields(
            FIELD_START_END,
            new Object[]{lowerBound(seq, target, cmpFn, callNode), upperBound(seq, target, cmpFn, callNode)}
        );
    }


    // ==================== Update Operations ====================

    public static MezzeVariant updateAt(MezzeSeq seq, long index, MezzeFunction fn) {
        if (index < 0 || index >= seq.size()) {
            return MezzeVariant.none();
        }
        Object oldVal = seq.get((int) index);
        Object newVal = fn.getCallTarget().call(prepareArgs(fn, oldVal));
        return MezzeVariant.some(seq.update((int) index, newVal));
    }

    @TruffleBoundary
    public static MezzeVariant removeAt(MezzeSeq seq, long index) {
        return remove(seq, index);
    }

    // ==================== Split Operations ====================

    @TruffleBoundary
    public static MezzeSeq split(MezzeSeq seq, Object separator, MezzeFunction eqFn, com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var eq = ScalarCall.prepare2(eqFn, "a", "b");
        java.util.List<Object> result = new java.util.ArrayList<>();
        java.util.List<Object> current = new java.util.ArrayList<>();

        for (int i = 0; i < seq.size(); i++) {
            Object elem = seq.get(i);
            if (eq.callEq(callNode, elem, separator)) {
                result.add(MezzeSeq.fromArray(current.toArray()));
                current.clear();
            } else {
                current.add(elem);
            }
        }
        result.add(MezzeSeq.fromArray(current.toArray()));
        return MezzeSeq.fromArray(result.toArray());
    }

    // ==================== Float-specific Operations ====================

    @TruffleBoundary
    public static MezzeVariant minFloat(MezzeSeq seq) {
        if (seq.isEmpty()) return MezzeVariant.none();
        double minVal = Double.MAX_VALUE;
        for (int i = 0; i < seq.size(); i++) {
            Object elem = seq.get(i);
            double val = elem instanceof Long l ? l : (Double) elem;
            if (val < minVal) minVal = val;
        }
        return MezzeVariant.some(minVal);
    }

    @TruffleBoundary
    public static MezzeVariant maxFloat(MezzeSeq seq) {
        if (seq.isEmpty()) return MezzeVariant.none();
        double maxVal = Double.MIN_VALUE;
        for (int i = 0; i < seq.size(); i++) {
            Object elem = seq.get(i);
            double val = elem instanceof Long l ? l : (Double) elem;
            if (val > maxVal) maxVal = val;
        }
        return MezzeVariant.some(maxVal);
    }

    // ==================== Cross-Collection Conversions ====================

    @TruffleBoundary
    public static MezzeList toList(MezzeSeq seq) {
        MezzeList result = MezzeList.empty();
        // Build in reverse to get correct order
        for (int i = seq.size() - 1; i >= 0; i--) {
            result = result.prepend(seq.get(i));
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSet toSet(MezzeSeq seq) {
        MezzeSet result = MezzeSet.empty();
        for (int i = 0; i < seq.size(); i++) {
            result = result.add(seq.get(i));
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeDeque toDeque(MezzeSeq seq) {
        return MezzeDeque.fromIterable(java.util.Arrays.asList(seq.toArray()));
    }

    /**
     * ORDERED BY THE ELEMENT'S `Ord`, which the caller supplies.
     *
     * <p>TWO BUGS LIVED HERE. It built through a bare `empty()`, so a
     * hand-written `Ord` was ignored and the result came out naturally ordered
     * — measured as `1, 3, 5` for a descending newtype, with `contains`
     * finding one element of three.
     *
     * <p>And it cast: `add((Comparable) seq.get(i))`. {@code add(A)} is
     * UNBOUNDED and delegates to {@code addObject}, so the cast bought nothing
     * and reintroduced at this call site the very checkcast
     * `MezzeSortedSet`'s class comment records removing — "ClassCastException
     * for any Mezze record or variant, so a sorted set of user values could not
     * even be iterated". A fix to the container's type parameter, undone by one
     * of its callers. Confirmed by running it, not read off the source.
     *
     * <p>The newtype-over-Int case survived that cast because a boxed Long IS
     * Comparable, which is why only record and variant elements crashed.
     */
    @TruffleBoundary
    public static MezzeSortedSet toSortedSet(MezzeSeq seq, MezzeFunction cmpFn) {
        MezzeSortedSet result = MezzeSortedSet.empty(ElementComparator.of(cmpFn));
        for (int i = 0; i < seq.size(); i++) {
            result = result.addObject(seq.get(i));
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeMap toMap(MezzeSeq seq) {
        MezzeMap result = MezzeMap.empty();
        for (int i = 0; i < seq.size(); i++) {
            Object elem = seq.get(i);
            if (elem instanceof MezzeRecord rec) {
                Object k = rec.getField("k");
                Object v = rec.getField("v");
                result = result.put(k, v);
            }
        }
        return result;
    }

    /** See {@link #toSortedSet}: the key's `Ord` comes from the caller. */
    @TruffleBoundary
    public static MezzeSortedMap toSortedMap(MezzeSeq seq, MezzeFunction cmpFn) {
        MezzeSortedMap result = MezzeSortedMap.empty(ElementComparator.of(cmpFn));
        for (int i = 0; i < seq.size(); i++) {
            Object elem = seq.get(i);
            if (elem instanceof MezzeRecord rec) {
                Object k = rec.getField("k");
                Object v = rec.getField("v");
                result = result.putObject(k, v);
            }
        }
        return result;
    }

    @TruffleBoundary
    public static MezzeSeq fromList(MezzeList list) {
        java.util.List<Object> result = new java.util.ArrayList<>();
        MezzeList current = list;
        while (!current.isEmpty()) {
            result.add(current.head());
            current = current.tail();
        }
        return MezzeSeq.fromArray(result.toArray());
    }

    @TruffleBoundary
    public static MezzeSeq fromSet(MezzeSet set) {
        return SetNatives.toSeq(set);
    }

    @TruffleBoundary
    public static MezzeSeq fromDeque(MezzeDeque deque) {
        return DequeNatives.toSeq(deque);
    }

    @TruffleBoundary
    public static MezzeSeq fromSortedSet(MezzeSortedSet set) {
        return SortedSetNatives.toSeq(set);
    }

    @TruffleBoundary
    public static MezzeSeq fromMap(MezzeMap map) {
        return MapNatives.toSeq(map);
    }

    @TruffleBoundary
    public static MezzeSeq fromSortedMap(MezzeSortedMap map) {
        return SortedMapNatives.toSeq(map);
    }

    // ==================== Additional Comparison Operations ====================

    /**
     * Lexicographic comparison returning -1, 0, or 1.
     */
    @TruffleBoundary
    // Lexicographic comparison threading the caller-supplied element comparator
    // `cmpFn` ({a, b} -> -1/0/1, resolving the element's Ord impl at the call site),
    // so `[T]` ordering respects a custom Ord instead of the primitive-only fallback.
    public static long compareSeqs(MezzeSeq seq1, MezzeSeq seq2, MezzeFunction cmpFn,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int minLen = Math.min(seq1.size(), seq2.size());
        for (int i = 0; i < minLen; i++) {
            Object r = callNode.call(cmpFn.getCallTarget(),
                prepareArgsComparator(cmpFn, seq1.get(i), seq2.get(i)));
            long c = (r instanceof Long) ? (Long) r : 0L;
            if (c != 0) {
                return c < 0 ? -1L : 1L;
            }
        }
        return Integer.compare(seq1.size(), seq2.size());
    }

    // ==================== Additional Permutation/Combination Operations ====================

    @TruffleBoundary
    public static MezzeSeq subsequencesNonEmpty(MezzeSeq seq) {
        java.util.List<Object> result = new java.util.ArrayList<>();
        int n = seq.size();
        for (int mask = 1; mask < (1 << n); mask++) {
            java.util.List<Object> sub = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    sub.add(seq.get(i));
                }
            }
            result.add(MezzeSeq.fromArray(sub.toArray()));
        }
        return MezzeSeq.fromArray(result.toArray());
    }

    @TruffleBoundary
    public static MezzeSeq contiguousSubsequences(MezzeSeq seq) {
        java.util.List<Object> result = new java.util.ArrayList<>();
        int n = seq.size();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j <= n; j++) {
                result.add(seq.slice(i, j));
            }
        }
        return MezzeSeq.fromArray(result.toArray());
    }
}
