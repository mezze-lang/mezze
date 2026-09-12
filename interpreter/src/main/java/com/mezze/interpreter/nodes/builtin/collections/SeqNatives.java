package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.deque.MezzeDeque;
import com.mezze.datastructures.map.MezzeMap;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.*;
import com.mezze.datastructures.set.MezzeSet;
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
 * Seq in V2 is represented as MezzeSeq (from datastructures module).
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
    private static final String[] FIELD_FST_SND = {"fst", "snd"};
    private static final String[] FIELD_VALUE_INDEX = {"value", "index"};
    private static final String[] FIELD_FSTS_SNDS = {"fsts", "snds"};
    private static final String[] FIELD_PASS_FAIL = {"pass", "fail"};
    private static final String[] FIELD_KEY_VALUES = {"key", "values"};
    private static final String[] FIELD_L_R = {"l", "r"};
    private static final String[] FIELD_START_END = {"start", "end"};
    private static final String[] FIELD_FST_SND_THD = {"fst", "snd", "thd"};
    private static final String[] FIELD_FST_SND_THD_FTH = {"fst", "snd", "thd", "fth"};

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

    public static MezzeVariant findIndex(MezzeSeq seq, MezzeFunction predicate,
                                         com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] closureValues = predicate.getClosureValues();
        int closureLen = closureValues.length;
        Object[] args;
        CallTarget innerTarget = ScalarBypass.tryResolve(predicate);
        if (innerTarget != null) {
            args = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            for (int i = 0; i < seq.size(); i++) {
                args[closureLen] = seq.get(i);
                Object result = callNode.call(innerTarget, args);
                if (result instanceof Boolean && (Boolean) result) {
                    return MezzeVariant.some((long) i);
                }
            }
            return MezzeVariant.none();
        }
        com.oracle.truffle.api.CallTarget target = predicate.getCallTarget();
        args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        for (int i = 0; i < seq.size(); i++) {
            args[closureLen] = MezzeRecord.wrapX(seq.get(i));
            Object result = callNode.call(target, args);
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some((long) i);
            }
        }
        return MezzeVariant.none();
    }

    public static MezzeVariant find(MezzeSeq seq, MezzeFunction predicate,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] closureValues = predicate.getClosureValues();
        int closureLen = closureValues.length;
        CallTarget innerTarget = ScalarBypass.tryResolve(predicate);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            for (int i = 0; i < seq.size(); i++) {
                Object element = seq.get(i);
                args[closureLen] = element;
                Object result = callNode.call(innerTarget, args);
                if (result instanceof Boolean && (Boolean) result) {
                    return MezzeVariant.some(element);
                }
            }
            return MezzeVariant.none();
        }
        com.oracle.truffle.api.CallTarget target = predicate.getCallTarget();
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        for (int i = 0; i < seq.size(); i++) {
            Object element = seq.get(i);
            args[closureLen] = MezzeRecord.wrapX(element);
            Object result = callNode.call(target, args);
            if (result instanceof Boolean && (Boolean) result) {
                return MezzeVariant.some(element);
            }
        }
        return MezzeVariant.none();
    }

    // ==================== Transform Operations ====================

    // Simple delegation - no @TruffleBoundary needed for JIT inlining
    public static MezzeSeq reverse(MezzeSeq seq) {
        return seq.reverse();
    }

    public static MezzeSeq map(MezzeSeq seq, MezzeFunction mapper,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] closureValues = mapper.getClosureValues();
        int closureLen = closureValues.length;
        int size = seq.size();
        Object[] result = new Object[size];
        CallTarget innerTarget = ScalarBypass.tryResolve(mapper);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            for (int i = 0; i < size; i++) {
                args[closureLen] = seq.get(i);
                result[i] = callNode.call(innerTarget, args);
            }
            return MezzeSeq.fromArray(result);
        }
        com.oracle.truffle.api.CallTarget target = mapper.getCallTarget();
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        for (int i = 0; i < size; i++) {
            args[closureLen] = MezzeRecord.wrapX(seq.get(i));
            result[i] = callNode.call(target, args);
        }
        return MezzeSeq.fromArray(result);
    }

    public static MezzeSeq filter(MezzeSeq seq, MezzeFunction predicate,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int size = seq.size();
        if (size == 0) return MezzeSeq.empty();

        Object[] closureValues = predicate.getClosureValues();
        int closureLen = closureValues.length;

        // Single-pass: collect matching elements into a growable buffer.
        // Eliminates the boolean[size] + second seq.get() per kept element
        // that the old two-pass approach needed.
        Object[] buf = new Object[Math.min(size, 16)]; // start small, grow as needed
        int count = 0;

        CallTarget innerTarget = ScalarBypass.tryResolve(predicate);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            for (int i = 0; i < size; i++) {
                Object elem = seq.get(i);
                args[closureLen] = elem;
                Object result = callNode.call(innerTarget, args);
                if (result instanceof Boolean && (Boolean) result) {
                    if (count == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                    buf[count++] = elem;
                }
            }
        } else {
            com.oracle.truffle.api.CallTarget target = predicate.getCallTarget();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            for (int i = 0; i < size; i++) {
                Object elem = seq.get(i);
                args[closureLen] = MezzeRecord.wrapX(elem);
                Object result = callNode.call(target, args);
                if (result instanceof Boolean && (Boolean) result) {
                    if (count == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2);
                    buf[count++] = elem;
                }
            }
        }

        if (count == 0) return MezzeSeq.empty();
        if (count == size) return seq; // All kept — return original

        Object[] results = (count == buf.length) ? buf : java.util.Arrays.copyOf(buf, count);
        return MezzeSeq.fromArray(results);
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
    public static MezzeVariant tryMap(MezzeSeq seq, MezzeFunction mapper,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] closureValues = mapper.getClosureValues();
        int closureLen = closureValues.length;
        com.oracle.truffle.api.CallTarget target = mapper.getCallTarget();
        int size = seq.size();
        Object[] result = new Object[size];
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        for (int i = 0; i < size; i++) {
            args[closureLen] = MezzeRecord.wrapX(seq.get(i));
            Object elemResult = callNode.call(target, args);
            if (elemResult instanceof MezzeVariant v) {
                if (v.isErr()) {
                    return v; // short-circuit
                }
                if (v.isOk()) {
                    Object payload = v.getPayloadValue();
                    if (payload instanceof MezzeRecord r) {
                        try {
                            result[i] = r.getFieldByName("val");
                        } catch (Exception e) {
                            result[i] = payload;
                        }
                        continue;
                    }
                    result[i] = payload;
                    continue;
                }
            }
            // Non-Result callback return — treat as raw element.
            result[i] = elemResult;
        }
        return MezzeVariant.ok(MezzeSeq.fromArray(result));
    }

    public static MezzeSeq flatMap(MezzeSeq seq, MezzeFunction mapper,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        java.util.ArrayList<Object> results = new java.util.ArrayList<>();
        for (int i = 0; i < seq.size(); i++) {
            Object mapped = callNode.call(mapper.getCallTarget(),
                prepareArgs(mapper, seq.get(i)));
            if (mapped instanceof MezzeSeq innerSeq) {
                for (int j = 0; j < innerSeq.size(); j++) {
                    results.add(innerSeq.get(j));
                }
            }
        }
        return MezzeSeq.fromArray(results.toArray());
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
            return SeqReduceLongSIMDNode.getUncached().execute(opOrdinal, init, longStorage);
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
            return SeqReduceDoubleSIMDNode.getUncached().execute(opOrdinal, init, doubleStorage);
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

    public static MezzeVariant reduce(MezzeSeq seq, MezzeFunction reducer,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (seq.isEmpty()) {
            return MezzeVariant.none();
        }
        Object acc = foldTwoArg(seq, seq.get(0), reducer, callNode,
                                /*startIndex=*/1, /*scanOut=*/null);
        return MezzeVariant.some(acc);
    }

    // ==================== Predicate Operations ====================

    public static boolean any(MezzeSeq seq, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] closureValues = predicate.getClosureValues();
        int closureLen = closureValues.length;
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        CallTarget innerTarget = ScalarBypass.tryResolve(predicate);
        if (innerTarget != null) {
            for (int i = 0; i < seq.size(); i++) {
                args[closureLen] = seq.get(i);
                Object result = callNode.call(innerTarget, args);
                if (result instanceof Boolean && (Boolean) result) {
                    return true;
                }
            }
            return false;
        }
        com.oracle.truffle.api.CallTarget target = predicate.getCallTarget();
        for (int i = 0; i < seq.size(); i++) {
            args[closureLen] = MezzeRecord.wrapX(seq.get(i));
            Object result = callNode.call(target, args);
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        }
        return false;
    }

    public static boolean all(MezzeSeq seq, MezzeFunction predicate,
                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] closureValues = predicate.getClosureValues();
        int closureLen = closureValues.length;
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        CallTarget innerTarget = ScalarBypass.tryResolve(predicate);
        if (innerTarget != null) {
            for (int i = 0; i < seq.size(); i++) {
                args[closureLen] = seq.get(i);
                Object result = callNode.call(innerTarget, args);
                if (!(result instanceof Boolean) || !(Boolean) result) {
                    return false;
                }
            }
            return true;
        }
        com.oracle.truffle.api.CallTarget target = predicate.getCallTarget();
        for (int i = 0; i < seq.size(); i++) {
            args[closureLen] = MezzeRecord.wrapX(seq.get(i));
            Object result = callNode.call(target, args);
            if (!(result instanceof Boolean) || !(Boolean) result) {
                return false;
            }
        }
        return true;
    }

    public static boolean none(MezzeSeq seq, MezzeFunction predicate,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return !any(seq, predicate, callNode);
    }

    public static long count(MezzeSeq seq, MezzeFunction predicate,
                             com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] closureValues = predicate.getClosureValues();
        int closureLen = closureValues.length;
        Object[] args = new Object[closureLen + 1];
        System.arraycopy(closureValues, 0, args, 0, closureLen);
        long count = 0;
        CallTarget innerTarget = ScalarBypass.tryResolve(predicate);
        if (innerTarget != null) {
            for (int i = 0; i < seq.size(); i++) {
                args[closureLen] = seq.get(i);
                Object result = callNode.call(innerTarget, args);
                if (result instanceof Boolean && (Boolean) result) {
                    count++;
                }
            }
            return count;
        }
        com.oracle.truffle.api.CallTarget target = predicate.getCallTarget();
        for (int i = 0; i < seq.size(); i++) {
            args[closureLen] = MezzeRecord.wrapX(seq.get(i));
            Object result = callNode.call(target, args);
            if (result instanceof Boolean && (Boolean) result) {
                count++;
            }
        }
        return count;
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

    public static MezzeRecord partition(MezzeSeq seq, MezzeFunction predicate,
                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int size = seq.size();
        if (size == 0) {
            return MezzeRecord.fromFields(
                FIELD_PASS_FAIL,
                new Object[]{MezzeSeq.empty(), MezzeSeq.empty()}
            );
        }

        // Two-pass: first determine matches, then collect.
        Object[] closureValues = predicate.getClosureValues();
        int closureLen = closureValues.length;
        boolean[] matches = new boolean[size];
        int matchCount = 0;
        CallTarget innerTarget = ScalarBypass.tryResolve(predicate);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            for (int i = 0; i < size; i++) {
                args[closureLen] = seq.get(i);
                Object result = callNode.call(innerTarget, args);
                if (result instanceof Boolean && (Boolean) result) {
                    matches[i] = true;
                    matchCount++;
                }
            }
        } else {
            com.oracle.truffle.api.CallTarget target = predicate.getCallTarget();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            for (int i = 0; i < size; i++) {
                args[closureLen] = MezzeRecord.wrapX(seq.get(i));
                Object result = callNode.call(target, args);
                if (result instanceof Boolean && (Boolean) result) {
                    matches[i] = true;
                    matchCount++;
                }
            }
        }

        Object[] matchingArr = new Object[matchCount];
        Object[] nonMatchingArr = new Object[size - matchCount];
        int mIdx = 0, nIdx = 0;
        for (int i = 0; i < size; i++) {
            if (matches[i]) {
                matchingArr[mIdx++] = seq.get(i);
            } else {
                nonMatchingArr[nIdx++] = seq.get(i);
            }
        }

        return MezzeRecord.fromFields(
            FIELD_PASS_FAIL,
            new Object[]{
                MezzeSeq.fromArray(matchingArr),
                MezzeSeq.fromArray(nonMatchingArr)
            }
        );
    }

    // ==================== Sort Operations ====================

    @TruffleBoundary
    public static MezzeSeq sort(MezzeSeq seq) {
        Object[] arr = seq.toArray();
        Arrays.sort(arr, (a, b) -> {
            if (a instanceof Long && b instanceof Long) {
                return Long.compare((Long) a, (Long) b);
            }
            if (a instanceof Double && b instanceof Double) {
                return Double.compare((Double) a, (Double) b);
            }
            if (a instanceof String && b instanceof String) {
                return ((String) a).compareTo((String) b);
            }
            return 0;
        });
        return MezzeSeq.fromArray(arr);
    }

    public static MezzeSeq sortBy(MezzeSeq seq, MezzeFunction cmpFn,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] arr = seq.toArray();
        Arrays.sort(arr, (elemA, elemB) -> {
            Object cmpResult = callNode.call(cmpFn.getCallTarget(),
                prepareArgsComparator(cmpFn, elemA, elemB));
            if (cmpResult instanceof Long) {
                return ((Long) cmpResult).intValue();
            }
            return 0;
        });
        return MezzeSeq.fromArray(arr);
    }

    // ==================== Unique/Flatten Operations ====================

    public static MezzeSeq unique(MezzeSeq seq, MezzeFunction eqFn,
                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        return distinct(seq, eqFn, callNode);
    }

    // Keeps the first occurrence of each element; O(n^2) in the worst case because
    // Eq is not required to be hashable (only equatable). The primitive/structural
    // fast path (seq.distinct()) can't be used: it would ignore a custom Eq impl.
    public static MezzeSeq distinct(MezzeSeq seq, MezzeFunction eqFn,
                                    com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (seq.isEmpty()) return seq;
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        for (int i = 0; i < seq.size(); i++) {
            Object elem = seq.get(i);
            boolean seen = false;
            for (int j = 0; j < result.size(); j++) {
                if (eqCall(eqFn, result.get(j), elem, callNode)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) result.add(elem);
        }
        return MezzeSeq.fromArray(result.toArray());
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

    public static MezzeSeq takeWhile(MezzeSeq seq, MezzeFunction predicate,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        java.util.ArrayList<Object> results = new java.util.ArrayList<>();
        for (int i = 0; i < seq.size(); i++) {
            Object element = seq.get(i);
            Object result = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, element));
            if (result instanceof Boolean && (Boolean) result) {
                results.add(element);
            } else {
                break;
            }
        }
        return MezzeSeq.fromArray(results.toArray());
    }

    public static MezzeSeq dropWhile(MezzeSeq seq, MezzeFunction predicate,
                                     com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        int dropCount = 0;
        for (int i = 0; i < seq.size(); i++) {
            Object result = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, seq.get(i)));
            if (result instanceof Boolean && (Boolean) result) {
                dropCount++;
            } else {
                break;
            }
        }
        return seq.drop(dropCount);
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

    @TruffleBoundary
    public static boolean equals(MezzeSeq seq1, MezzeSeq seq2) {
        return seq1.equals(seq2);
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

    // ==================== Min/Max/Sum/Product ====================

    @TruffleBoundary
    public static MezzeVariant min(MezzeSeq seq) {
        if (seq.isEmpty()) return MezzeVariant.none();
        // OPTIMIZED: Use specialized nodes for unboxed storage
        SeqStorage storage = seq.getStorage();
        if (storage instanceof LongSeqStorage longStorage) {
            return MezzeVariant.some(SeqMinLongNode.getUncached().execute(longStorage));
        }
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            return MezzeVariant.some(SeqMinDoubleNode.getUncached().execute(doubleStorage));
        }
        // Fallback for boxed storage
        Object minVal = seq.get(0);
        for (int i = 1; i < seq.size(); i++) {
            Object curr = seq.get(i);
            if (compare(curr, minVal) < 0) {
                minVal = curr;
            }
        }
        return MezzeVariant.some(minVal);
    }

    @TruffleBoundary
    public static MezzeVariant max(MezzeSeq seq) {
        if (seq.isEmpty()) return MezzeVariant.none();
        // OPTIMIZED: Use specialized nodes for unboxed storage
        SeqStorage storage = seq.getStorage();
        if (storage instanceof LongSeqStorage longStorage) {
            return MezzeVariant.some(SeqMaxLongNode.getUncached().execute(longStorage));
        }
        if (storage instanceof DoubleSeqStorage doubleStorage) {
            return MezzeVariant.some(SeqMaxDoubleNode.getUncached().execute(doubleStorage));
        }
        // Fallback for boxed storage
        Object maxVal = seq.get(0);
        for (int i = 1; i < seq.size(); i++) {
            Object curr = seq.get(i);
            if (compare(curr, maxVal) > 0) {
                maxVal = curr;
            }
        }
        return MezzeVariant.some(maxVal);
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

    public static MezzeSeq groupBy(MezzeSeq seq, MezzeFunction keyFn,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        java.util.LinkedHashMap<Object, java.util.ArrayList<Object>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < seq.size(); i++) {
            Object elem = seq.get(i);
            Object key = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, elem));
            groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(elem);
        }
        Object[] result = new Object[groups.size()];
        int idx = 0;
        for (var entry : groups.entrySet()) {
            result[idx++] = MezzeRecord.fromFields(
                FIELD_KEY_VALUES,
                new Object[]{entry.getKey(), MezzeSeq.fromArray(entry.getValue().toArray())}
            );
        }
        return MezzeSeq.fromArray(result);
    }

    // ==================== Enumerate/ForEach/Scan ====================

    @TruffleBoundary
    public static MezzeSeq enumerate(MezzeSeq seq) {
        return zipWithIndex(seq);  // Same as zip_with_index
    }

    public static MezzeRecord forEach(MezzeSeq seq, MezzeFunction fn,
                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        CallTarget innerTarget = ScalarBypass.tryResolve(fn);
        if (innerTarget != null) {
            Object[] closureValues = fn.getClosureValues();
            int closureLen = closureValues.length;
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closureValues, 0, args, 0, closureLen);
            for (int i = 0; i < seq.size(); i++) {
                args[closureLen] = seq.get(i);
                callNode.call(innerTarget, args);
            }
            return MezzeRecord.empty();
        }
        for (int i = 0; i < seq.size(); i++) {
            callNode.call(fn.getCallTarget(), prepareArgs(fn, seq.get(i)));
        }
        return MezzeRecord.empty();  // Returns unit
    }

    public static MezzeSeq scan(MezzeSeq seq, Object init, MezzeFunction fn,
                                com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] result = new Object[seq.size() + 1];
        result[0] = init;
        foldTwoArg(seq, init, fn, callNode, /*startIndex=*/0, result);
        return MezzeSeq.fromArray(result);
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

    public static MezzeSeq dedupBy(MezzeSeq seq, MezzeFunction keyFn,
                                   com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (seq.isEmpty()) return seq;
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        Object lastElem = seq.get(0);
        Object lastKey = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, lastElem));
        result.add(lastElem);
        for (int i = 1; i < seq.size(); i++) {
            Object elem = seq.get(i);
            Object key = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, elem));
            if (!java.util.Objects.equals(key, lastKey)) {
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
    public static MezzeSeq groupConsecutive(MezzeSeq seq) {
        if (seq.isEmpty()) return MezzeSeq.empty();
        java.util.ArrayList<Object> groups = new java.util.ArrayList<>();
        java.util.ArrayList<Object> currentGroup = new java.util.ArrayList<>();
        Object currentKey = seq.get(0);
        currentGroup.add(currentKey);

        for (int i = 1; i < seq.size(); i++) {
            Object elem = seq.get(i);
            if (java.util.Objects.equals(elem, currentKey)) {
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

    public static MezzeSeq groupConsecutiveBy(MezzeSeq seq, MezzeFunction keyFn,
                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (seq.isEmpty()) return MezzeSeq.empty();
        java.util.ArrayList<Object> groups = new java.util.ArrayList<>();
        java.util.ArrayList<Object> currentGroup = new java.util.ArrayList<>();
        Object firstElem = seq.get(0);
        Object currentKey = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, firstElem));
        currentGroup.add(firstElem);

        for (int i = 1; i < seq.size(); i++) {
            Object elem = seq.get(i);
            Object key = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, elem));
            if (java.util.Objects.equals(key, currentKey)) {
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

    // ==================== Binary Search ====================

    @TruffleBoundary
    public static MezzeVariant binarySearch(MezzeSeq seq, Object target) {
        int lo = 0, hi = seq.size() - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            int cmp = compare(seq.get(mid), target);
            if (cmp == 0) return MezzeVariant.some((long) mid);
            if (cmp < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return MezzeVariant.none();
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
        // Pre-compute keys to avoid calling keyFn multiple times per element
        int size = seq.size();
        Object[] elements = new Object[size];
        Object[] keys = new Object[size];
        for (int i = 0; i < size; i++) {
            elements[i] = seq.get(i);
            keys[i] = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, elements[i]));
        }

        // Sort indices by key, comparing keys via the threaded comparator
        // (the key type's Ord impl), not the primitive-only compare().
        Integer[] indices = new Integer[size];
        for (int i = 0; i < size; i++) indices[i] = i;
        Arrays.sort(indices, (a, b) -> cmpInt(cmpFn, keys[a], keys[b], callNode));

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

    // ==================== Helper Methods ====================

    private static int compare(Object a, Object b) {
        if (a instanceof Long && b instanceof Long) {
            return Long.compare((Long) a, (Long) b);
        }
        if (a instanceof Double && b instanceof Double) {
            return Double.compare((Double) a, (Double) b);
        }
        if (a instanceof String && b instanceof String) {
            return ((String) a).compareTo((String) b);
        }
        if (a instanceof Long && b instanceof Double) {
            return Double.compare(((Long) a).doubleValue(), (Double) b);
        }
        if (a instanceof Double && b instanceof Long) {
            return Double.compare((Double) a, ((Long) b).doubleValue());
        }
        return 0;
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
        Object result = callNode.call(eqFn.getCallTarget(), prepareArgsComparator(eqFn, a, b));
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
        java.util.Arrays.sort(arr, (a, b) -> compare(a, b));
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
        for (int i = 1; i < seq.size(); i++) {
            if (cmpInt(cmpFn, seq.get(i - 1), seq.get(i), callNode) > 0) {
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
        Object prevKey = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, seq.get(0)));
        for (int i = 1; i < seq.size(); i++) {
            Object currKey = callNode.call(keyFn.getCallTarget(), prepareArgs(keyFn, seq.get(i)));
            if (cmpInt(cmpFn, prevKey, currKey, callNode) > 0) {
                return false;
            }
            prevKey = currKey;
        }
        return true;
    }

    public static boolean isSortedDescending(MezzeSeq seq, MezzeFunction cmpFn,
                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        for (int i = 1; i < seq.size(); i++) {
            if (cmpInt(cmpFn, seq.get(i - 1), seq.get(i), callNode) < 0) {
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

    // ==================== Counting ====================

    @TruffleBoundary
    public static long countElement(MezzeSeq seq, Object target) {
        long count = 0;
        for (int i = 0; i < seq.size(); i++) {
            if (java.util.Objects.equals(seq.get(i), target)) {
                count++;
            }
        }
        return count;
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
    public static MezzeSeq split(MezzeSeq seq, Object separator) {
        java.util.List<Object> result = new java.util.ArrayList<>();
        java.util.List<Object> current = new java.util.ArrayList<>();

        for (int i = 0; i < seq.size(); i++) {
            Object elem = seq.get(i);
            if (java.util.Objects.equals(elem, separator)) {
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

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public static MezzeSortedSet toSortedSet(MezzeSeq seq) {
        MezzeSortedSet result = MezzeSortedSet.empty();
        for (int i = 0; i < seq.size(); i++) {
            result = result.add((Comparable) seq.get(i));
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

    @TruffleBoundary
    public static MezzeSortedMap toSortedMap(MezzeSeq seq) {
        MezzeSortedMap result = MezzeSortedMap.empty();
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
