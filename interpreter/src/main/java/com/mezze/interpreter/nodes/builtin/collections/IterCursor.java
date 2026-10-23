package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.iter.ChainIterNode;
import com.mezze.datastructures.iter.DropIterNode;
import com.mezze.datastructures.iter.DropWhileIterNode;
import com.mezze.datastructures.iter.EmptyIterNode;
import com.mezze.datastructures.iter.EnumerateIterNode;
import com.mezze.datastructures.iter.FlatMapIterNode;
import com.mezze.datastructures.iter.IterNode;
import com.mezze.datastructures.iter.ListIterNode;
import com.mezze.datastructures.iter.MezzeIter;
import com.mezze.datastructures.iter.OnceIterNode;
import com.mezze.datastructures.iter.RangeIterNode;
import com.mezze.datastructures.iter.SeqIterNode;
import com.mezze.datastructures.iter.TakeIterNode;
import com.mezze.datastructures.iter.TakeWhileIterNode;
import com.mezze.datastructures.iter.UnaryOpIterNode;
import com.mezze.datastructures.iter.UnfoldIterNode;
import com.mezze.datastructures.iter.ZipIterNode;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.IndirectCallNode;

/**
 * A RESUMABLE pull view over the same descriptor tree the push walk uses.
 *
 * `IterNatives.walkElements` is a push driver: it calls a consumer once per
 * element and, since the early-exit fix, can be told to STOP. It still cannot
 * be RESUMED from the middle, and two things need exactly that:
 *
 *   - A lazily-yielded output. `map_concurrent` has to hand an element
 *     downstream and come back for the next one later, which a push walk
 *     cannot express — it owns the loop.
 *   - `zip`. The push walk MATERIALISES both sides and indexes them, so
 *     zipping anything endless exhausts memory. With a cursor per side it is
 *     two `next()` calls and a pair, holding nothing.
 *
 * Why this is a second mechanism rather than a replacement: the push walk is
 * the fast path. It fuses, keeps its loop variables in registers and allocates
 * nothing per element, and the measured numbers in
 * `bench/cases/concurrent_map_compare` rest on it. A cursor allocates a frame
 * per stage and checks state per element. So the push walk stays the default
 * for every existing sink, and a cursor is created only where suspension is
 * the requirement. Converting everything to pull would be the textbook move
 * and would make the common case slower.
 *
 * Exhaustion is the {@link #END} sentinel rather than null, because a Mezze
 * iterator may legitimately yield null-ish host values, and rather than a
 * `hasNext`/`next` pair, which would force every stage to compute an element
 * before being asked whether one exists — wrong when computing it performs an
 * effect.
 *
 * NOT thread-safe, by construction. A cursor is mutable position, and the
 * handler chain a guest callback needs is thread-local; sharing one across
 * threads would be the cross-thread handler bug this interpreter already has
 * an assumption guarding.
 */
public abstract class IterCursor {

    /** Returned by {@link #next} when the source is exhausted. */
    public static final Object END = new Object();

    /** @return the next element, or {@link #END}. */
    public abstract Object next();

    // ==================== construction ====================

    @TruffleBoundary
    public static IterCursor of(MezzeIter it, IndirectCallNode callNode) {
        return of(it.getDescriptor(), callNode);
    }

    @TruffleBoundary
    public static IterCursor of(IterNode node, IndirectCallNode callNode) {
        if (node instanceof RangeIterNode n) return new Range(n);
        if (node instanceof SeqIterNode n) return new Seq(n);
        if (node instanceof ListIterNode n) return new Arr(n.list.toArray());
        if (node instanceof OnceIterNode n) return new Once(n.value);
        if (node instanceof EmptyIterNode) return new Empty();
        if (node instanceof TakeIterNode n) return new Take(of(n.src, callNode), n.n);
        if (node instanceof DropIterNode n) return new Drop(of(n.src, callNode), n.n);
        if (node instanceof TakeWhileIterNode n) {
            return new TakeWhile(of(n.src, callNode), (MezzeFunction) n.predicate, callNode);
        }
        if (node instanceof DropWhileIterNode n) {
            return new DropWhile(of(n.src, callNode), (MezzeFunction) n.predicate, callNode);
        }
        if (node instanceof EnumerateIterNode n) return new Enumerate(of(n.src, callNode));
        if (node instanceof ChainIterNode n) {
            return new Chain(of(n.first, callNode), of(n.second, callNode));
        }
        if (node instanceof ZipIterNode n) {
            return new Zip(of(n.first, callNode), of(n.second, callNode));
        }
        if (node instanceof FlatMapIterNode n) {
            return new FlatMap(of(n.src, callNode), (MezzeFunction) n.fn, callNode);
        }
        if (node instanceof UnfoldIterNode n) {
            return new Unfold(n.initialState, (MezzeFunction) n.stepFunction, callNode);
        }
        if (node instanceof UnaryOpIterNode n) {
            IterCursor src = of(n.src, callNode);
            MezzeFunction fn = (MezzeFunction) n.fn;
            return n.isMap() ? new Map(src, fn, callNode) : new Filter(src, fn, callNode);
        }
        // No fallback to materialising. A node type this does not know would
        // silently change a pipeline's semantics, and a loud failure names the
        // one class somebody forgot to add here.
        throw new IllegalStateException(
            "IterCursor: no cursor for descriptor node " + node.getClass().getSimpleName());
    }

    /** Calls a guest unary function, honouring the scalar bypass. */
    private static final class Call {
        private final CallTarget direct;
        private final CallTarget wrapped;
        private final Object[] args;
        private final int slot;
        private final IndirectCallNode callNode;

        Call(MezzeFunction fn, IndirectCallNode callNode) {
            this.callNode = callNode;
            Object[] closure = fn.getClosureValues();
            this.slot = closure.length;
            this.args = new Object[closure.length + 1];
            System.arraycopy(closure, 0, args, 0, closure.length);
            CallTarget bypass = ScalarBypass.tryResolve(fn);
            this.direct = bypass;
            this.wrapped = bypass == null ? fn.getCallTarget() : null;
        }

        Object on(Object x) {
            if (direct != null) {
                args[slot] = x;
                return callNode.call(direct, args);
            }
            args[slot] = MezzeRecord.wrapX(x);
            return callNode.call(wrapped, args);
        }

        Object onState(Object s) {
            if (direct != null) {
                args[slot] = s;
                return callNode.call(direct, args);
            }
            args[slot] = MezzeRecord.wrapS(s);
            return callNode.call(wrapped, args);
        }
    }

    private static boolean truthy(Object r) {
        return r instanceof Boolean && (Boolean) r;
    }

    // ==================== sources ====================

    private static final class Range extends IterCursor {
        private long i;
        private final long end;
        private final long step;

        Range(RangeIterNode n) {
            this.i = n.start;
            this.end = n.end;
            this.step = n.step;
        }

        @Override
        public Object next() {
            if (step == 0) return END;
            if (step > 0 ? i >= end : i <= end) return END;
            long v = i;
            i += step;
            return v;
        }
    }

    private static final class Seq extends IterCursor {
        private final SeqIterNode node;
        private int idx;

        Seq(SeqIterNode node) { this.node = node; }

        @Override
        public Object next() {
            return idx < node.size() ? node.get(idx++) : END;
        }
    }

    private static final class Arr extends IterCursor {
        private final Object[] items;
        private int idx;

        Arr(Object[] items) { this.items = items; }

        @Override
        public Object next() {
            return idx < items.length ? items[idx++] : END;
        }
    }

    private static final class Once extends IterCursor {
        private Object value;
        private boolean done;

        Once(Object value) { this.value = value; }

        @Override
        public Object next() {
            if (done) return END;
            done = true;
            Object v = value;
            value = null;
            return v;
        }
    }

    private static final class Empty extends IterCursor {
        @Override
        public Object next() { return END; }
    }

    /**
     * The user-defined source. `Skip` loops rather than yielding, which is
     * what lets a step advance its state without producing — a line reader
     * that needs another chunk, for instance.
     */
    private static final class Unfold extends IterCursor {
        private final Call step;
        private Object state;
        private boolean done;

        Unfold(Object initialState, MezzeFunction stepFn, IndirectCallNode callNode) {
            this.state = initialState;
            this.step = new Call(stepFn, callNode);
        }

        @Override
        public Object next() {
            while (!done) {
                MezzeVariant s = (MezzeVariant) step.onState(state);
                int tag = s.getTag();
                if (tag == IterNatives.YIELD_TAG) {
                    Object payload = s.getRawPayloadValue();
                    if (payload instanceof MezzeRecord rec) {
                        state = rec.getField("next");
                        return rec.getField("val");
                    }
                    // Fused single-field payload: Yield has two fields, so
                    // this is the end of a degenerate shape rather than a
                    // value plus a state.
                    done = true;
                    return payload;
                } else if (tag == IterNatives.SKIP_TAG) {
                    state = s.getPayloadFieldByName("next");
                } else {
                    done = true;
                }
            }
            return END;
        }
    }

    // ==================== stages ====================

    private static final class Map extends IterCursor {
        private final IterCursor src;
        private final Call f;

        Map(IterCursor src, MezzeFunction fn, IndirectCallNode callNode) {
            this.src = src;
            this.f = new Call(fn, callNode);
        }

        @Override
        public Object next() {
            Object x = src.next();
            return x == END ? END : f.on(x);
        }
    }

    private static final class Filter extends IterCursor {
        private final IterCursor src;
        private final Call p;

        Filter(IterCursor src, MezzeFunction pred, IndirectCallNode callNode) {
            this.src = src;
            this.p = new Call(pred, callNode);
        }

        @Override
        public Object next() {
            for (Object x = src.next(); x != END; x = src.next()) {
                if (truthy(p.on(x))) return x;
            }
            return END;
        }
    }

    private static final class Take extends IterCursor {
        private final IterCursor src;
        private long remaining;

        Take(IterCursor src, long n) {
            this.src = src;
            this.remaining = n;
        }

        @Override
        public Object next() {
            // Does not touch the source once the quota is met, which is what
            // makes `take` in front of an endless producer terminate.
            if (remaining <= 0) return END;
            Object x = src.next();
            if (x == END) {
                remaining = 0;
                return END;
            }
            remaining--;
            return x;
        }
    }

    private static final class Drop extends IterCursor {
        private final IterCursor src;
        private long toSkip;

        Drop(IterCursor src, long n) {
            this.src = src;
            this.toSkip = n;
        }

        @Override
        public Object next() {
            while (toSkip > 0) {
                if (src.next() == END) {
                    toSkip = 0;
                    return END;
                }
                toSkip--;
            }
            return src.next();
        }
    }

    private static final class TakeWhile extends IterCursor {
        private final IterCursor src;
        private final Call p;
        private boolean done;

        TakeWhile(IterCursor src, MezzeFunction pred, IndirectCallNode callNode) {
            this.src = src;
            this.p = new Call(pred, callNode);
        }

        @Override
        public Object next() {
            if (done) return END;
            Object x = src.next();
            if (x == END || !truthy(p.on(x))) {
                done = true;
                return END;
            }
            return x;
        }
    }

    private static final class DropWhile extends IterCursor {
        private final IterCursor src;
        private final Call p;
        private boolean dropping = true;

        DropWhile(IterCursor src, MezzeFunction pred, IndirectCallNode callNode) {
            this.src = src;
            this.p = new Call(pred, callNode);
        }

        @Override
        public Object next() {
            if (dropping) {
                for (Object x = src.next(); x != END; x = src.next()) {
                    if (!truthy(p.on(x))) {
                        dropping = false;
                        return x;
                    }
                }
                dropping = false;
                return END;
            }
            return src.next();
        }
    }

    private static final class Enumerate extends IterCursor {
        private final IterCursor src;
        private long idx;

        Enumerate(IterCursor src) { this.src = src; }

        @Override
        public Object next() {
            Object x = src.next();
            if (x == END) return END;
            return MezzeRecord.fromFields(new String[]{"i", "val"}, new Object[]{idx++, x});
        }
    }

    private static final class Chain extends IterCursor {
        private final IterCursor first;
        private final IterCursor second;
        private boolean onFirst = true;

        Chain(IterCursor first, IterCursor second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public Object next() {
            if (onFirst) {
                Object x = first.next();
                if (x != END) return x;
                onFirst = false;
            }
            return second.next();
        }
    }

    /**
     * Zip WITHOUT materialising either side — the reason this class exists as
     * much as lazy output is. The push walk collected both sides into
     * ArrayLists and indexed them, so zipping an endless source exhausted
     * memory. Here it is one `next()` per side and a pair, holding nothing,
     * and it stops at the shorter side as it always did.
     */
    private static final class Zip extends IterCursor {
        private final IterCursor first;
        private final IterCursor second;

        Zip(IterCursor first, IterCursor second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public Object next() {
            Object a = first.next();
            if (a == END) return END;
            Object b = second.next();
            if (b == END) return END;
            return MezzeRecord.fromFields(new String[]{"fst", "snd"}, new Object[]{a, b});
        }
    }

    private static final class FlatMap extends IterCursor {
        private final IterCursor outer;
        private final Call f;
        private final IndirectCallNode callNode;
        private IterCursor inner;

        FlatMap(IterCursor outer, MezzeFunction fn, IndirectCallNode callNode) {
            this.outer = outer;
            this.f = new Call(fn, callNode);
            this.callNode = callNode;
        }

        @Override
        public Object next() {
            while (true) {
                if (inner != null) {
                    Object x = inner.next();
                    if (x != END) return x;
                    inner = null;
                }
                Object o = outer.next();
                if (o == END) return END;
                // The callback answers an Iter; walk it with its own cursor so
                // an inner source can itself be lazy.
                inner = IterCursor.of((MezzeIter) f.on(o), callNode);
            }
        }
    }
}
