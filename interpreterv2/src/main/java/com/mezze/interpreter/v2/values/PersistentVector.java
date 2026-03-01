package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * PERSISTENT VECTOR with structural sharing.
 *
 * This is a copy-on-write vector implementation that shares structure
 * between versions, dramatically reducing allocation for append/update operations.
 *
 * Based on Clojure's PersistentVector design:
 * - Uses a 32-way branching tree (for cache-friendly access)
 * - Updates copy only the path from root to modified leaf
 * - O(log32 n) ≈ O(1) for practical sizes for most operations
 *
 * Benefits:
 * - append() shares most of the tree with the original
 * - update() shares all unmodified branches
 * - Memory-efficient for functional programming patterns
 *
 * Example:
 * Original: [1, 2, 3, 4, 5]
 * Updated:  [1, 2, 99, 4, 5]  -- shares nodes for [1,2] and [4,5]
 */
@ExportLibrary(InteropLibrary.class)
public final class PersistentVector implements TruffleObject {

    // Branching factor: 32 for cache-friendly traversal
    private static final int BITS = 5;
    private static final int WIDTH = 1 << BITS;  // 32
    private static final int MASK = WIDTH - 1;   // 31

    @CompilationFinal
    private final int count;

    @CompilationFinal
    private final int shift;

    @CompilationFinal(dimensions = 1)
    private final Object[] root;

    @CompilationFinal(dimensions = 1)
    private final Object[] tail;

    // Shared empty vector singleton
    private static final Object[] EMPTY_ARRAY = new Object[0];
    private static final PersistentVector EMPTY = new PersistentVector(0, BITS, EMPTY_ARRAY, EMPTY_ARRAY);

    private PersistentVector(int count, int shift, Object[] root, Object[] tail) {
        this.count = count;
        this.shift = shift;
        this.root = root;
        this.tail = tail;
    }

    /**
     * Create an empty persistent vector.
     */
    public static PersistentVector empty() {
        return EMPTY;
    }

    /**
     * Create a vector from an array.
     */
    @TruffleBoundary
    public static PersistentVector fromArray(Object[] items) {
        PersistentVector v = EMPTY;
        for (Object item : items) {
            v = v.append(item);
        }
        return v;
    }

    /**
     * Get the number of elements.
     */
    public int size() {
        return count;
    }

    /**
     * Check if empty.
     */
    public boolean isEmpty() {
        return count == 0;
    }

    /**
     * Get element at index.
     * O(log32 n) ≈ O(1) for practical sizes.
     */
    public Object get(int index) {
        if (index < 0 || index >= count) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
        }

        // Tail optimization: last chunk is stored flat
        if (index >= tailOffset()) {
            return tail[index - tailOffset()];
        }

        // Tree traversal
        Object[] node = root;
        for (int level = shift; level > 0; level -= BITS) {
            node = (Object[]) node[(index >>> level) & MASK];
        }
        return node[index & MASK];
    }

    /**
     * Append an element.
     * O(log32 n) - shares structure with original.
     */
    @TruffleBoundary
    public PersistentVector append(Object value) {
        // Room in tail?
        if (count - tailOffset() < WIDTH) {
            Object[] newTail = new Object[tail.length + 1];
            System.arraycopy(tail, 0, newTail, 0, tail.length);
            newTail[tail.length] = value;
            return new PersistentVector(count + 1, shift, root, newTail);
        }

        // Tail is full, push it into tree
        Object[] newTail = new Object[] { value };
        return pushTail(count, shift, root, tail, newTail);
    }

    private PersistentVector pushTail(int cnt, int level, Object[] parent, Object[] tailNode, Object[] newTail) {
        int subIndex = ((cnt - 1) >>> level) & MASK;
        Object[] ret = parent.clone();

        if (level == BITS) {
            // Leaf level
            ret[subIndex] = tailNode;
        } else {
            Object[] child = (Object[]) parent[subIndex];
            if (child != null) {
                PersistentVector pushed = pushTail(cnt, level - BITS, child, tailNode, newTail);
                ret[subIndex] = pushed.root;
                return new PersistentVector(cnt + 1, shift, ret, newTail);
            } else {
                ret[subIndex] = newPath(level - BITS, tailNode);
            }
        }

        // Need to grow tree?
        if ((cnt >>> BITS) > (1 << shift)) {
            Object[] newRoot = new Object[] { root, newPath(shift, tailNode) };
            return new PersistentVector(cnt + 1, shift + BITS, newRoot, newTail);
        }

        return new PersistentVector(cnt + 1, shift, ret, newTail);
    }

    private static Object[] newPath(int level, Object[] node) {
        if (level == 0) {
            return node;
        }
        Object[] ret = new Object[WIDTH];
        ret[0] = newPath(level - BITS, node);
        return ret;
    }

    /**
     * Update element at index.
     * O(log32 n) - shares structure for unmodified branches.
     */
    @TruffleBoundary
    public PersistentVector update(int index, Object value) {
        if (index < 0 || index >= count) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + count);
        }

        // Tail optimization
        if (index >= tailOffset()) {
            Object[] newTail = tail.clone();
            newTail[index - tailOffset()] = value;
            return new PersistentVector(count, shift, root, newTail);
        }

        // Tree update - only copies the path to the modified leaf
        return new PersistentVector(count, shift, doUpdate(shift, root, index, value), tail);
    }

    private Object[] doUpdate(int level, Object[] node, int index, Object value) {
        Object[] ret = node.clone();
        if (level == 0) {
            ret[index & MASK] = value;
        } else {
            int subIndex = (index >>> level) & MASK;
            ret[subIndex] = doUpdate(level - BITS, (Object[]) node[subIndex], index, value);
        }
        return ret;
    }

    private int tailOffset() {
        if (count < WIDTH) {
            return 0;
        }
        return ((count - 1) >>> BITS) << BITS;
    }

    /**
     * Convert to array (for iteration).
     */
    @TruffleBoundary
    public Object[] toArray() {
        Object[] result = new Object[count];
        for (int i = 0; i < count; i++) {
            result[i] = get(i);
        }
        return result;
    }

    // InteropLibrary exports
    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return count;
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < count;
    }

    @ExportMessage
    Object readArrayElement(long index) {
        return get((int) index);
    }

    @Override
    @TruffleBoundary
    public String toString() {
        if (count == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append(get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    @TruffleBoundary
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PersistentVector that)) return false;
        if (count != that.count) return false;

        for (int i = 0; i < count; i++) {
            if (!java.util.Objects.equals(get(i), that.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    @TruffleBoundary
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < count; i++) {
            result = 31 * result + java.util.Objects.hashCode(get(i));
        }
        return result;
    }
}
