package com.mezze.interpreter.v2.nodes.builtin.collections;

import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.HashSet;
import java.util.LinkedHashSet;

/**
 * Native Set operations - ULTRA PERFORMANT.
 *
 * Set in V2 uses LinkedHashSet for predictable iteration order.
 * All operations are immutable (return new sets).
 */
public final class SetNatives {

    private SetNatives() {}

    /**
     * Wrapper class for Set to enable persistent semantics
     */
    public static final class MezzeSet {
        private final LinkedHashSet<Object> set;

        public MezzeSet() {
            this.set = new LinkedHashSet<>();
        }

        private MezzeSet(LinkedHashSet<Object> set) {
            this.set = set;
        }

        public LinkedHashSet<Object> getSet() {
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
        public MezzeSet insert(Object element) {
            LinkedHashSet<Object> newSet = new LinkedHashSet<>(set);
            newSet.add(element);
            return new MezzeSet(newSet);
        }

        @TruffleBoundary
        public MezzeSet remove(Object element) {
            LinkedHashSet<Object> newSet = new LinkedHashSet<>(set);
            newSet.remove(element);
            return new MezzeSet(newSet);
        }

        @TruffleBoundary
        public Object[] toArray() {
            return set.toArray();
        }
    }

    // ==================== Factory Operations ====================

    public static MezzeSet empty() {
        return new MezzeSet();
    }

    @TruffleBoundary
    public static MezzeSet fromArray(Object[] arr) {
        LinkedHashSet<Object> set = new LinkedHashSet<>();
        for (Object element : arr) {
            set.add(element);
        }
        return new MezzeSet(set);
    }

    @TruffleBoundary
    public static MezzeSet fromList(MezzeList list) {
        LinkedHashSet<Object> set = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            set.add(list.get(i));
        }
        return new MezzeSet(set);
    }

    // ==================== Basic Operations ====================

    public static long size(MezzeSet set) {
        return set.size();
    }

    public static boolean isEmpty(MezzeSet set) {
        return set.isEmpty();
    }

    public static boolean contains(MezzeSet set, Object element) {
        return set.contains(element);
    }

    public static MezzeSet insert(MezzeSet set, Object element) {
        return set.insert(element);
    }

    public static MezzeSet remove(MezzeSet set, Object element) {
        return set.remove(element);
    }

    @TruffleBoundary
    public static MezzeSet insertAll(MezzeSet set, Object[] elements) {
        LinkedHashSet<Object> newSet = new LinkedHashSet<>(set.getSet());
        for (Object element : elements) {
            newSet.add(element);
        }
        return new MezzeSet(newSet);
    }

    @TruffleBoundary
    public static MezzeSet removeAll(MezzeSet set, Object[] elements) {
        LinkedHashSet<Object> newSet = new LinkedHashSet<>(set.getSet());
        for (Object element : elements) {
            newSet.remove(element);
        }
        return new MezzeSet(newSet);
    }

    // ==================== Set Operations ====================

    @TruffleBoundary
    public static MezzeSet union(MezzeSet set1, MezzeSet set2) {
        LinkedHashSet<Object> result = new LinkedHashSet<>(set1.getSet());
        result.addAll(set2.getSet());
        return new MezzeSet(result);
    }

    @TruffleBoundary
    public static MezzeSet intersection(MezzeSet set1, MezzeSet set2) {
        LinkedHashSet<Object> result = new LinkedHashSet<>(set1.getSet());
        result.retainAll(set2.getSet());
        return new MezzeSet(result);
    }

    @TruffleBoundary
    public static MezzeSet difference(MezzeSet set1, MezzeSet set2) {
        LinkedHashSet<Object> result = new LinkedHashSet<>(set1.getSet());
        result.removeAll(set2.getSet());
        return new MezzeSet(result);
    }

    @TruffleBoundary
    public static boolean isSubset(MezzeSet set1, MezzeSet set2) {
        return set2.getSet().containsAll(set1.getSet());
    }

    @TruffleBoundary
    public static boolean equals(MezzeSet set1, MezzeSet set2) {
        return set1.getSet().equals(set2.getSet());
    }

    // ==================== Transform Operations ====================

    @TruffleBoundary
    public static MezzeSet filter(MezzeSet set, MezzeFunction predicate,
                                                                      com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashSet<Object> result = new LinkedHashSet<>();
        for (Object element : set.getSet()) {
            Object res = callNode.call(predicate.getCallTarget(),
                prepareArgs(predicate, element));
            if (res instanceof Boolean && (Boolean) res) {
                result.add(element);
            }
        }
        return new MezzeSet(result);
    }

    @TruffleBoundary
    public static MezzeSet map(MezzeSet set, MezzeFunction mapper,
                                                                com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        LinkedHashSet<Object> result = new LinkedHashSet<>();
        for (Object element : set.getSet()) {
            Object mapped = callNode.call(mapper.getCallTarget(),
                prepareArgs(mapper, element));
            result.add(mapped);
        }
        return new MezzeSet(result);
    }

    @TruffleBoundary
    public static Object fold(MezzeSet set, Object init, MezzeFunction folder,
                                                              com.oracle.truffle.api.nodes.IndirectCallNode callNode) {
        Object acc = init;
        for (Object element : set.getSet()) {
            acc = callNode.call(folder.getCallTarget(),
                prepareArgsTwo(folder, acc, element));
        }
        return acc;
    }

    // ==================== Conversion ====================

    public static Object[] toArray(MezzeSet set) {
        return set.toArray();
    }

    @TruffleBoundary
    public static MezzeList toList(MezzeSet set) {
        return MezzeList.fromObjects(set.toArray());
    }

    // ==================== Helper Methods ====================

    private static Object[] prepareArgs(MezzeFunction fn, Object arg) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            new String[]{"x"}, new Object[]{arg}
        );
        return args;
    }

    private static Object[] prepareArgsTwo(MezzeFunction fn, Object arg1, Object arg2) {
        Object[] closureValues = fn.getClosureValues();
        Object[] args = new Object[closureValues.length + 1];
        System.arraycopy(closureValues, 0, args, 0, closureValues.length);
        args[closureValues.length] = MezzeRecord.fromFields(
            new String[]{"acc", "x"}, new Object[]{arg1, arg2}
        );
        return args;
    }
}
