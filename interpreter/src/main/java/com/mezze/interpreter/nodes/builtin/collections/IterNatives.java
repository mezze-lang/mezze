package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.iter.IterNode;
import com.mezze.datastructures.iter.MezzeIter;
import com.mezze.datastructures.iter.RangeIterNode;
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

    // ===== FOLD (slow-path fallback) =====

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

    /** Recursive walker. Each unary transform wraps the surrounding folder. */
    @TruffleBoundary
    private static Object foldWalk(IterNode node, Object acc, MezzeFunction folder,
                                   IndirectCallNode callNode) {
        if (node instanceof RangeIterNode range) {
            return foldRange(range, acc, folder, callNode, /*mapper*/ null, /*filter*/ null);
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
