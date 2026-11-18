package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.interpreter.nodes.continuation.NativeSuspension;
import com.mezze.interpreter.nodes.continuation.SuspendFacts;
import com.mezze.datastructures.iter.*;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * Native ops for the lazy {@code Iter} data structure. Kept in the same
 * shape as {@link SeqNatives}: static methods with hoisted closure/target
 * refs, no boundary on construction paths (they're just descriptor
 * allocations), boundary on the fold sink (has to call user code).
 *
 * <p>The BDSL fold sink ({@code IterFoldLongLoopNode}) handles the hot
 * path with PE-time descriptor-tree specialization; this class holds the
 * slow-path fallback and the constructors that don't need specialization.
 */
public final class IterNatives {
    /** `{ fst, snd }`, hoisted: this was allocated per call. Names from
     *  {@link com.mezze.language.truffleir.NativeHofNames} so a rename reaches every spelling. */
    private static final String[] FIELD_FST_SND =
        {com.mezze.language.truffleir.NativeHofNames.PAIR_FST, com.mezze.language.truffleir.NativeHofNames.PAIR_SND};


    private IterNatives() {}

    // Step variant tags — computed once, same as TagRegistry.getOrCreateTag
    // THE definition, not a restatement of it — see BuiltinTypes.computeVariantTag.
    static final int YIELD_TAG =
        com.mezze.language.ast.BuiltinTypes.INSTANCE.computeVariantTag("Yield", null);
    static final int SKIP_TAG =
        com.mezze.language.ast.BuiltinTypes.INSTANCE.computeVariantTag("Skip", null);
    private static final int DONE_TAG =
        com.mezze.language.ast.BuiltinTypes.INSTANCE.computeVariantTag("Done", null);

    // Register tags in global registry so pattern matching can find them
    static {
        MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("Yield");
        MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("Skip");
        MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("Done");
    }

    // ===== CONSTRUCTION =====

    public static MezzeIter range(long start, long end) {
        return MezzeIter.range(start, end);
    }

    public static MezzeIter rangeStep(long start, long end, long step) {
        return MezzeIter.rangeStep(start, end, step);
    }

    public static MezzeIter map(MezzeIter it, MezzeFunction f) {
        // fn stored as opaque Object — the fold walker will cast back to
        // MezzeFunction at each application site. Mirrors how SeqNatives
        // holds MezzeFunction refs inline.
        return it.map(f);
    }

    public static MezzeIter filter(MezzeIter it, MezzeFunction p) {
        return it.filter(p);
    }

    public static MezzeIter fromSeq(com.mezze.datastructures.seq.MezzeSeq<?> seq) {
        return MezzeIter.fromSeq(seq);
    }

    public static MezzeIter take(MezzeIter it, long n) {
        return it.take(n);
    }

    public static MezzeIter drop(MezzeIter it, long n) {
        return it.drop(n);
    }

    public static MezzeIter takeWhile(MezzeIter it, MezzeFunction p) {
        return it.takeWhile(p);
    }

    public static MezzeIter dropWhile(MezzeIter it, MezzeFunction p) {
        return it.dropWhile(p);
    }

    public static MezzeIter fromList(MezzeList list) {
        return MezzeIter.fromList(list);
    }

    public static MezzeIter unfold(Object initialState, MezzeFunction stepFn) {
        return MezzeIter.unfold(initialState, stepFn);
    }

    public static MezzeIter once(Object value) {
        return MezzeIter.once(value);
    }

    public static MezzeIter empty() {
        return MezzeIter.empty();
    }

    public static MezzeIter enumerate(MezzeIter it) {
        return it.enumerate();
    }

    public static MezzeIter chain(MezzeIter a, MezzeIter b) {
        return a.chain(b);
    }

    public static MezzeIter zip(MezzeIter a, MezzeIter b) {
        return a.zip(b);
    }

    public static MezzeIter flatMap(MezzeIter it, MezzeFunction f) {
        return it.flatMap(f);
    }

    // ===== SINKS =====

    /**
     * Generic descriptor-tree walker. Boundaried because it calls out to
     * user closures via {@link IndirectCallNode#call}; PE traces stop
     * here in the fallback path. The BDSL sink specializes the common
     * shapes into a straight-line inlined loop.
     */
    @TruffleBoundary
    public static Object fold(MezzeIter it, Object init, MezzeFunction folder,
                              IndirectCallNode callNode) {
        if (resumable(it, folder)) return pull(IterCursor.of(it, callNode), new FoldSink(init, folder, callNode));
        return foldWalk(it.getDescriptor(), init, folder, callNode);
    }

    @TruffleBoundary
    public static long sum(MezzeIter it) {
        long[] acc = {0L};
        walkElements(it.getDescriptor(), x -> { acc[0] += (Long) x; return true; },
                     IndirectCallNode.getUncached());
        return acc[0];
    }

    // ==================== Cursors (pull, resumable) ====================

    /**
     * A resumable pull view over `it`. See {@link IterCursor} for why this is
     * a second mechanism rather than a replacement for the push walk.
     *
     * The cursor is MUTABLE POSITION, so it is a handle like a FileHandle
     * rather than a value: pulling from it twice gives two different answers.
     * That is also why it is not thread-safe — a guest callback needs the
     * handler chain of the thread that created it.
     */
    @TruffleBoundary
    public static Object cursor(MezzeIter it, IndirectCallNode callNode) {
        return IterCursor.of(it, callNode);
    }

    /** The next element as `Some`, or `None` once the source is exhausted. */
    @TruffleBoundary
    public static Object cursorNext(Object cursor) {
        if (!(cursor instanceof IterCursor c)) {
            throw new com.mezze.interpreter.runtime.NativeTypeMismatchException(
                "Expected an Iter cursor");
        }
        Object x = c.next();
        return x == IterCursor.END ? MezzeVariant.none() : MezzeVariant.some(x);
    }

    @TruffleBoundary
    public static Object forEach(MezzeIter it, MezzeFunction f, IndirectCallNode callNode) {
        if (resumable(it, f)) return pull(IterCursor.of(it, callNode), new ForEachSink(f, callNode));
        return forEachWalk(it, f, callNode);
    }

    public static Object forEachWalk(MezzeIter it, MezzeFunction f,
                                 IndirectCallNode callNode) {
        Object[] closure = f.getClosureValues();
        int closureLen = closure.length;
        CallTarget innerTarget = ScalarBypass.tryResolve(f);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                args[closureLen] = x;
                callNode.call(innerTarget, args);
                return true;
            }, callNode);
        } else {
            CallTarget target = f.getCallTarget();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                args[closureLen] = MezzeRecord.wrapX(x);
                callNode.call(target, args);
                return true;
            }, callNode);
        }
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static Object count(MezzeIter it, MezzeFunction p, IndirectCallNode callNode) {
        if (resumable(it, p)) return pull(IterCursor.of(it, callNode), new CountSink(p, callNode));
        return countWalk(it, p, callNode);
    }

    public static long countWalk(MezzeIter it, MezzeFunction p,
                             IndirectCallNode callNode) {
        Object[] closure = p.getClosureValues();
        int closureLen = closure.length;
        long[] acc = {0L};
        CallTarget innerTarget = ScalarBypass.tryResolve(p);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                args[closureLen] = x;
                Object r = callNode.call(innerTarget, args);
                if (r instanceof Boolean && (Boolean) r) acc[0]++;
                return true;
            }, callNode);
        } else {
            CallTarget target = p.getCallTarget();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                args[closureLen] = MezzeRecord.wrapX(x);
                Object r = callNode.call(target, args);
                if (r instanceof Boolean && (Boolean) r) acc[0]++;
                return true;
            }, callNode);
        }
        return acc[0];
    }

    @TruffleBoundary
    public static Object any(MezzeIter it, MezzeFunction p, IndirectCallNode callNode) {
        if (resumable(it, p)) return pull(IterCursor.of(it, callNode), new SearchSink(p, callNode, SEARCH_ANY));
        return anyWalk(it, p, callNode);
    }

    public static boolean anyWalk(MezzeIter it, MezzeFunction p,
                              IndirectCallNode callNode) {
        Object[] closure = p.getClosureValues();
        int closureLen = closure.length;
        boolean[] found = {false};
        CallTarget innerTarget = ScalarBypass.tryResolve(p);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                args[closureLen] = x;
                Object r = callNode.call(innerTarget, args);
                if (r instanceof Boolean && (Boolean) r) {
                    found[0] = true;
                    return false;
                }
                return true;
            }, callNode);
        } else {
            CallTarget target = p.getCallTarget();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                args[closureLen] = MezzeRecord.wrapX(x);
                Object r = callNode.call(target, args);
                if (r instanceof Boolean && (Boolean) r) {
                    found[0] = true;
                    return false;
                }
                return true;
            }, callNode);
        }
        return found[0];
    }

    @TruffleBoundary
    public static Object all(MezzeIter it, MezzeFunction p, IndirectCallNode callNode) {
        if (resumable(it, p)) return pull(IterCursor.of(it, callNode), new SearchSink(p, callNode, SEARCH_ALL));
        return allWalk(it, p, callNode);
    }

    public static boolean allWalk(MezzeIter it, MezzeFunction p,
                              IndirectCallNode callNode) {
        Object[] closure = p.getClosureValues();
        int closureLen = closure.length;
        boolean[] allTrue = {true};
        CallTarget innerTarget = ScalarBypass.tryResolve(p);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                args[closureLen] = x;
                Object r = callNode.call(innerTarget, args);
                if (!(r instanceof Boolean) || !((Boolean) r)) {
                    allTrue[0] = false;
                    return false;
                }
                return true;
            }, callNode);
        } else {
            CallTarget target = p.getCallTarget();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                args[closureLen] = MezzeRecord.wrapX(x);
                Object r = callNode.call(target, args);
                if (!(r instanceof Boolean) || !((Boolean) r)) {
                    allTrue[0] = false;
                    return false;
                }
                return true;
            }, callNode);
        }
        return allTrue[0];
    }

    @TruffleBoundary
    public static Object find(MezzeIter it, MezzeFunction p, IndirectCallNode callNode) {
        if (resumable(it, p)) return pull(IterCursor.of(it, callNode), new SearchSink(p, callNode, SEARCH_FIND));
        return findWalk(it, p, callNode);
    }

    public static Object findWalk(MezzeIter it, MezzeFunction p,
                              IndirectCallNode callNode) {
        Object[] closure = p.getClosureValues();
        int closureLen = closure.length;
        Object[] result = {null};
        CallTarget innerTarget = ScalarBypass.tryResolve(p);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                args[closureLen] = x;
                Object r = callNode.call(innerTarget, args);
                if (r instanceof Boolean && (Boolean) r) {
                    result[0] = com.mezze.interpreter.values.MezzeVariant.some(x);
                    return false;
                }
                return true;
            }, callNode);
        } else {
            CallTarget target = p.getCallTarget();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                args[closureLen] = MezzeRecord.wrapX(x);
                Object r = callNode.call(target, args);
                if (r instanceof Boolean && (Boolean) r) {
                    result[0] = com.mezze.interpreter.values.MezzeVariant.some(x);
                    return false;
                }
                return true;
            }, callNode);
        }
        return result[0] != null ? result[0] : com.mezze.interpreter.values.MezzeVariant.none();
    }

    @TruffleBoundary
    public static Object reduce(MezzeIter it, MezzeFunction f, IndirectCallNode callNode) {
        if (resumable(it, f)) return pull(IterCursor.of(it, callNode), new ReduceSink(f, callNode));
        return reduceWalk(it, f, callNode);
    }

    public static Object reduceWalk(MezzeIter it, MezzeFunction f,
                                IndirectCallNode callNode) {
        Object[] closure = f.getClosureValues();
        int closureLen = closure.length;
        Object[] acc = {null};
        boolean[] first = {true};
        CallTarget innerTarget = ScalarBypass.tryResolve(f);
        if (innerTarget != null) {
            String[] params = f.getParameterNames();
            boolean accFirst = params.length < 2 || "acc".equals(params[0]);
            int accSlot = accFirst ? closureLen : closureLen + 1;
            int elemSlot = accFirst ? closureLen + 1 : closureLen;
            Object[] args = new Object[closureLen + 2];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                if (first[0]) {
                    acc[0] = x;
                    first[0] = false;
                } else {
                    args[accSlot] = acc[0];
                    args[elemSlot] = x;
                    acc[0] = callNode.call(innerTarget, args);
                }
                return true;
            }, callNode);
        } else {
            CallTarget target = f.getCallTarget();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                if (first[0]) {
                    acc[0] = x;
                    first[0] = false;
                } else {
                    args[closureLen] = MezzeRecord.wrapAccX(acc[0], x);
                    acc[0] = callNode.call(target, args);
                }
                return true;
            }, callNode);
        }
        return first[0] ? com.mezze.interpreter.values.MezzeVariant.none() : com.mezze.interpreter.values.MezzeVariant.some(acc[0]);
    }

    /**
     * Collect all elements from an iter descriptor into an Object[].
     * Used by the BDSL IterFoldDynamic/IterForEachDynamic ops:
     * complex descriptors (map/filter chains) are materialized behind
     * this @TruffleBoundary, then the fold/forEach loop runs outside
     * the boundary so the JIT can inline the user callback.
     */
    @TruffleBoundary
    public static Object[] collectToArray(MezzeIter it) {
        java.util.ArrayList<Object> buf = new java.util.ArrayList<>();
        walkElements(it.getDescriptor(), buf::add, IndirectCallNode.getUncached());
        return buf.toArray();
    }

    @TruffleBoundary
    public static Object toSeq(MezzeIter it, IndirectCallNode callNode) {
        if (resumable(it)) return pull(IterCursor.of(it, callNode), new CollectSink(false));
        return toSeqWalk(it, callNode);
    }

    public static Object toSeqWalk(MezzeIter it, IndirectCallNode callNode) {
        java.util.ArrayList<Object> buf = new java.util.ArrayList<>();
        walkElements(it.getDescriptor(), buf::add, callNode);
        return com.mezze.datastructures.seq.MezzeSeq.fromArray(buf.toArray());
    }

    @TruffleBoundary
    public static long countAll(MezzeIter it, IndirectCallNode callNode) {
        long[] acc = {0L};
        walkElements(it.getDescriptor(), x -> { acc[0]++; return true; }, callNode);
        return acc[0];
    }

    @TruffleBoundary
    public static Object first(MezzeIter it, IndirectCallNode callNode) {
        if (resumable(it)) return pull(IterCursor.of(it, callNode), new EdgeSink(true));
        return firstWalk(it, callNode);
    }

    public static Object firstWalk(MezzeIter it, IndirectCallNode callNode) {
        Object[] result = {null};
        walkElements(it.getDescriptor(), x -> {
            result[0] = MezzeVariant.some(x);
            return false;
        }, callNode);
        return result[0] != null ? result[0] : MezzeVariant.none();
    }

    @TruffleBoundary
    public static Object last(MezzeIter it, IndirectCallNode callNode) {
        if (resumable(it)) return pull(IterCursor.of(it, callNode), new EdgeSink(false));
        return lastWalk(it, callNode);
    }

    public static Object lastWalk(MezzeIter it, IndirectCallNode callNode) {
        Object[] result = {null};
        walkElements(it.getDescriptor(), x -> { result[0] = x; return true; }, callNode);
        return result[0] != null ? MezzeVariant.some(result[0]) : MezzeVariant.none();
    }

    @TruffleBoundary
    public static Object toList(MezzeIter it, IndirectCallNode callNode) {
        if (resumable(it)) return pull(IterCursor.of(it, callNode), new CollectSink(true));
        return toListWalk(it, callNode);
    }

    public static Object toListWalk(MezzeIter it, IndirectCallNode callNode) {
        java.util.ArrayList<Object> buf = new java.util.ArrayList<>();
        walkElements(it.getDescriptor(), buf::add, callNode);
        com.mezze.datastructures.list.MezzeList result = com.mezze.datastructures.list.MezzeNil.INSTANCE;
        for (int i = buf.size() - 1; i >= 0; i--) {
            result = result.prepend(buf.get(i));
        }
        return result;
    }

    // ===== ELEMENT WALKER =====

    /**
     * A sink for walked elements.
     *
     * `accept` answers whether the walk should CONTINUE. It used to return
     * void, which meant `take`, `find`, `any`, `all` and `take_while` could
     * only stop processing: the source kept being walked to the end. On a
     * finite source that is a silent O(n) where O(k) was asked for — `find`
     * on a 10M range scanned 10M elements after matching the first. On an
     * endless source it never returned at all, which is the case `unfold` is
     * advertised for (trees, file handles, database cursors, paginated APIs).
     *
     * Returning false must stop the walk promptly, not eventually: a
     * consumer that has its answer may be sitting in front of an infinite
     * producer.
     */

    // ------------------------------------------------------------------ C4: resumable path

    /** Any callback in the chain's descriptor may suspend (read once per call). */
    public static boolean chainMaySuspend(IterNode n) {
        if (n instanceof UnaryOpIterNode u) return fnMay(u.fn) || chainMaySuspend(u.src);
        if (n instanceof TakeWhileIterNode t) return fnMay(t.predicate) || chainMaySuspend(t.src);
        if (n instanceof DropWhileIterNode d) return fnMay(d.predicate) || chainMaySuspend(d.src);
        if (n instanceof FlatMapIterNode f) return fnMay(f.fn) || chainMaySuspend(f.src);
        if (n instanceof UnfoldIterNode u) return fnMay(u.stepFunction);
        if (n instanceof TakeIterNode t) return chainMaySuspend(t.src);
        if (n instanceof DropIterNode d) return chainMaySuspend(d.src);
        if (n instanceof EnumerateIterNode e) return chainMaySuspend(e.src);
        if (n instanceof ChainIterNode c) return chainMaySuspend(c.first) || chainMaySuspend(c.second);
        if (n instanceof ZipIterNode z) return chainMaySuspend(z.first) || chainMaySuspend(z.second);
        return false;
    }

    private static boolean fnMay(Object fn) {
        return fn instanceof MezzeFunction f && SuspendFacts.mayFunctionSuspend(f);
    }

    /** Take the resumable (cursor) path: the consumer's callback or the chain may
     *  suspend. Pure callbacks never come here — measured ~15% slower than the push
     *  walk on an effectful `range.map.fold`, and that is the price of suspendability
     *  paid only by code that can park. */
    public static boolean resumable(MezzeIter it, MezzeFunction fn) {
        return NativeSuspension.ENABLED && (SuspendFacts.mayFunctionSuspend(fn) || chainMaySuspend(it.getDescriptor()));
    }

    public static boolean resumable(MezzeIter it) {
        return NativeSuspension.ENABLED && chainMaySuspend(it.getDescriptor());
    }

    /** A consumer over a cursor, with heap state. {@code element} returns CONTINUE,
     *  STOP, or a suspension (its callback parked); {@code resumeElement} completes
     *  the step with the callback's value and answers CONTINUE/STOP. */
    interface Sink {
        Object element(Object x);
        Object resumeElement(Object v);
        Object finish();
    }
    static final Object CONTINUE = new Object(), STOP = new Object();

    /** Drive a sink over a cursor; a suspension from either side becomes a
     *  NativeSuspension whose resume re-enters here. */
    static Object pull(IterCursor c, Sink sink) {
        return drive(c, sink, c.next());
    }

    private static Object drive(IterCursor c, Sink sink, Object x) {
        while (true) {
            if (IterCursor.susp(x)) {
                return new NativeSuspension(x, v -> drive(c, sink, c.resume(v)));
            }
            if (x == IterCursor.END) return sink.finish();
            Object r = sink.element(x);
            if (IterCursor.susp(r)) {
                return new NativeSuspension(r, v -> {
                    Object d = sink.resumeElement(v);
                    return d == STOP ? sink.finish() : drive(c, sink, c.next());
                });
            }
            if (r == STOP) return sink.finish();
            x = c.next();
        }
    }

    /** A two-argument call (folder/reducer) honouring the scalar ABI's slot order. */
    private static final class Call2 {
        private final CallTarget target; private final Object[] args; private final int accSlot, elemSlot; private final boolean scalar;
        private final IndirectCallNode callNode;
        Call2(MezzeFunction fn, IndirectCallNode callNode) {
            this.callNode = callNode;
            Object[] closure = fn.getClosureValues(); int len = closure.length;
            CallTarget inner = ScalarBypass.tryResolve(fn);
            scalar = inner != null;
            target = scalar ? inner : fn.getCallTarget();
            if (scalar) {
                String[] params = fn.getParameterNames();
                boolean accFirst = params.length < 2 || "acc".equals(params[0]);
                accSlot = accFirst ? len : len + 1; elemSlot = accFirst ? len + 1 : len;
                args = new Object[len + 2];
            } else { accSlot = len; elemSlot = -1; args = new Object[len + 1]; }
            System.arraycopy(closure, 0, args, 0, len);
        }
        Object on(Object acc, Object x) {
            if (scalar) { args[accSlot] = acc; args[elemSlot] = x; }
            else args[accSlot] = MezzeRecord.wrapAccX(acc, x);
            return callNode.call(target, args);
        }
    }

    private static final class FoldSink implements Sink {
        private Object acc; private final Call2 f;
        FoldSink(Object init, MezzeFunction folder, IndirectCallNode cn) { acc = init; f = new Call2(folder, cn); }
        public Object element(Object x) { Object r = f.on(acc, x); if (IterCursor.susp(r)) return r; acc = r; return CONTINUE; }
        public Object resumeElement(Object v) { acc = v; return CONTINUE; }
        public Object finish() { return acc; }
    }
    private static final class ReduceSink implements Sink {
        private Object acc; private boolean first = true; private final Call2 f;
        ReduceSink(MezzeFunction fn, IndirectCallNode cn) { f = new Call2(fn, cn); }
        public Object element(Object x) {
            if (first) { first = false; acc = x; return CONTINUE; }
            Object r = f.on(acc, x); if (IterCursor.susp(r)) return r; acc = r; return CONTINUE;
        }
        public Object resumeElement(Object v) { acc = v; return CONTINUE; }
        public Object finish() { return first ? MezzeVariant.none() : MezzeVariant.some(acc); }
    }
    private static final class ForEachSink implements Sink {
        private final IterCursor.Call f;
        ForEachSink(MezzeFunction fn, IndirectCallNode cn) { f = new IterCursor.Call(fn, cn); }
        public Object element(Object x) { Object r = f.on(x); return IterCursor.susp(r) ? r : CONTINUE; }
        public Object resumeElement(Object v) { return CONTINUE; }
        public Object finish() { return MezzeRecord.empty(); }
    }
    private static final class CountSink implements Sink {
        private long n; private final IterCursor.Call p;
        CountSink(MezzeFunction fn, IndirectCallNode cn) { p = new IterCursor.Call(fn, cn); }
        public Object element(Object x) { Object r = p.on(x); if (IterCursor.susp(r)) return r; if (r instanceof Boolean b && b) n++; return CONTINUE; }
        public Object resumeElement(Object v) { if (v instanceof Boolean b && b) n++; return CONTINUE; }
        public Object finish() { return n; }
    }
    private static final int SEARCH_ANY = 0, SEARCH_ALL = 1, SEARCH_FIND = 2;
    private static final class SearchSink implements Sink {
        private final IterCursor.Call p; private final int mode; private Object pendingX; private Object answer;
        SearchSink(MezzeFunction fn, IndirectCallNode cn, int mode) { p = new IterCursor.Call(fn, cn); this.mode = mode; }
        public Object element(Object x) { pendingX = x; Object r = p.on(x); return IterCursor.susp(r) ? r : decide(r); }
        public Object resumeElement(Object v) { return decide(v); }
        private Object decide(Object r) {
            boolean yes = r instanceof Boolean b && b;
            switch (mode) {
                case SEARCH_ANY: if (yes) { answer = Boolean.TRUE; return STOP; } return CONTINUE;
                case SEARCH_ALL: if (!yes) { answer = Boolean.FALSE; return STOP; } return CONTINUE;
                default: if (yes) { answer = MezzeVariant.some(pendingX); return STOP; } return CONTINUE;
            }
        }
        public Object finish() {
            if (answer != null) return answer;
            switch (mode) { case SEARCH_ANY: return Boolean.FALSE; case SEARCH_ALL: return Boolean.TRUE; default: return MezzeVariant.none(); }
        }
    }
    private static final class CollectSink implements Sink {
        private final java.util.ArrayList<Object> buf = new java.util.ArrayList<>(); private final boolean list;
        CollectSink(boolean list) { this.list = list; }
        public Object element(Object x) { buf.add(x); return CONTINUE; }
        public Object resumeElement(Object v) { return CONTINUE; }
        public Object finish() {
            if (!list) return com.mezze.datastructures.seq.MezzeSeq.fromArray(buf.toArray());
            com.mezze.datastructures.list.MezzeList result = com.mezze.datastructures.list.MezzeNil.INSTANCE;
            for (int i = buf.size() - 1; i >= 0; i--) result = result.prepend(buf.get(i));
            return result;
        }
    }
    private static final class EdgeSink implements Sink {
        private final boolean first; private Object seen; private boolean any;
        EdgeSink(boolean first) { this.first = first; }
        public Object element(Object x) { seen = x; any = true; return first ? STOP : CONTINUE; }
        public Object resumeElement(Object v) { return CONTINUE; }
        public Object finish() { return any ? MezzeVariant.some(seen) : MezzeVariant.none(); }
    }

    @FunctionalInterface
    private interface ElementConsumer {
        /** @return true to keep walking, false to stop now. */
        boolean accept(Object element);
    }

    /** @return false if a consumer stopped the walk early. */
    @TruffleBoundary
    private static boolean walkElements(IterNode node, ElementConsumer consumer,
                                        IndirectCallNode callNode) {
        if (node instanceof RangeIterNode range) {
            long i = range.start;
            long end = range.end;
            long step = range.step;
            if (step == 0) return true;
            boolean asc = step > 0;
            while (asc ? (i < end) : (i > end)) {
                if (!consumer.accept((Object) i)) return false;
                i += step;
            }
            return true;
        }
        if (node instanceof SeqIterNode seqNode) {
            for (int idx = 0; idx < seqNode.size(); idx++) {
                if (!consumer.accept(seqNode.get(idx))) return false;
            }
            return true;
        }
        if (node instanceof ListIterNode listNode) {
            Object[] arr = listNode.list.toArray();
            for (Object x : arr) {
                if (!consumer.accept(x)) return false;
            }
            return true;
        }
        if (node instanceof OnceIterNode once) {
            return consumer.accept(once.value);
        }
        if (node instanceof EmptyIterNode) {
            return true;
        }
        if (node instanceof TakeIterNode take) {
            long[] remaining = {take.n};
            if (remaining[0] <= 0) return true;
            walkElements(take.src, x -> {
                // Stop the SOURCE once the quota is met, rather than walking
                // on and discarding. This is the whole point: `take` in front
                // of an endless producer has to be the thing that ends it.
                if (!consumer.accept(x)) return false;
                remaining[0]--;
                return remaining[0] > 0;
            }, callNode);
            return true;
        }
        if (node instanceof DropIterNode drop) {
            long[] toSkip = {drop.n};
            return walkElements(drop.src, x -> {
                if (toSkip[0] > 0) {
                    toSkip[0]--;
                    return true;
                }
                return consumer.accept(x);
            }, callNode);
        }
        if (node instanceof TakeWhileIterNode tw) {
            boolean[] done = {false};
            MezzeFunction pred = (MezzeFunction) tw.predicate;
            CallTarget predInner = ScalarBypass.tryResolve(pred);
            Object[] pClosure = pred.getClosureValues();
            int pClosureLen = pClosure.length;
            Object[] pArgs = new Object[pClosureLen + 1];
            System.arraycopy(pClosure, 0, pArgs, 0, pClosureLen);
            walkElements(tw.src, x -> {
                if (done[0]) return false;
                if (predInner != null) {
                    pArgs[pClosureLen] = x;
                    Object r = callNode.call(predInner, pArgs);
                    if (r instanceof Boolean && (Boolean) r) return consumer.accept(x);
                    done[0] = true;
                    return false;
                } else {
                    pArgs[pClosureLen] = MezzeRecord.wrapX(x);
                    Object r = callNode.call(pred.getCallTarget(), pArgs);
                    if (r instanceof Boolean && (Boolean) r) return consumer.accept(x);
                    done[0] = true;
                    return false;
                }
            }, callNode);
            return true;
        }
        if (node instanceof DropWhileIterNode dw) {
            boolean[] dropping = {true};
            MezzeFunction pred = (MezzeFunction) dw.predicate;
            CallTarget predInner = ScalarBypass.tryResolve(pred);
            Object[] pClosure = pred.getClosureValues();
            int pClosureLen = pClosure.length;
            Object[] pArgs = new Object[pClosureLen + 1];
            System.arraycopy(pClosure, 0, pArgs, 0, pClosureLen);
            return walkElements(dw.src, x -> {
                if (dropping[0]) {
                    if (predInner != null) {
                        pArgs[pClosureLen] = x;
                        Object r = callNode.call(predInner, pArgs);
                        if (!(r instanceof Boolean && (Boolean) r)) {
                            dropping[0] = false;
                            return consumer.accept(x);
                        }
                    } else {
                        pArgs[pClosureLen] = MezzeRecord.wrapX(x);
                        Object r = callNode.call(pred.getCallTarget(), pArgs);
                        if (!(r instanceof Boolean && (Boolean) r)) {
                            dropping[0] = false;
                            return consumer.accept(x);
                        }
                    }
                    return true;
                }
                return consumer.accept(x);
            }, callNode);
        }
        if (node instanceof EnumerateIterNode en) {
            long[] idx = {0L};
            return walkElements(en.src, x -> {
                boolean go = consumer.accept(MezzeRecord.fromFields(
                    new String[]{"i", "val"}, new Object[]{idx[0], x}));
                idx[0]++;
                return go;
            }, callNode);
        }
        if (node instanceof ChainIterNode chain) {
            // A stop in the first half must not start the second.
            if (!walkElements(chain.first, consumer, callNode)) return false;
            return walkElements(chain.second, consumer, callNode);
        }
        if (node instanceof ZipIterNode zip) {
            // A CURSOR per side, not two materialised lists.
            //
            // This used to walk both sides into ArrayLists and index them,
            // which meant zipping anything endless exhausted memory — and
            // `zip { a = finite, b = endless }` is an ordinary thing to write.
            // Interleaving two pushes is what a push driver cannot do, so each
            // side gets a resumable pull view instead and this holds one pair
            // at a time. Stops at the shorter side, as before.
            IterCursor left = IterCursor.of(zip.first, callNode);
            IterCursor right = IterCursor.of(zip.second, callNode);
            while (true) {
                Object a = left.next();
                if (a == IterCursor.END) return true;
                Object b = right.next();
                if (b == IterCursor.END) return true;
                if (!consumer.accept(MezzeRecord.fromFields(
                        FIELD_FST_SND, new Object[]{a, b}))) {
                    return false;
                }
            }
        }
        if (node instanceof FlatMapIterNode fm) {
            MezzeFunction fn = (MezzeFunction) fm.fn;
            Object[] fmClosure = fn.getClosureValues();
            int fmClosureLen = fmClosure.length;
            CallTarget fmInner = ScalarBypass.tryResolve(fn);
            Object[] fmArgs = new Object[fmClosureLen + 1];
            System.arraycopy(fmClosure, 0, fmArgs, 0, fmClosureLen);
            return walkElements(fm.src, x -> {
                MezzeIter inner;
                if (fmInner != null) {
                    fmArgs[fmClosureLen] = x;
                    inner = (MezzeIter) callNode.call(fmInner, fmArgs);
                } else {
                    fmArgs[fmClosureLen] = MezzeRecord.wrapX(x);
                    inner = (MezzeIter) callNode.call(fn.getCallTarget(), fmArgs);
                }
                // A stop inside the INNER iterator stops the outer walk too,
                // or `take` behind a flat_map would keep pulling outer
                // elements and discarding whole inner sequences.
                return walkElements(inner.getDescriptor(), consumer, callNode);
            }, callNode);
        }
        if (node instanceof UnfoldIterNode unfold) {
            return walkUnfold(unfold, consumer, callNode);
        }
        if (node instanceof UnaryOpIterNode unary) {
            MezzeFunction fn = (MezzeFunction) unary.fn;
            Object[] closure = fn.getClosureValues();
            int closureLen = closure.length;
            CallTarget innerTarget = ScalarBypass.tryResolve(fn);
            if (innerTarget != null) {
                Object[] args = new Object[closureLen + 1];
                System.arraycopy(closure, 0, args, 0, closureLen);
                if (unary.isMap()) {
                    return walkElements(unary.src, x -> {
                        args[closureLen] = x;
                        return consumer.accept(callNode.call(innerTarget, args));
                    }, callNode);
                } else {
                    return walkElements(unary.src, x -> {
                        args[closureLen] = x;
                        Object r = callNode.call(innerTarget, args);
                        if (r instanceof Boolean && (Boolean) r) return consumer.accept(x);
                        return true;
                    }, callNode);
                }
            } else {
                com.oracle.truffle.api.CallTarget target = fn.getCallTarget();
                Object[] args = new Object[closureLen + 1];
                System.arraycopy(closure, 0, args, 0, closureLen);
                if (unary.isMap()) {
                    return walkElements(unary.src, x -> {
                        args[closureLen] = MezzeRecord.wrapX(x);
                        return consumer.accept(callNode.call(target, args));
                    }, callNode);
                } else {
                    return walkElements(unary.src, x -> {
                        args[closureLen] = MezzeRecord.wrapX(x);
                        Object r = callNode.call(target, args);
                        if (r instanceof Boolean && (Boolean) r) return consumer.accept(x);
                        return true;
                    }, callNode);
                }
            }
        }
        return true;
    }

    /**
     * Unfold driver loop. Calls the step function repeatedly, matching
     * on the Step variant tag (Yield/Skip/Done).
     *
     * The step function has Mezze signature:
     *   { s: 'state } -> Step 'elem 'state
     * and returns a MezzeVariant:
     *   Yield { val: 'elem, next: 'state } — emit val, continue with next
     *   Skip  { next: 'state }             — advance state, no emission
     *   Done                               — stop
     */
    @TruffleBoundary
    private static boolean walkUnfold(UnfoldIterNode unfold, ElementConsumer consumer,
                                      IndirectCallNode callNode) {
        MezzeFunction stepFn = (MezzeFunction) unfold.stepFunction;
        Object[] closure = stepFn.getClosureValues();
        int closureLen = closure.length;
        CallTarget stepInner = ScalarBypass.tryResolve(stepFn);

        Object state = unfold.initialState;

        if (stepInner != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            while (true) {
                args[closureLen] = state;
                MezzeVariant step = (MezzeVariant) callNode.call(stepInner, args);
                int tag = step.getTag();
                if (tag == YIELD_TAG) {
                    Object payload = step.getRawPayloadValue();
                    if (payload instanceof MezzeRecord rec) {
                        if (!consumer.accept(rec.getField("val"))) return false;
                        state = rec.getField("next");
                    } else {
                        // Fused single-field — shouldn't happen for Yield (2 fields)
                        consumer.accept(payload);
                        break;
                    }
                } else if (tag == SKIP_TAG) {
                    state = step.getPayloadFieldByName("next");
                } else {
                    // Done
                    break;
                }
            }
        } else {
            CallTarget target = stepFn.getCallTarget();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            while (true) {
                args[closureLen] = MezzeRecord.wrapS(state);
                MezzeVariant step = (MezzeVariant) callNode.call(target, args);
                int tag = step.getTag();
                if (tag == YIELD_TAG) {
                    Object payload = step.getRawPayloadValue();
                    if (payload instanceof MezzeRecord rec) {
                        if (!consumer.accept(rec.getField("val"))) return false;
                        state = rec.getField("next");
                    } else {
                        consumer.accept(payload);
                        break;
                    }
                } else if (tag == SKIP_TAG) {
                    state = step.getPayloadFieldByName("next");
                } else {
                    break;
                }
            }
        }
        return true;
    }

    /** Recursive walker. Each unary transform wraps the surrounding folder. */
    @TruffleBoundary
    private static Object foldWalk(IterNode node, Object acc, MezzeFunction folder,
                                   IndirectCallNode callNode) {
        if (node instanceof RangeIterNode range) {
            return foldRange(range, acc, folder, callNode,
                    /*mapper*/ null, /*filter*/ null, /*filterAfterMap*/ false);
        }
        if (node instanceof SeqIterNode || node instanceof TakeIterNode || node instanceof DropIterNode
                || node instanceof ListIterNode || node instanceof OnceIterNode || node instanceof EmptyIterNode
                || node instanceof TakeWhileIterNode || node instanceof DropWhileIterNode
                || node instanceof EnumerateIterNode || node instanceof ChainIterNode
                || node instanceof ZipIterNode || node instanceof FlatMapIterNode
                || node instanceof UnfoldIterNode) {
            return foldViaRecursion(node, acc, folder, callNode);
        }
        if (node instanceof UnaryOpIterNode unary) {
            // Peel one level of transforms above a Range in a single sweep
            // — matches how the BDSL sink will pattern-match nested
            // Filter(Map(Range)) chains. This keeps the fallback fast for
            // simple pipelines without needing every combination hand-
            // coded.
            MezzeFunction mapper = null;
            MezzeFunction pred = null;
            // WHICH STAGE IS OUTERMOST, i.e. which the user wrote LAST.
            // `xs.map(f).filter(p)` builds Filter(Map(range)), so this inward
            // walk meets the PREDICATE first; `xs.filter(p).map(f)` builds
            // Map(Filter(range)) and meets the MAPPER first. Without recording
            // that, both chains peeled to the same (mapper, pred) pair and
            // foldRange applied a fixed order — so `map(*10).filter(<40)` over
            // 0..4 tested the predicate against the RAW element, kept
            // everything, and summed 100 where the answer is 60. Silently.
            boolean filterAfterMap = false;
            IterNode cursor = unary;
            while (cursor instanceof UnaryOpIterNode u) {
                if (u.isMap()) {
                    if (mapper != null) break; // multi-map: bail to full recursion
                    mapper = (MezzeFunction) u.fn;
                } else {
                    if (pred != null) break; // multi-filter: bail
                    pred = (MezzeFunction) u.fn;
                    // Predicate seen before any mapper on the way in: the
                    // filter sits ABOVE the map, so it must see mapped values.
                    if (mapper == null) filterAfterMap = true;
                }
                cursor = u.src;
            }
            if (cursor instanceof RangeIterNode range && !(cursor instanceof UnaryOpIterNode)) {
                return foldRange(range, acc, folder, callNode, mapper, pred, filterAfterMap);
            }
            // Deeper stacks fall back to a per-element recursion — slow
            // but correct.
            return foldViaRecursion(node, acc, folder, callNode);
        }
        throw new IllegalStateException("Unknown IterNode: " + node.getClass());
    }

    /**
     * Range walker with optional map + optional filter peeled above it.
     *
     * STAGE ORDER IS CARRIED, NOT ASSUMED. [filterAfterMap] says the filter
     * sits above the map in the chain, so the predicate must run on the MAPPED
     * element. This used to be fixed at "filter first (raw element), then map",
     * which is right for `filter(p).map(f)` and silently wrong for
     * `map(f).filter(p)` — the common order — because the predicate then tested
     * a value the user never wrote a predicate for.
     *
     * Keeping both stages in one loop still avoids the per-stage intermediate
     * structures that make eager collections slow.
     */
    @TruffleBoundary
    private static Object foldRange(RangeIterNode range, Object initAcc,
                                    MezzeFunction folder, IndirectCallNode callNode,
                                    MezzeFunction mapper, MezzeFunction pred,
                                    boolean filterAfterMap) {
        Object acc = initAcc;
        long i = range.start;
        long end = range.end;
        long step = range.step;
        if (step == 0) return acc;

        // Hoist closure + target refs outside the loop — see SeqNatives
        // .fold's doc for why this matters. One allocation per element
        // is unavoidable at the boundary; two would be gratuitous.
        Object[] folderClosure = folder.getClosureValues();
        int folderClosureLen = folderClosure.length;
        com.oracle.truffle.api.CallTarget folderTarget = folder.getCallTarget();

        Object[] mapperClosure = null;
        int mapperClosureLen = 0;
        com.oracle.truffle.api.CallTarget mapperTarget = null;
        if (mapper != null) {
            mapperClosure = mapper.getClosureValues();
            mapperClosureLen = mapperClosure.length;
            mapperTarget = mapper.getCallTarget();
        }

        Object[] predClosure = null;
        int predClosureLen = 0;
        com.oracle.truffle.api.CallTarget predTarget = null;
        if (pred != null) {
            predClosure = pred.getClosureValues();
            predClosureLen = predClosure.length;
            predTarget = pred.getCallTarget();
        }

        // Scalar-ABI bypass: resolve inner targets once
        CallTarget predInner = pred != null ? ScalarBypass.tryResolve(pred) : null;
        CallTarget mapperInner = mapper != null ? ScalarBypass.tryResolve(mapper) : null;
        CallTarget folderInner = ScalarBypass.tryResolve(folder);

        // Hoist args arrays outside the loop — callbacks destructure
        // immediately and never retain the array, so reuse is safe.
        Object[] pArgs = null;
        if (pred != null) {
            CallTarget pt = predInner != null ? predInner : predTarget;
            pArgs = new Object[predClosureLen + 1];
            System.arraycopy(predClosure, 0, pArgs, 0, predClosureLen);
        }
        Object[] mArgs = null;
        if (mapper != null) {
            mArgs = new Object[mapperClosureLen + 1];
            System.arraycopy(mapperClosure, 0, mArgs, 0, mapperClosureLen);
        }

        // Folder args: scalar bypass uses 2 separate arg slots, record path uses 1
        Object[] fArgs;
        int fAccSlot, fElemSlot;
        boolean folderScalar = folderInner != null;
        if (folderScalar) {
            String[] fParams = folder.getParameterNames();
            boolean accFirst = fParams.length < 2 || "acc".equals(fParams[0]);
            fAccSlot = accFirst ? folderClosureLen : folderClosureLen + 1;
            fElemSlot = accFirst ? folderClosureLen + 1 : folderClosureLen;
            fArgs = new Object[folderClosureLen + 2];
        } else {
            fAccSlot = -1;
            fElemSlot = -1;
            fArgs = new Object[folderClosureLen + 1];
        }
        System.arraycopy(folderClosure, 0, fArgs, 0, folderClosureLen);

        boolean ascending = step > 0;
        while (ascending ? (i < end) : (i > end)) {
            Object x = (Object) i;

            // Filter BELOW the map: the predicate sees the raw element.
            if (pred != null && !filterAfterMap) {
                if (predInner != null) {
                    pArgs[predClosureLen] = x;
                    Object r = callNode.call(predInner, pArgs);
                    if (!(r instanceof Boolean) || !((Boolean) r)) { i += step; continue; }
                } else {
                    pArgs[predClosureLen] = MezzeRecord.wrapX(x);
                    Object r = callNode.call(predTarget, pArgs);
                    if (!(r instanceof Boolean) || !((Boolean) r)) { i += step; continue; }
                }
            }
            if (mapper != null) {
                if (mapperInner != null) {
                    mArgs[mapperClosureLen] = x;
                    x = callNode.call(mapperInner, mArgs);
                } else {
                    mArgs[mapperClosureLen] = MezzeRecord.wrapX(x);
                    x = callNode.call(mapperTarget, mArgs);
                }
            }
            // Filter ABOVE the map: the predicate sees the mapped element.
            if (pred != null && filterAfterMap) {
                if (predInner != null) {
                    pArgs[predClosureLen] = x;
                    Object r = callNode.call(predInner, pArgs);
                    if (!(r instanceof Boolean) || !((Boolean) r)) { i += step; continue; }
                } else {
                    pArgs[predClosureLen] = MezzeRecord.wrapX(x);
                    Object r = callNode.call(predTarget, pArgs);
                    if (!(r instanceof Boolean) || !((Boolean) r)) { i += step; continue; }
                }
            }
            if (folderScalar) {
                fArgs[fAccSlot] = acc;
                fArgs[fElemSlot] = x;
                acc = callNode.call(folderInner, fArgs);
            } else {
                fArgs[folderClosureLen] = MezzeRecord.wrapAccX(acc, x);
                acc = callNode.call(folderTarget, fArgs);
            }
            i += step;
        }
        return acc;
    }

    /**
     * Fully generic recursive fold — used when the descriptor is deeper
     * than one map + one filter above a Range. Wraps the folder at each
     * level so a Filter above stops values that fail its predicate. Slow
     * because each layer adds a lambda; correct because it always works.
     */
    @TruffleBoundary
    private static Object foldViaRecursion(IterNode node, Object initAcc,
                                           MezzeFunction folder, IndirectCallNode callNode) {
        // STREAM IT. This used to materialize the whole iterator into a
        // `java.util.ArrayList` and fold that, under the note "the correctness
        // path isn't asked to be fast, only to work. Fusion is the fast path's
        // job." The second half is the problem: for a shape the emitter
        // REFUSES there is no fast path, so the correctness path is the only
        // one. MEASURED on `iter_perf_compare` at n=10M, min of three
        // interleaved passes: `zip+fold` 640 ms to 237 (2.70x) and
        // `flat_map+fold` 322 ms to 139 (2.31x), with `sum`, `filter+fold` and
        // `count` flat beside them at 1.00x, 0.97x and 1.05x.
        //
        // THOSE TWO ARE THE SHAPES THAT ACTUALLY ARRIVE HERE. `chain` and
        // `enumerate` are in the list of kinds above and measure 1.01x and
        // 0.98x, because their chains FUSE at compile time and this native is
        // never called; the other kinds reach it only when something else about
        // the chain declined.
        //
        // At that n the buffer held ten million elements, every one retained
        // until the fold ended, which is why the base arm also SWUNG — 603,
        // 1283, 833, 1330 and 742 ms across five passes against the streaming
        // arm's 252 to 261.
        //
        // {@link #walkElements} already pushes elements one at a time, handles
        // every descriptor kind, and stops early when the consumer says so.
        // `collect` was literally `walkElements(node, out::add, callNode)`, so
        // folding inside the walk visits exactly the same elements in exactly
        // the same order with nothing buffered.
        //
        // THE BUFFER BOUGHT NOTHING HERE. {@link #collectToArray} materializes
        // on purpose — behind its boundary, so that ITS caller's loop runs
        // outside one and the JIT can inline the callback. This path has no
        // such caller: {@link #fold} is itself `@TruffleBoundary`, so the fold
        // loop was already inside it either way.
        Object[] folderClosure = folder.getClosureValues();
        int folderClosureLen = folderClosure.length;
        Object[] accBox = {initAcc};
        CallTarget folderInner = ScalarBypass.tryResolve(folder);
        if (folderInner != null) {
            String[] params = folder.getParameterNames();
            boolean accFirst = params.length < 2 || "acc".equals(params[0]);
            int accSlot = accFirst ? folderClosureLen : folderClosureLen + 1;
            int elemSlot = accFirst ? folderClosureLen + 1 : folderClosureLen;
            Object[] fArgs = new Object[folderClosureLen + 2];
            System.arraycopy(folderClosure, 0, fArgs, 0, folderClosureLen);
            walkElements(node, x -> {
                fArgs[accSlot] = accBox[0];
                fArgs[elemSlot] = x;
                accBox[0] = callNode.call(folderInner, fArgs);
                return true;
            }, callNode);
        } else {
            com.oracle.truffle.api.CallTarget folderTarget = folder.getCallTarget();
            Object[] fArgs = new Object[folderClosureLen + 1];
            System.arraycopy(folderClosure, 0, fArgs, 0, folderClosureLen);
            walkElements(node, x -> {
                fArgs[folderClosureLen] = MezzeRecord.wrapAccX(accBox[0], x);
                accBox[0] = callNode.call(folderTarget, fArgs);
                return true;
            }, callNode);
        }
        return accBox[0];
    }
}
