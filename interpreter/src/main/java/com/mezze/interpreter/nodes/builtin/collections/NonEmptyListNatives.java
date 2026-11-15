package com.mezze.interpreter.nodes.builtin.collections;

import com.mezze.datastructures.list.MezzeList;

import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Native NonEmptyList operations - ULTRA PERFORMANT.
 *
 * NonEmptyList is represented as Object[] with guaranteed length >= 1.
 * All operations are designed for JIT compilation.
 */
public final class NonEmptyListNatives {
    /** `{ fst, snd }`, hoisted: this was allocated per call. Names from
     *  {@link com.mezze.language.truffleir.NativeHofNames} so a rename reaches every spelling. */
    private static final String[] FIELD_FST_SND =
        {com.mezze.language.truffleir.NativeHofNames.PAIR_FST, com.mezze.language.truffleir.NativeHofNames.PAIR_SND};


    private NonEmptyListNatives() {}

    /**
     * Wrapper class for NonEmptyList - guarantees non-empty
     */
    public static final class MezzeNonEmptyList {
        private final Object[] elements;

        private MezzeNonEmptyList(Object[] elements) {
            if (elements.length == 0) {
                throw new IllegalArgumentException("NonEmptyList cannot be empty");
            }
            this.elements = elements;
        }

        public Object[] getElements() {
            return elements;
        }

        public int size() {
            return elements.length;
        }

        public Object head() {
            return elements[0];
        }

        public Object last() {
            return elements[elements.length - 1];
        }

        public MezzeList tail() {
            if (elements.length == 1) {
                return MezzeList.empty();
            }
            Object[] tail = new Object[elements.length - 1];
            System.arraycopy(elements, 1, tail, 0, elements.length - 1);
            return MezzeList.of(tail);
        }

        public Object get(int index) {
            return elements[index];
        }
    }

    // ==================== Factory Operations ====================

    @TruffleBoundary
    public static MezzeNonEmptyList singleton(Object element) {
        return new MezzeNonEmptyList(new Object[]{element});
    }

    @TruffleBoundary
    public static MezzeNonEmptyList fromHeadTail(Object head, MezzeList tail) {
        Object[] elements = new Object[tail.size() + 1];
        elements[0] = head;
        for (int i = 0; i < tail.size(); i++) {
            elements[i + 1] = tail.get(i);
        }
        return new MezzeNonEmptyList(elements);
    }

    @TruffleBoundary
    public static MezzeVariant fromList(MezzeList list) {
        if (list.isEmpty()) {
            // No-fatal: empty source is a normal, recoverable case.
            // `nel::from_list` returns Option (NonEmptyList 'a).
            return MezzeVariant.none();
        }
        Object[] elements = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            elements[i] = list.get(i);
        }
        return MezzeVariant.some(new MezzeNonEmptyList(elements));
    }

    @TruffleBoundary
    public static MezzeVariant fromArray(Object[] arr) {
        if (arr.length == 0) {
            // No-fatal: empty seq is a normal case; `nel::from_seq`
            // returns Option (NonEmptyList 'a).
            return MezzeVariant.none();
        }
        return MezzeVariant.some(new MezzeNonEmptyList(arr.clone()));
    }

    // ==================== Basic Operations ====================

    public static long length(MezzeNonEmptyList list) {
        return list.size();
    }

    public static Object head(MezzeNonEmptyList list) {
        return list.head();
    }

    public static MezzeList tail(MezzeNonEmptyList list) {
        return list.tail();
    }

    public static Object last(MezzeNonEmptyList list) {
        return list.last();
    }

    public static MezzeVariant get(MezzeNonEmptyList list, long index) {
        int idx = (int) index;
        if (idx < 0 || idx >= list.size()) {
            // No-fatal: out-of-range index is a normal case; `nel::get`
            // returns Option 'a.
            return MezzeVariant.none();
        }
        return MezzeVariant.some(list.get(idx));
    }

    // ==================== Construct Operations ====================

    @TruffleBoundary
    public static MezzeNonEmptyList cons(Object element, MezzeNonEmptyList list) {
        Object[] result = new Object[list.size() + 1];
        result[0] = element;
        System.arraycopy(list.getElements(), 0, result, 1, list.size());
        return new MezzeNonEmptyList(result);
    }

    @TruffleBoundary
    public static MezzeNonEmptyList append(MezzeNonEmptyList list1, MezzeNonEmptyList list2) {
        Object[] result = new Object[list1.size() + list2.size()];
        System.arraycopy(list1.getElements(), 0, result, 0, list1.size());
        System.arraycopy(list2.getElements(), 0, result, list1.size(), list2.size());
        return new MezzeNonEmptyList(result);
    }

    @TruffleBoundary
    public static MezzeNonEmptyList appendList(MezzeNonEmptyList list, MezzeList other) {
        Object[] result = new Object[list.size() + other.size()];
        System.arraycopy(list.getElements(), 0, result, 0, list.size());
        for (int i = 0; i < other.size(); i++) {
            result[list.size() + i] = other.get(i);
        }
        return new MezzeNonEmptyList(result);
    }

    /**
     * Prepend a list to an element to form a NonEmptyList.
     * {xs: List 'a, y: 'a} -> NonEmptyList 'a
     * Result: [xs[0], xs[1], ..., xs[n-1], y]
     */
    @TruffleBoundary
    public static MezzeNonEmptyList prependList(MezzeList list, Object element) {
        Object[] result = new Object[list.size() + 1];
        for (int i = 0; i < list.size(); i++) {
            result[i] = list.get(i);
        }
        result[list.size()] = element;
        return new MezzeNonEmptyList(result);
    }

    // ==================== Transform Operations ====================

    @TruffleBoundary
    public static MezzeNonEmptyList reverse(MezzeNonEmptyList list) {
        Object[] result = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[list.size() - 1 - i] = list.get(i);
        }
        return new MezzeNonEmptyList(result);
    }

    public static MezzeNonEmptyList map(MezzeNonEmptyList list, MezzeFunction mapper,
                                                                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(mapper, "x");
        Object[] result = new Object[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = ctx.call1(callNode, list.get(i));
        }
        return new MezzeNonEmptyList(result);
    }

    public static MezzeNonEmptyList flatMap(MezzeNonEmptyList list, MezzeFunction mapper,
                                                                                          com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(mapper, "x");
        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object mapped = ctx.call1(callNode, list.get(i));
            if (mapped instanceof MezzeNonEmptyList nel) {
                for (int j = 0; j < nel.size(); j++) {
                    result.add(nel.get(j));
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("[internal] flatMap on NonEmptyList produced an empty result — invariant violated");
        }
        return new MezzeNonEmptyList(result.toArray());
    }

    /**
     * Sum the integer elements, without a guest callback.
     *
     * Added because it was MISSING, and its absence was costing 8.9 ns per
     * element: `FoldToSumRewriter` turns a plain summing fold into the
     * collection's own `sum`, and every element-based collection had one except
     * this. Measured on `bench/cases/fuseall` kernel 3, a nonemptylist fold cost
     * 8 858 ps/elem where the identical deque fold cost 9 638 and dropped to
     * 1 726 once it had a `sum` to rewrite to.
     *
     * Reads Long/Integer and ignores anything else, matching
     * {@code DequeNatives.sum} and {@code SetNatives.sum}. The stdlib signature
     * is `{ xs: NonEmptyList Int } -> Int`, so a caller cannot reach the ignored
     * cases, and the rewriter additionally requires the folder's parameter slots
     * to be LONG before it will redirect to here.
     *
     * `@TruffleBoundary` is ONE crossing for the whole loop, not one per
     * element — the same shape the sibling sums use. What must never be
     * boundary-wrapped is a loop that calls BACK into guest code, which this
     * one does not.
     */
    @TruffleBoundary
    public static long sum(MezzeNonEmptyList list) {
        long total = 0;
        int n = list.size();
        for (int i = 0; i < n; i++) {
            Object elem = list.get(i);
            if (elem instanceof Long l) total += l;
            else if (elem instanceof Integer x) total += x;
        }
        return total;
    }

    public static Object foldLeft(MezzeNonEmptyList list, Object init, MezzeFunction folder,
                                                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(folder, "acc", "x");
        Object acc = init;
        for (int i = 0; i < list.size(); i++) {
            acc = ctx.call2(callNode, acc, list.get(i));
        }
        return acc;
    }

    public static Object foldRight(MezzeNonEmptyList list, Object init, MezzeFunction folder,
                                                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(folder, "x", "acc");
        Object acc = init;
        for (int i = list.size() - 1; i >= 0; i--) {
            acc = ctx.call2(callNode, list.get(i), acc);
        }
        return acc;
    }

    public static Object reduce(MezzeNonEmptyList list, MezzeFunction reducer,
                                                                  com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(reducer, "acc", "x");
        Object acc = list.head();
        for (int i = 1; i < list.size(); i++) {
            acc = ctx.call2(callNode, acc, list.get(i));
        }
        return acc;
    }


    public static boolean any(MezzeNonEmptyList list, MezzeFunction predicate,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "x");
        for (int i = 0; i < list.size(); i++) {
            Object result = ctx.call1(callNode, list.get(i));
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        }
        return false;
    }

    public static boolean all(MezzeNonEmptyList list, MezzeFunction predicate,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare1(predicate, "x");
        for (int i = 0; i < list.size(); i++) {
            Object result = ctx.call1(callNode, list.get(i));
            if (!(result instanceof Boolean) || !(Boolean) result) {
                return false;
            }
        }
        return true;
    }

    // ==================== Intersperse ====================

    @TruffleBoundary
    public static MezzeNonEmptyList intersperse(MezzeNonEmptyList list, Object separator) {
        if (list.size() == 1) return list;

        Object[] result = new Object[list.size() * 2 - 1];
        for (int i = 0; i < list.size(); i++) {
            result[i * 2] = list.get(i);
            if (i < list.size() - 1) {
                result[i * 2 + 1] = separator;
            }
        }
        return new MezzeNonEmptyList(result);
    }

    // ==================== Zip ====================

    @TruffleBoundary
    public static MezzeNonEmptyList zip(MezzeNonEmptyList list1, MezzeNonEmptyList list2) {
        int len = Math.min(list1.size(), list2.size());
        Object[] result = new Object[len];
        for (int i = 0; i < len; i++) {
            result[i] = MezzeRecord.fromFields(
                FIELD_FST_SND,
                new Object[]{list1.get(i), list2.get(i)}
            );
        }
        return new MezzeNonEmptyList(result);
    }

    // ==================== Sort Operations ====================

    /**
     * NATURAL ORDER, no comparator — the form {@code TypedASTCompiler} rewrites
     * {@code __native_nel_sort_by_ord} to when the element is DIRECTLY
     * comparable. Reached only through that rewrite.
     *
     * <p>THIS NATIVE WAS DEAD AND WRONG, the third in that family after
     * {@code __native_seq_sort} and {@code __native_list_sort}. It was
     * registered in {@code NativeDispatch}, referenced from nowhere, and its
     * comparator was an instanceof chain ending {@code return 0} — so a
     * NonEmptyList of records would have come back in its original order,
     * reported as sorted. Nothing called it, so nothing was broken; it was a
     * trap for whoever wired it up, which is a thing that nearly happened while
     * triaging the dead natives for speed.
     *
     * <p>A {@code MezzeNonEmptyList} holds {@code Object[]} of BOXED elements,
     * so unlike {@code seq::sort} there is no primitive tier to drop to and the
     * best available is {@code Arrays.sort(Object[])} through {@code
     * Comparable}. The elements are CHECKED rather than assumed, so a type the
     * rewrite should have declined fails by naming the broken promise instead
     * of answering 0 for every pair or throwing from inside TimSort.
     */
    @TruffleBoundary
    public static MezzeNonEmptyList sort(MezzeNonEmptyList list) {
        Object[] arr = list.getElements().clone();
        if (arr.length <= 1) return list;
        for (Object elem : arr) {
            if (!(elem instanceof Long || elem instanceof Double || elem instanceof String)) {
                throw new IllegalStateException(
                    "__native_nel_sort reached a " + (elem == null ? "null" : elem.getClass().getName())
                    + " element: this native sorts only the directly comparable elements"
                    + " (Int, Float, Str). Anything else must have been rewritten to"
                    + " __native_nel_sort_by_ord by TypedASTCompiler, so this is a"
                    + " compiler bug, not a program error.");
            }
        }
        java.util.Arrays.sort(arr);
        return new MezzeNonEmptyList(arr);
    }

    /**
     * Sorts by the element's own {@code Ord}, handed in as a `le` callback.
     *
     * <p>`le` answers True when `it` should come before `r`. One guest call per
     * comparison, which is what this path has always cost — the point of the
     * pair is that {@link #sort} replaces it when the element is proved
     * directly comparable, and this remains the route for everything else.
     */
    public static MezzeNonEmptyList sortByOrd(MezzeNonEmptyList list, MezzeFunction leFn,
                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object[] arr = list.getElements().clone();
        if (arr.length <= 1) return list;
        var ctx = ScalarCall.prepare2(leFn, "it", "r");
        java.util.Arrays.sort(arr, (a, b) -> ctx.callBool(callNode, a, b) ? -1 : 1);
        return new MezzeNonEmptyList(arr);
    }

    /**
     * Sort by comparator function: {l: 'a, r: 'a} -> Int
     * Returns negative if l < r, 0 if equal, positive if l > r
     */
    public static MezzeNonEmptyList sortBy(MezzeNonEmptyList list, MezzeFunction cmpFn,
                                                                                        com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var ctx = ScalarCall.prepare2(cmpFn, "l", "r");
        Object[] arr = list.getElements().clone();
        // Through `callCmp`, as `SeqNatives.sortBy` does. The hand-rolled unbox
        // this replaces ended `return 0` for a non-Long and, worse, narrowed
        // with `intValue()`: a comparator returning 2^31 TRUNCATED to -2^31 and
        // ordered the pair backwards. Latent rather than live — every stdlib
        // `sort_by` wraps a Bool comparator to exactly -1 or 1, and guest code
        // cannot name a `__native_*` directly (checked: V0702) — so this is the
        // trap removed, not a user-visible bug fixed. `callCmp` takes signum.
        java.util.Arrays.sort(arr, (a, b) -> ctx.callCmp(callNode, a, b));
        return new MezzeNonEmptyList(arr);
    }

    // ==================== Conversion ====================

    @TruffleBoundary
    public static MezzeList toList(MezzeNonEmptyList list) {
        return MezzeList.of(list.getElements());
    }

    public static Object[] toArray(MezzeNonEmptyList list) {
        return list.getElements().clone();
    }

    // ==================== Equality & Comparison ====================

    @TruffleBoundary
    public static boolean equals(MezzeNonEmptyList list1, MezzeNonEmptyList list2, MezzeFunction eqFn,
                                 com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        if (list1.size() != list2.size()) return false;
        var eq = ScalarCall.prepare2(eqFn, "a", "b");
        for (int i = 0; i < list1.size(); i++) {
            if (!eq.callEq(callNode, list1.get(i), list2.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Lexicographic comparison returning -1, 0, or 1.
     */
    @TruffleBoundary
    public static long compare(MezzeNonEmptyList list1, MezzeNonEmptyList list2, MezzeFunction cmpFn,
                               com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        var cmpCall = ScalarCall.prepare2(cmpFn, "a", "b");
        int minLen = Math.min(list1.size(), list2.size());
        for (int i = 0; i < minLen; i++) {
            int cmp = cmpCall.callCmp(callNode, list1.get(i), list2.get(i));
            if (cmp != 0) {
                return cmp < 0 ? -1L : 1L;
            }
        }
        return Integer.compare(list1.size(), list2.size());
    }


}
