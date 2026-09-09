package com.mezze.interpreter.nodes.builtin.collections;

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

    private IterNatives() {}

    // Step variant tags — computed once, same as TagRegistry.getOrCreateTag
    private static final int YIELD_TAG = "Yield".hashCode() & 0x7FFFFFFF;
    private static final int SKIP_TAG  = "Skip".hashCode()  & 0x7FFFFFFF;
    private static final int DONE_TAG  = "Done".hashCode()  & 0x7FFFFFFF;

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
        CallTarget innerTarget = ScalarBypass.tryResolve(f);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                args[closureLen] = x;
                callNode.call(innerTarget, args);
            }, callNode);
        } else {
            CallTarget target = f.getCallTarget();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                args[closureLen] = MezzeRecord.wrapX(x);
                callNode.call(target, args);
            }, callNode);
        }
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static long count(MezzeIter it, MezzeFunction p,
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
            }, callNode);
        } else {
            CallTarget target = p.getCallTarget();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                args[closureLen] = MezzeRecord.wrapX(x);
                Object r = callNode.call(target, args);
                if (r instanceof Boolean && (Boolean) r) acc[0]++;
            }, callNode);
        }
        return acc[0];
    }

    @TruffleBoundary
    public static boolean any(MezzeIter it, MezzeFunction p,
                              IndirectCallNode callNode) {
        Object[] closure = p.getClosureValues();
        int closureLen = closure.length;
        boolean[] found = {false};
        CallTarget innerTarget = ScalarBypass.tryResolve(p);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                if (found[0]) return;
                args[closureLen] = x;
                Object r = callNode.call(innerTarget, args);
                if (r instanceof Boolean && (Boolean) r) found[0] = true;
            }, callNode);
        } else {
            CallTarget target = p.getCallTarget();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                if (found[0]) return;
                args[closureLen] = MezzeRecord.wrapX(x);
                Object r = callNode.call(target, args);
                if (r instanceof Boolean && (Boolean) r) found[0] = true;
            }, callNode);
        }
        return found[0];
    }

    @TruffleBoundary
    public static boolean all(MezzeIter it, MezzeFunction p,
                              IndirectCallNode callNode) {
        Object[] closure = p.getClosureValues();
        int closureLen = closure.length;
        boolean[] allTrue = {true};
        CallTarget innerTarget = ScalarBypass.tryResolve(p);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                if (!allTrue[0]) return;
                args[closureLen] = x;
                Object r = callNode.call(innerTarget, args);
                if (!(r instanceof Boolean) || !((Boolean) r)) allTrue[0] = false;
            }, callNode);
        } else {
            CallTarget target = p.getCallTarget();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                if (!allTrue[0]) return;
                args[closureLen] = MezzeRecord.wrapX(x);
                Object r = callNode.call(target, args);
                if (!(r instanceof Boolean) || !((Boolean) r)) allTrue[0] = false;
            }, callNode);
        }
        return allTrue[0];
    }

    @TruffleBoundary
    public static Object find(MezzeIter it, MezzeFunction p,
                              IndirectCallNode callNode) {
        Object[] closure = p.getClosureValues();
        int closureLen = closure.length;
        Object[] result = {null};
        CallTarget innerTarget = ScalarBypass.tryResolve(p);
        if (innerTarget != null) {
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                if (result[0] != null) return;
                args[closureLen] = x;
                Object r = callNode.call(innerTarget, args);
                if (r instanceof Boolean && (Boolean) r) result[0] = com.mezze.interpreter.values.MezzeVariant.some(x);
            }, callNode);
        } else {
            CallTarget target = p.getCallTarget();
            Object[] args = new Object[closureLen + 1];
            System.arraycopy(closure, 0, args, 0, closureLen);
            walkElements(it.getDescriptor(), x -> {
                if (result[0] != null) return;
                args[closureLen] = MezzeRecord.wrapX(x);
                Object r = callNode.call(target, args);
                if (r instanceof Boolean && (Boolean) r) result[0] = com.mezze.interpreter.values.MezzeVariant.some(x);
            }, callNode);
        }
        return result[0] != null ? result[0] : com.mezze.interpreter.values.MezzeVariant.none();
    }

    @TruffleBoundary
    public static Object reduce(MezzeIter it, MezzeFunction f,
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
            }, callNode);
        }
        return first[0] ? com.mezze.interpreter.values.MezzeVariant.none() : com.mezze.interpreter.values.MezzeVariant.some(acc[0]);
    }

    @TruffleBoundary
    public static Object toSeq(MezzeIter it, IndirectCallNode callNode) {
        java.util.ArrayList<Object> buf = new java.util.ArrayList<>();
        walkElements(it.getDescriptor(), buf::add, callNode);
        return com.mezze.datastructures.seq.MezzeSeq.fromArray(buf.toArray());
    }

    @TruffleBoundary
    public static long countAll(MezzeIter it, IndirectCallNode callNode) {
        long[] acc = {0L};
        walkElements(it.getDescriptor(), x -> acc[0]++, callNode);
        return acc[0];
    }

    @TruffleBoundary
    public static Object first(MezzeIter it, IndirectCallNode callNode) {
        Object[] result = {null};
        walkElements(it.getDescriptor(), x -> {
            if (result[0] == null) result[0] = MezzeVariant.some(x);
        }, callNode);
        return result[0] != null ? result[0] : MezzeVariant.none();
    }

    @TruffleBoundary
    public static Object last(MezzeIter it, IndirectCallNode callNode) {
        Object[] result = {null};
        walkElements(it.getDescriptor(), x -> result[0] = x, callNode);
        return result[0] != null ? MezzeVariant.some(result[0]) : MezzeVariant.none();
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
        if (node instanceof ListIterNode listNode) {
            Object[] arr = listNode.list.toArray();
            for (Object x : arr) {
                consumer.accept(x);
            }
            return;
        }
        if (node instanceof OnceIterNode once) {
            consumer.accept(once.value);
            return;
        }
        if (node instanceof EmptyIterNode) {
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
        if (node instanceof TakeWhileIterNode tw) {
            boolean[] done = {false};
            MezzeFunction pred = (MezzeFunction) tw.predicate;
            CallTarget predInner = ScalarBypass.tryResolve(pred);
            Object[] pClosure = pred.getClosureValues();
            int pClosureLen = pClosure.length;
            Object[] pArgs = new Object[pClosureLen + 1];
            System.arraycopy(pClosure, 0, pArgs, 0, pClosureLen);
            walkElements(tw.src, x -> {
                if (done[0]) return;
                if (predInner != null) {
                    pArgs[pClosureLen] = x;
                    Object r = callNode.call(predInner, pArgs);
                    if (r instanceof Boolean && (Boolean) r) consumer.accept(x);
                    else done[0] = true;
                } else {
                    pArgs[pClosureLen] = MezzeRecord.wrapX(x);
                    Object r = callNode.call(pred.getCallTarget(), pArgs);
                    if (r instanceof Boolean && (Boolean) r) consumer.accept(x);
                    else done[0] = true;
                }
            }, callNode);
            return;
        }
        if (node instanceof DropWhileIterNode dw) {
            boolean[] dropping = {true};
            MezzeFunction pred = (MezzeFunction) dw.predicate;
            CallTarget predInner = ScalarBypass.tryResolve(pred);
            Object[] pClosure = pred.getClosureValues();
            int pClosureLen = pClosure.length;
            Object[] pArgs = new Object[pClosureLen + 1];
            System.arraycopy(pClosure, 0, pArgs, 0, pClosureLen);
            walkElements(dw.src, x -> {
                if (dropping[0]) {
                    if (predInner != null) {
                        pArgs[pClosureLen] = x;
                        Object r = callNode.call(predInner, pArgs);
                        if (!(r instanceof Boolean && (Boolean) r)) {
                            dropping[0] = false;
                            consumer.accept(x);
                        }
                    } else {
                        pArgs[pClosureLen] = MezzeRecord.wrapX(x);
                        Object r = callNode.call(pred.getCallTarget(), pArgs);
                        if (!(r instanceof Boolean && (Boolean) r)) {
                            dropping[0] = false;
                            consumer.accept(x);
                        }
                    }
                } else {
                    consumer.accept(x);
                }
            }, callNode);
            return;
        }
        if (node instanceof EnumerateIterNode en) {
            long[] idx = {0L};
            walkElements(en.src, x -> {
                consumer.accept(MezzeRecord.fromFields(
                    new String[]{"i", "val"}, new Object[]{idx[0], x}));
                idx[0]++;
            }, callNode);
            return;
        }
        if (node instanceof ChainIterNode chain) {
            walkElements(chain.first, consumer, callNode);
            walkElements(chain.second, consumer, callNode);
            return;
        }
        if (node instanceof ZipIterNode zip) {
            // Materialize both sides, zip by index
            java.util.ArrayList<Object> left = new java.util.ArrayList<>();
            java.util.ArrayList<Object> right = new java.util.ArrayList<>();
            walkElements(zip.first, left::add, callNode);
            walkElements(zip.second, right::add, callNode);
            int len = Math.min(left.size(), right.size());
            for (int idx = 0; idx < len; idx++) {
                consumer.accept(MezzeRecord.fromFields(
                    new String[]{"fst", "snd"}, new Object[]{left.get(idx), right.get(idx)}));
            }
            return;
        }
        if (node instanceof FlatMapIterNode fm) {
            MezzeFunction fn = (MezzeFunction) fm.fn;
            Object[] fmClosure = fn.getClosureValues();
            int fmClosureLen = fmClosure.length;
            CallTarget fmInner = ScalarBypass.tryResolve(fn);
            Object[] fmArgs = new Object[fmClosureLen + 1];
            System.arraycopy(fmClosure, 0, fmArgs, 0, fmClosureLen);
            walkElements(fm.src, x -> {
                MezzeIter inner;
                if (fmInner != null) {
                    fmArgs[fmClosureLen] = x;
                    inner = (MezzeIter) callNode.call(fmInner, fmArgs);
                } else {
                    fmArgs[fmClosureLen] = MezzeRecord.wrapX(x);
                    inner = (MezzeIter) callNode.call(fn.getCallTarget(), fmArgs);
                }
                walkElements(inner.getDescriptor(), consumer, callNode);
            }, callNode);
            return;
        }
        if (node instanceof UnfoldIterNode unfold) {
            walkUnfold(unfold, consumer, callNode);
            return;
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
                    walkElements(unary.src, x -> {
                        args[closureLen] = x;
                        consumer.accept(callNode.call(innerTarget, args));
                    }, callNode);
                } else {
                    walkElements(unary.src, x -> {
                        args[closureLen] = x;
                        Object r = callNode.call(innerTarget, args);
                        if (r instanceof Boolean && (Boolean) r) consumer.accept(x);
                    }, callNode);
                }
            } else {
                com.oracle.truffle.api.CallTarget target = fn.getCallTarget();
                Object[] args = new Object[closureLen + 1];
                System.arraycopy(closure, 0, args, 0, closureLen);
                if (unary.isMap()) {
                    walkElements(unary.src, x -> {
                        args[closureLen] = MezzeRecord.wrapX(x);
                        consumer.accept(callNode.call(target, args));
                    }, callNode);
                } else {
                    walkElements(unary.src, x -> {
                        args[closureLen] = MezzeRecord.wrapX(x);
                        Object r = callNode.call(target, args);
                        if (r instanceof Boolean && (Boolean) r) consumer.accept(x);
                    }, callNode);
                }
            }
        }
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
    private static void walkUnfold(UnfoldIterNode unfold, ElementConsumer consumer,
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
                        consumer.accept(rec.getField("val"));
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
                        consumer.accept(rec.getField("val"));
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
    }

    /** Recursive walker. Each unary transform wraps the surrounding folder. */
    @TruffleBoundary
    private static Object foldWalk(IterNode node, Object acc, MezzeFunction folder,
                                   IndirectCallNode callNode) {
        if (node instanceof RangeIterNode range) {
            return foldRange(range, acc, folder, callNode, /*mapper*/ null, /*filter*/ null);
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

            if (pred != null) {
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
        // Materialize into an ArrayList and fold — the correctness path
        // isn't asked to be fast, only to work. Fusion is the fast
        // path's job.
        java.util.ArrayList<Object> buf = new java.util.ArrayList<>();
        collect(node, buf, callNode);
        Object[] folderClosure = folder.getClosureValues();
        int folderClosureLen = folderClosure.length;
        Object acc = initAcc;
        CallTarget folderInner = ScalarBypass.tryResolve(folder);
        if (folderInner != null) {
            String[] params = folder.getParameterNames();
            boolean accFirst = params.length < 2 || "acc".equals(params[0]);
            int accSlot = accFirst ? folderClosureLen : folderClosureLen + 1;
            int elemSlot = accFirst ? folderClosureLen + 1 : folderClosureLen;
            Object[] fArgs = new Object[folderClosureLen + 2];
            System.arraycopy(folderClosure, 0, fArgs, 0, folderClosureLen);
            for (Object x : buf) {
                fArgs[accSlot] = acc;
                fArgs[elemSlot] = x;
                acc = callNode.call(folderInner, fArgs);
            }
        } else {
            com.oracle.truffle.api.CallTarget folderTarget = folder.getCallTarget();
            Object[] fArgs = new Object[folderClosureLen + 1];
            System.arraycopy(folderClosure, 0, fArgs, 0, folderClosureLen);
            for (Object x : buf) {
                fArgs[folderClosureLen] = MezzeRecord.wrapAccX(acc, x);
                acc = callNode.call(folderTarget, fArgs);
            }
        }
        return acc;
    }

    /**
     * Collect all elements into an ArrayList. Delegates to walkElements
     * for all node types — keeps a single source of truth for walking.
     */
    @TruffleBoundary
    private static void collect(IterNode node, java.util.ArrayList<Object> out,
                                IndirectCallNode callNode) {
        walkElements(node, out::add, callNode);
    }
}
