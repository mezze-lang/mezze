package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.iter.IterNode;
import com.mezze.datastructures.iter.MezzeIter;
import com.mezze.datastructures.iter.RangeIterNode;
import com.mezze.datastructures.iter.SeqIterNode;
import com.mezze.datastructures.iter.TakeIterNode;
import com.mezze.datastructures.iter.DropIterNode;
import com.mezze.datastructures.iter.UnaryOpIterNode;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
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

    private IterNatives() {}

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
        return foldWalk(it.getDescriptor(), init, folder, callNode);
    }

    @TruffleBoundary
    public static long sum(MezzeIter it) {
        long[] acc = {0L};
        walkElements(it.getDescriptor(), x -> { acc[0] += (Long) x; }, null);
        return acc[0];
    }

    @TruffleBoundary
    public static Object forEach(MezzeIter it, MezzeFunction f,
                                 IndirectCallNode callNode) {
        Object[] closure = f.getClosureValues();
        int closureLen = closure.length;
        com.oracle.truffle.api.CallTarget target = f.getCallTarget();
        walkElements(it.getDescriptor(), x -> {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            args[closureLen] = MezzeRecord.wrapX(x);
            callNode.call(target, args);
        }, callNode);
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static long count(MezzeIter it, MezzeFunction p,
                             IndirectCallNode callNode) {
        Object[] closure = p.getClosureValues();
        int closureLen = closure.length;
        com.oracle.truffle.api.CallTarget target = p.getCallTarget();
        long[] acc = {0L};
        walkElements(it.getDescriptor(), x -> {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            args[closureLen] = MezzeRecord.wrapX(x);
            Object r = callNode.call(target, args);
            if (r instanceof Boolean && (Boolean) r) acc[0]++;
        }, callNode);
        return acc[0];
    }

    @TruffleBoundary
    public static boolean any(MezzeIter it, MezzeFunction p,
                              IndirectCallNode callNode) {
        Object[] closure = p.getClosureValues();
        int closureLen = closure.length;
        com.oracle.truffle.api.CallTarget target = p.getCallTarget();
        boolean[] found = {false};
        walkElements(it.getDescriptor(), x -> {
            if (found[0]) return;
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            args[closureLen] = MezzeRecord.wrapX(x);
            Object r = callNode.call(target, args);
            if (r instanceof Boolean && (Boolean) r) found[0] = true;
        }, callNode);
        return found[0];
    }

    @TruffleBoundary
    public static boolean all(MezzeIter it, MezzeFunction p,
                              IndirectCallNode callNode) {
        Object[] closure = p.getClosureValues();
        int closureLen = closure.length;
        com.oracle.truffle.api.CallTarget target = p.getCallTarget();
        boolean[] allTrue = {true};
        walkElements(it.getDescriptor(), x -> {
            if (!allTrue[0]) return;
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            args[closureLen] = MezzeRecord.wrapX(x);
            Object r = callNode.call(target, args);
            if (!(r instanceof Boolean) || !((Boolean) r)) allTrue[0] = false;
        }, callNode);
        return allTrue[0];
    }

    @TruffleBoundary
    public static Object find(MezzeIter it, MezzeFunction p,
                              IndirectCallNode callNode) {
        Object[] closure = p.getClosureValues();
        int closureLen = closure.length;
        com.oracle.truffle.api.CallTarget target = p.getCallTarget();
        Object[] result = {null};
        walkElements(it.getDescriptor(), x -> {
            if (result[0] != null) return;
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            args[closureLen] = MezzeRecord.wrapX(x);
            Object r = callNode.call(target, args);
            if (r instanceof Boolean && (Boolean) r) result[0] = com.mezze.interpreter.values.MezzeVariant.some(x);
        }, callNode);
        return result[0] != null ? result[0] : com.mezze.interpreter.values.MezzeVariant.none();
    }

    @TruffleBoundary
    public static Object reduce(MezzeIter it, MezzeFunction f,
                                IndirectCallNode callNode) {
        Object[] closure = f.getClosureValues();
        int closureLen = closure.length;
        com.oracle.truffle.api.CallTarget target = f.getCallTarget();
        Object[] acc = {null};
        boolean[] first = {true};
        walkElements(it.getDescriptor(), x -> {
            if (first[0]) {
                acc[0] = x;
                first[0] = false;
            } else {
                Object[] args = new Object[closureLen + 1];
                System.arraycopy(closure, 0, args, 0, closureLen);
                args[closureLen] = MezzeRecord.wrapAccX(acc[0], x);
                acc[0] = callNode.call(target, args);
            }
        }, callNode);
        return first[0] ? com.mezze.interpreter.values.MezzeVariant.none() : com.mezze.interpreter.values.MezzeVariant.some(acc[0]);
    }

    @TruffleBoundary
    public static Object toSeq(MezzeIter it, IndirectCallNode callNode) {
        java.util.ArrayList<Object> buf = new java.util.ArrayList<>();
        walkElements(it.getDescriptor(), buf::add, callNode);
        return com.mezze.datastructures.seq.MezzeSeq.fromArray(buf.toArray());
    }

    @TruffleBoundary
    public static Object toList(MezzeIter it, IndirectCallNode callNode) {
        java.util.ArrayList<Object> buf = new java.util.ArrayList<>();
        walkElements(it.getDescriptor(), buf::add, callNode);
        com.mezze.datastructures.list.MezzeList result = com.mezze.datastructures.list.MezzeNil.INSTANCE;
        for (int i = buf.size() - 1; i >= 0; i--) {
            result = result.prepend(buf.get(i));
        }
        return result;
    }

    // ===== ELEMENT WALKER =====

    @FunctionalInterface
    private interface ElementConsumer {
        void accept(Object element);
    }

    @TruffleBoundary
    private static void walkElements(IterNode node, ElementConsumer consumer,
                                     IndirectCallNode callNode) {
        if (node instanceof RangeIterNode range) {
            long i = range.start;
            long end = range.end;
            long step = range.step;
            if (step == 0) return;
            boolean asc = step > 0;
            while (asc ? (i < end) : (i > end)) {
                consumer.accept((Object) i);
                i += step;
            }
            return;
        }
        if (node instanceof SeqIterNode seqNode) {
            for (int idx = 0; idx < seqNode.size(); idx++) {
                consumer.accept(seqNode.get(idx));
            }
            return;
        }
        if (node instanceof TakeIterNode take) {
            long[] remaining = {take.n};
            walkElements(take.src, x -> {
                if (remaining[0] > 0) {
                    consumer.accept(x);
                    remaining[0]--;
                }
            }, callNode);
            return;
        }
        if (node instanceof DropIterNode drop) {
            long[] toSkip = {drop.n};
            walkElements(drop.src, x -> {
                if (toSkip[0] > 0) {
                    toSkip[0]--;
                } else {
                    consumer.accept(x);
                }
            }, callNode);
            return;
        }
        if (node instanceof UnaryOpIterNode unary) {
            MezzeFunction fn = (MezzeFunction) unary.fn;
            Object[] closure = fn.getClosureValues();
            int closureLen = closure.length;
            com.oracle.truffle.api.CallTarget target = fn.getCallTarget();
            if (unary.isMap()) {
                walkElements(unary.src, x -> {
                    Object[] args = new Object[closureLen + 1];
                    System.arraycopy(closure, 0, args, 0, closureLen);
                    args[closureLen] = MezzeRecord.wrapX(x);
                    consumer.accept(callNode.call(target, args));
                }, callNode);
            } else {
                walkElements(unary.src, x -> {
                    Object[] args = new Object[closureLen + 1];
                    System.arraycopy(closure, 0, args, 0, closureLen);
                    args[closureLen] = MezzeRecord.wrapX(x);
                    Object r = callNode.call(target, args);
                    if (r instanceof Boolean && (Boolean) r) consumer.accept(x);
                }, callNode);
            }
        }
    }

    /** Recursive walker. Each unary transform wraps the surrounding folder. */
    @TruffleBoundary
    private static Object foldWalk(IterNode node, Object acc, MezzeFunction folder,
                                   IndirectCallNode callNode) {
        if (node instanceof RangeIterNode range) {
            return foldRange(range, acc, folder, callNode, /*mapper*/ null, /*filter*/ null);
        }
        if (node instanceof SeqIterNode || node instanceof TakeIterNode || node instanceof DropIterNode) {
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
            IterNode cursor = unary;
            while (cursor instanceof UnaryOpIterNode u) {
                if (u.isMap()) {
                    if (mapper != null) break; // multi-map: bail to full recursion
                    mapper = (MezzeFunction) u.fn;
                } else {
                    if (pred != null) break; // multi-filter: bail
                    pred = (MezzeFunction) u.fn;
                }
                cursor = u.src;
            }
            if (cursor instanceof RangeIterNode range && !(cursor instanceof UnaryOpIterNode)) {
                return foldRange(range, acc, folder, callNode, mapper, pred);
            }
            // Deeper stacks fall back to a per-element recursion — slow
            // but correct.
            return foldViaRecursion(node, acc, folder, callNode);
        }
        throw new IllegalStateException("Unknown IterNode: " + node.getClass());
    }

    /**
     * Range walker with optional map + optional filter peeled above it.
     * Data-flow order matches the BDSL sink: filter first (raw element),
     * then map, then fold. Keeping both in one loop avoids the per-stage
     * intermediate structures that make eager collections slow.
     */
    @TruffleBoundary
    private static Object foldRange(RangeIterNode range, Object initAcc,
                                    MezzeFunction folder, IndirectCallNode callNode,
                                    MezzeFunction mapper, MezzeFunction pred) {
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

        boolean ascending = step > 0;
        while (ascending ? (i < end) : (i > end)) {
            Object x = (Object) i;

            if (pred != null) {
                Object[] pArgs = new Object[predClosureLen + 1];
                System.arraycopy(predClosure, 0, pArgs, 0, predClosureLen);
                pArgs[predClosureLen] = MezzeRecord.wrapX(x);
                Object r = callNode.call(predTarget, pArgs);
                if (!(r instanceof Boolean) || !((Boolean) r)) {
                    i += step;
                    continue;
                }
            }
            if (mapper != null) {
                Object[] mArgs = new Object[mapperClosureLen + 1];
                System.arraycopy(mapperClosure, 0, mArgs, 0, mapperClosureLen);
                mArgs[mapperClosureLen] = MezzeRecord.wrapX(x);
                x = callNode.call(mapperTarget, mArgs);
            }
            Object[] fArgs = new Object[folderClosureLen + 1];
            System.arraycopy(folderClosure, 0, fArgs, 0, folderClosureLen);
            fArgs[folderClosureLen] = MezzeRecord.wrapAccX(acc, x);
            acc = callNode.call(folderTarget, fArgs);
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
        // Materialize into an ArrayList and fold — the correctness path
        // isn't asked to be fast, only to work. Fusion is the fast
        // path's job.
        java.util.ArrayList<Object> buf = new java.util.ArrayList<>();
        collect(node, buf, callNode);
        Object[] folderClosure = folder.getClosureValues();
        int folderClosureLen = folderClosure.length;
        com.oracle.truffle.api.CallTarget folderTarget = folder.getCallTarget();
        Object acc = initAcc;
        for (Object x : buf) {
            Object[] fArgs = new Object[folderClosureLen + 1];
            System.arraycopy(folderClosure, 0, fArgs, 0, folderClosureLen);
            fArgs[folderClosureLen] = MezzeRecord.wrapAccX(acc, x);
            acc = callNode.call(folderTarget, fArgs);
        }
        return acc;
    }

    @TruffleBoundary
    private static void collect(IterNode node, java.util.ArrayList<Object> out,
                                IndirectCallNode callNode) {
        if (node instanceof RangeIterNode range) {
            long i = range.start;
            long end = range.end;
            long step = range.step;
            if (step == 0) return;
            boolean asc = step > 0;
            while (asc ? (i < end) : (i > end)) {
                out.add((Object) i);
                i += step;
            }
            return;
        }
        if (node instanceof SeqIterNode seqNode) {
            for (int idx = 0; idx < seqNode.size(); idx++) {
                out.add(seqNode.get(idx));
            }
            return;
        }
        if (node instanceof TakeIterNode take) {
            java.util.ArrayList<Object> inner = new java.util.ArrayList<>();
            collect(take.src, inner, callNode);
            long n = Math.min(take.n, inner.size());
            for (int idx = 0; idx < n; idx++) out.add(inner.get(idx));
            return;
        }
        if (node instanceof DropIterNode drop) {
            java.util.ArrayList<Object> inner = new java.util.ArrayList<>();
            collect(drop.src, inner, callNode);
            long start = Math.min(drop.n, inner.size());
            for (int idx = (int) start; idx < inner.size(); idx++) out.add(inner.get(idx));
            return;
        }
        if (node instanceof UnaryOpIterNode unary) {
            java.util.ArrayList<Object> inner = new java.util.ArrayList<>();
            collect(unary.src, inner, callNode);
            MezzeFunction fn = (MezzeFunction) unary.fn;
            Object[] closure = fn.getClosureValues();
            int closureLen = closure.length;
            com.oracle.truffle.api.CallTarget target = fn.getCallTarget();
            if (unary.isMap()) {
                for (Object x : inner) {
                    Object[] args = new Object[closureLen + 1];
                    System.arraycopy(closure, 0, args, 0, closureLen);
                    args[closureLen] = MezzeRecord.wrapX(x);
                    out.add(callNode.call(target, args));
                }
            } else {
                for (Object x : inner) {
                    Object[] args = new Object[closureLen + 1];
                    System.arraycopy(closure, 0, args, 0, closureLen);
                    args[closureLen] = MezzeRecord.wrapX(x);
                    Object r = callNode.call(target, args);
                    if (r instanceof Boolean && (Boolean) r) out.add(x);
                }
            }
        }
    }
}
