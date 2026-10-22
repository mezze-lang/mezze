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
    // THE definition, not a restatement of it — see BuiltinTypes.computeVariantTag.
    private static final int YIELD_TAG =
        com.mezze.language.ast.BuiltinTypes.INSTANCE.computeVariantTag("Yield", null);
    private static final int SKIP_TAG =
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
        return foldWalk(it.getDescriptor(), init, folder, callNode);
    }

    @TruffleBoundary
    public static long sum(MezzeIter it) {
        long[] acc = {0L};
        walkElements(it.getDescriptor(), x -> { acc[0] += (Long) x; return true; },
                     IndirectCallNode.getUncached());
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
        Object[] result = {null};
        walkElements(it.getDescriptor(), x -> {
            result[0] = MezzeVariant.some(x);
            return false;
        }, callNode);
        return result[0] != null ? result[0] : MezzeVariant.none();
    }

    @TruffleBoundary
    public static Object last(MezzeIter it, IndirectCallNode callNode) {
        Object[] result = {null};
        walkElements(it.getDescriptor(), x -> { result[0] = x; return true; }, callNode);
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
            // Materialize both sides, zip by index
            java.util.ArrayList<Object> left = new java.util.ArrayList<>();
            java.util.ArrayList<Object> right = new java.util.ArrayList<>();
            // NB both sides are MATERIALISED, so `zip` over an endless source
            // exhausts memory rather than hanging. Left as-is: fixing it means
            // interleaving two walks, which the push driver cannot express
            // without a cursor on at least one side.
            walkElements(zip.first, x -> { left.add(x); return true; }, callNode);
            walkElements(zip.second, x -> { right.add(x); return true; }, callNode);
            int len = Math.min(left.size(), right.size());
            for (int idx = 0; idx < len; idx++) {
                if (!consumer.accept(MezzeRecord.fromFields(
                        new String[]{"fst", "snd"}, new Object[]{left.get(idx), right.get(idx)}))) {
                    return false;
                }
            }
            return true;
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
