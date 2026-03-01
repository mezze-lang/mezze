package com.mezze.interpreter.v2.nodes.builtin.collections;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.TreeSet;

/**
 * Native SortedSet operations - ULTRA PERFORMANT.
 *
 * SortedSet in V2 uses TreeSet for sorted iteration order.
 * All operations are immutable (return new sets).
 */
public final class SortedSetNatives {

    private SortedSetNatives() {}

    /**
     * Wrapper class for SortedSet
     */
    public static final class MezzeSortedSet {
        private final TreeSet<Object> set;

        public MezzeSortedSet() {
            this.set = new TreeSet<>(SortedSetNatives::compare);
        }

        private MezzeSortedSet(TreeSet<Object> set) {
            this.set = set;
        }

        public TreeSet<Object> getSet() {
            return set;
        }

        public int size() {
            return set.size();
        }

        public boolean isEmpty() {
            return set.isEmpty();
        }

        @TruffleBoundary
        public boolean contains(Object element) {
            return set.contains(element);
        }

        @TruffleBoundary
        public MezzeSortedSet add(Object element) {
            TreeSet<Object> newSet = new TreeSet<>(set);
            newSet.add(element);
            return new MezzeSortedSet(newSet);
        }

        @TruffleBoundary
        public MezzeSortedSet remove(Object element) {
            TreeSet<Object> newSet = new TreeSet<>(set);
            newSet.remove(element);
            return new MezzeSortedSet(newSet);
        }

        @TruffleBoundary
        public Object[] toArray() {
            return set.toArray();
        }
    }

    // ==================== Factory Operations ====================

    public static MezzeSortedSet empty() {
        return new MezzeSortedSet();
    }

    @TruffleBoundary
    public static MezzeSortedSet singleton(Object element) {
        MezzeSortedSet set = new MezzeSortedSet();
        return set.add(element);
    }

    @TruffleBoundary
    public static MezzeSortedSet fromList(MezzeList list) {
        TreeSet<Object> set = new TreeSet<>(SortedSetNatives::compare);
        for (int i = 0; i < list.size(); i++) {
            set.add(list.get(i));
        }
        return new MezzeSortedSet(set);
    }

    @TruffleBoundary
    public static MezzeSortedSet fromSet(SetNatives.MezzeSet set) {
        TreeSet<Object> treeSet = new TreeSet<>(SortedSetNatives::compare);
        treeSet.addAll(java.util.Arrays.asList(set.toArray()));
        return new MezzeSortedSet(treeSet);
    }

    // ==================== Basic Operations ====================

    public static long size(MezzeSortedSet set) {
        return set.size();
    }

    public static boolean isEmpty(MezzeSortedSet set) {
        return set.isEmpty();
    }

    public static boolean contains(MezzeSortedSet set, Object element) {
        return set.contains(element);
    }

    public static MezzeSortedSet add(MezzeSortedSet set, Object element) {
        return set.add(element);
    }

    public static MezzeSortedSet remove(MezzeSortedSet set, Object element) {
        return set.remove(element);
    }

    // ==================== Min/Max Operations ====================

    @TruffleBoundary
    public static MezzeVariant min(MezzeSortedSet set) {
        if (set.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(set.getSet().first());
    }

    @TruffleBoundary
    public static MezzeVariant max(MezzeSortedSet set) {
        if (set.isEmpty()) {
            return MezzeVariant.none();
        }
        return MezzeVariant.some(set.getSet().last());
    }

    // ==================== Set Operations ====================

    @TruffleBoundary
    public static MezzeSortedSet union(MezzeSortedSet set1, MezzeSortedSet set2) {
        TreeSet<Object> result = new TreeSet<>(set1.getSet());
        result.addAll(set2.getSet());
        return new MezzeSortedSet(result);
    }

    @TruffleBoundary
    public static MezzeSortedSet intersect(MezzeSortedSet set1, MezzeSortedSet set2) {
        TreeSet<Object> result = new TreeSet<>(set1.getSet());
        result.retainAll(set2.getSet());
        return new MezzeSortedSet(result);
    }

    @TruffleBoundary
    public static MezzeSortedSet difference(MezzeSortedSet set1, MezzeSortedSet set2) {
        TreeSet<Object> result = new TreeSet<>(set1.getSet());
        result.removeAll(set2.getSet());
        return new MezzeSortedSet(result);
    }

    @TruffleBoundary
    public static boolean equals(MezzeSortedSet set1, MezzeSortedSet set2) {
        return set1.getSet().equals(set2.getSet());
    }

    // ==================== Conversion ====================

    @TruffleBoundary
    public static MezzeList toList(MezzeSortedSet set) {
        return MezzeList.fromObjects(set.toArray());
    }

    @TruffleBoundary
    public static SetNatives.MezzeSet toSet(MezzeSortedSet sortedSet) {
        return SetNatives.fromArray(sortedSet.toArray());
    }

    // ==================== Comparison Helper ====================

    @TruffleBoundary
    @SuppressWarnings("unchecked")
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
        if (a instanceof Boolean && b instanceof Boolean) {
            return Boolean.compare((Boolean) a, (Boolean) b);
        }
        // Fallback: compare hash codes
        return Integer.compare(System.identityHashCode(a), System.identityHashCode(b));
    }
}
