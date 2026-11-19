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
 * A pull view over an iter descriptor: `next()` yields the next element or {@link #END}. This is
 * the `iter::cursor` surface and the HTTP streaming reader. A callback that parks blocks its carrier
 * inside `next()`; the cursor never sees a suspension (the carrier machine).
 */
public abstract class IterCursor {
    /** `{ fst, snd }`, hoisted: this was allocated per call. Names from
     *  {@link com.mezze.language.truffleir.NativeHofNames} so a rename reaches every spelling. */
    private static final String[] FIELD_FST_SND =
        {com.mezze.language.truffleir.NativeHofNames.PAIR_FST, com.mezze.language.truffleir.NativeHofNames.PAIR_SND};


    /** Returned by {@link #next} when the source is exhausted. */
    public static final Object END = new Object();

    /** @return the next element, or {@link #END}. */
    /** The next element, {@link #END}, or — C4 — a SUSPENSION (a callback in this
     *  chain parked): the stage that saw it remembers where it was, and the consumer
     *  hands the callback's eventual value back through {@link #resume}. */
    public abstract Object next();

    /** Continue after {@link #next} returned a suspension: {@code v} is what the
     *  suspended callback finally returned. Same contract as {@code next}. */
    private static final Object CONTINUE = new Object();

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
    static final class Call {

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
                Object r = step.onState(state);
                Object out = decide((MezzeVariant) r);
                if (out != CONTINUE) return out;
            }
            return END;
        }
        private Object decide(MezzeVariant s) {
            int tag = s.getTag();
            if (tag == IterNatives.YIELD_TAG) {
                Object payload = s.getRawPayloadValue();
                if (payload instanceof MezzeRecord rec) {
                    state = rec.getField("next");
                    return rec.getField("val");
                }
                done = true;
                return payload;
            } else if (tag == IterNatives.SKIP_TAG) {
                state = s.getPayloadFieldByName("next");
                return CONTINUE;
            }
            done = true;
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
        public Object next() { return after(src.next()); }
        private Object after(Object x) {
            if (x == END) return END;
            Object r = f.on(x);
            return r;
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
        public Object next() { return scan(src.next()); }
        private Object scan(Object x) {
            while (true) {
                if (x == END) return END;
                Object r = p.on(x);
                if (truthy(r)) return x;
                x = src.next();
            }
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
            if (remaining <= 0) return END;
            return after(src.next());
        }
        private Object after(Object x) {
            if (x == END) { remaining = 0; return END; }
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
        public Object next() { return skipThen(src.next()); }
        private Object skipThen(Object x) {
            while (true) {
                if (toSkip <= 0) return x;
                if (x == END) { toSkip = 0; return END; }
                toSkip--;
                x = src.next();
            }
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
            return after(src.next());
        }
        private Object after(Object x) {
            if (x == END) { done = true; return END; }
            Object r = p.on(x);
            if (!truthy(r)) { done = true; return END; }
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
        public Object next() { return scan(src.next()); }
        private Object scan(Object x) {
            while (true) {
                if (!dropping || x == END) return x;
                Object r = p.on(x);
                if (!truthy(r)) { dropping = false; return x; }
                x = src.next();
            }
        }
    }

    private static final class Enumerate extends IterCursor {
        private final IterCursor src;
        private long idx;
        Enumerate(IterCursor src) { this.src = src; }
        @Override
        public Object next() { return after(src.next()); }
        private Object after(Object x) {
            if (x == END) return x;
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
            if (onFirst) return afterFirst(first.next());
            return second.next();
        }
        private Object afterFirst(Object x) {
            if (x != END) return x;
            onFirst = false;
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
        private Object pendingA;
        Zip(IterCursor first, IterCursor second) {
            this.first = first;
            this.second = second;
        }
        @Override
        public Object next() { return afterA(first.next()); }
        private Object afterA(Object a) {
            if (a == END) return END;
            pendingA = a;
            return afterB(second.next());
        }
        private Object afterB(Object b) {
            Object a = pendingA; pendingA = null;
            if (b == END) return END;
            return MezzeRecord.fromFields(FIELD_FST_SND, new Object[]{a, b});
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
            if (inner != null) return afterInner(inner.next());
            return afterOuter(outer.next());
        }
        private Object afterInner(Object x) {
            if (x != END) return x;
            inner = null;
            return afterOuter(outer.next());
        }
        private Object afterOuter(Object o) {
            if (o == END) return END;
            Object it = f.on(o);
            inner = IterCursor.of((MezzeIter) it, callNode);
            return afterInner(inner.next());
        }
    }
}
