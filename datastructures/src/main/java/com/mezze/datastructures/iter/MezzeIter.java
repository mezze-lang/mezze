package com.mezze.datastructures.iter;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * User-facing wrapper around an {@link IterNode} descriptor tree. Mirrors
 * {@code MezzeSeq}'s shape: one {@code @CompilationFinal} storage field
 * (here the descriptor root), Java-side HOFs as {@code @TruffleBoundary}
 * fallbacks, TruffleObject/InteropLibrary export.
 *
 * <p>An {@code Iter} is lazy — none of its stages execute until a sink
 * (fold, sum, collect) walks the descriptor. The BDSL fused sink is the
 * fast path; the {@code fold} method here is the slow-path fallback used
 * when {@link com.mezze.datastructures.iter.IterFoldLongLoopNode} can't
 * specialize.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeIter implements TruffleObject {

    @CompilationFinal
    private final IterNode descriptor;

    public MezzeIter(IterNode descriptor) {
        this.descriptor = descriptor;
    }

    /** Descriptor root — the fold sink walks this. */
    public IterNode getDescriptor() {
        return descriptor;
    }

    // ===== CONSTRUCTION =====

    public static MezzeIter range(long start, long end) {
        return new MezzeIter(new RangeIterNode(start, end, 1L));
    }

    public static MezzeIter rangeStep(long start, long end, long step) {
        return new MezzeIter(new RangeIterNode(start, end, step));
    }

    public static MezzeIter fromSeq(com.mezze.datastructures.seq.MezzeSeq<?> seq) {
        return new MezzeIter(new SeqIterNode(seq));
    }

    // ===== TRANSFORM (return new descriptors — no work done) =====

    /** Java-side map: wraps the fn as opaque; the runtime fold layer casts. */
    public MezzeIter map(Object fn) {
        return new MezzeIter(new UnaryOpIterNode(UnaryOpIterNode.KIND_MAP, descriptor, fn));
    }

    /** Java-side filter: wraps the predicate as opaque. */
    public MezzeIter filter(Object p) {
        return new MezzeIter(new UnaryOpIterNode(UnaryOpIterNode.KIND_FILTER, descriptor, p));
    }

    public MezzeIter take(long n) {
        return new MezzeIter(new TakeIterNode(descriptor, n));
    }

    public MezzeIter drop(long n) {
        return new MezzeIter(new DropIterNode(descriptor, n));
    }

    // ===== JAVA-SIDE FOLD (fallback, not the hot path) =====

    /**
     * Reference fold using {@link java.util.function.BiFunction}. This is
     * the fallback when no native fold walker is available — currently
     * used only from Java tests. The Mezze-side hot path goes through
     * {@code IterNatives.fold} → BDSL sink → descriptor-tree walker.
     */
    @TruffleBoundary
    public <B> B fold(B init, BiFunction<B, Object, B> fn) {
        return foldGeneric(descriptor, init, fn);
    }

    @TruffleBoundary
    @SuppressWarnings("unchecked")
    private static <B> B foldGeneric(IterNode node, B init, BiFunction<B, Object, B> fn) {
        // Recursive descent: materialize the source stream and apply each
        // pending unary transform on the way out. For a plain Range this
        // is a tight primitive loop; for map/filter we recurse the fn tree.
        if (node instanceof RangeIterNode range) {
            B acc = init;
            long i = range.start;
            long end = range.end;
            long step = range.step;
            if (step > 0) {
                while (i < end) {
                    acc = fn.apply(acc, (Object) i);
                    i += step;
                }
            } else if (step < 0) {
                while (i > end) {
                    acc = fn.apply(acc, (Object) i);
                    i += step;
                }
            }
            return acc;
        }
        if (node instanceof SeqIterNode seqNode) {
            B acc = init;
            for (int idx = 0; idx < seqNode.size(); idx++) {
                acc = fn.apply(acc, seqNode.get(idx));
            }
            return acc;
        }
        if (node instanceof TakeIterNode take) {
            java.util.ArrayList<Object> buf = new java.util.ArrayList<>();
            foldGeneric(take.src, buf, (b, x) -> { if (b.size() < take.n) b.add(x); return b; });
            B acc = init;
            for (Object x : buf) acc = fn.apply(acc, x);
            return acc;
        }
        if (node instanceof DropIterNode drop) {
            long[] counter = {0};
            return foldGeneric(drop.src, init, (accB, x) -> {
                if (counter[0] < drop.n) { counter[0]++; return accB; }
                return fn.apply(accB, x);
            });
        }
        if (node instanceof UnaryOpIterNode unary) {
            if (unary.isMap()) {
                Function<Object, Object> mapper = (Function<Object, Object>) unary.fn;
                return foldGeneric(unary.src, init, (accB, x) -> fn.apply(accB, mapper.apply(x)));
            } else {
                Predicate<Object> pred = (Predicate<Object>) unary.fn;
                return foldGeneric(unary.src, init, (accB, x) -> pred.test(x) ? fn.apply(accB, x) : accB);
            }
        }
        throw new IllegalStateException("Unknown IterNode: " + node.getClass());
    }

    // ===== INTEROP =====

    @ExportMessage
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object getIterator() {
        // Materialize on demand — polyglot iteration is not the hot path.
        return new IterIterator(this);
    }

    /** Simple pull-based iterator used only by polyglot interop. */
    @ExportLibrary(InteropLibrary.class)
    static final class IterIterator implements TruffleObject {
        private final java.util.Iterator<Object> it;

        IterIterator(MezzeIter iter) {
            java.util.ArrayList<Object> buf = new java.util.ArrayList<>();
            iter.fold(buf, (acc, x) -> { acc.add(x); return acc; });
            this.it = buf.iterator();
        }

        @ExportMessage
        boolean isIterator() { return true; }

        @ExportMessage
        boolean hasIteratorNextElement() { return it.hasNext(); }

        @ExportMessage
        Object getIteratorNextElement() { return it.next(); }
    }

    @Override
    public String toString() {
        return "MezzeIter(" + descriptor.getClass().getSimpleName() + ")";
    }
}
