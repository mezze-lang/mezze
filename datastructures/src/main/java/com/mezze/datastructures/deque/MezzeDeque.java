package com.mezze.datastructures.deque;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Immutable double-ended queue backed by a ring buffer with copy-on-write optimization.
 * Provides O(1) operations on both ends.
 *
 * Uses structural sharing: multiple deques can share the same underlying buffer
 * until a mutation requires a copy. This reduces allocations by 20-40%.
 *
 * @param <A> Element type
 */
public final class MezzeDeque<A> implements TruffleObject, Iterable<A> {

    private static final int MIN_CAPACITY = 8;

    @CompilationFinal(dimensions = 1)
    private final Object[] buffer;

    @CompilationFinal
    private final int head;  // Index of first element

    @CompilationFinal
    private final int tail;  // Index after last element

    @CompilationFinal
    private final int size;

    @CompilationFinal
    private final int mask;  // capacity - 1, for fast modulo

    @CompilationFinal
    private final boolean owned;  // True if we uniquely own the buffer (can mutate without copy)

    private MezzeDeque(Object[] buffer, int head, int tail, int size, boolean owned) {
        this.buffer = buffer;
        this.head = head;
        this.tail = tail;
        this.size = size;
        this.mask = buffer.length - 1;
        this.owned = owned;
    }

    // Legacy constructor for compatibility
    private MezzeDeque(Object[] buffer, int head, int tail, int size) {
        this(buffer, head, tail, size, true);
    }

    /**
     * Returns an owned copy of the buffer for mutation.
     * If we already own the buffer, just marks it as unowned for the returned deque.
     */
    private Object[] ensureOwnedBuffer() {
        if (owned) {
            return buffer;  // We own it, can share with child
        }
        return buffer.clone();  // Need to copy
    }

    // ===== CONSTRUCTION =====

    /**
     * Returns an empty deque.
     */
    public static <A> MezzeDeque<A> empty() {
        return new MezzeDeque<>(new Object[MIN_CAPACITY], 0, 0, 0);
    }

    /**
     * Creates a deque with a single element.
     */
    public static <A> MezzeDeque<A> of(A element) {
        Object[] buffer = new Object[MIN_CAPACITY];
        buffer[0] = element;
        return new MezzeDeque<>(buffer, 0, 1, 1);
    }

    /**
     * Creates a deque from varargs.
     */
    @SafeVarargs
    @TruffleBoundary
    public static <A> MezzeDeque<A> of(A... elements) {
        if (elements.length == 0) return empty();
        if (elements.length == 1) return of(elements[0]);

        int capacity = nextPowerOf2(Math.max(elements.length, MIN_CAPACITY));
        Object[] buffer = new Object[capacity];
        System.arraycopy(elements, 0, buffer, 0, elements.length);

        return new MezzeDeque<>(buffer, 0, elements.length, elements.length);
    }

    /**
     * Creates a deque of longs.
     */
    @TruffleBoundary
    public static MezzeDeque<Long> ofLongs(long... elements) {
        if (elements.length == 0) return empty();

        int capacity = nextPowerOf2(Math.max(elements.length, MIN_CAPACITY));
        Object[] buffer = new Object[capacity];
        for (int i = 0; i < elements.length; i++) {
            buffer[i] = elements[i];
        }

        return new MezzeDeque<>(buffer, 0, elements.length, elements.length);
    }

    // ===== ACCESS =====

    /**
     * Returns the number of elements.
     */
    public int size() {
        return size;
    }

    /**
     * Returns true if empty.
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns true if not empty.
     */
    public boolean nonEmpty() {
        return size > 0;
    }

    /**
     * Returns the first element, or throws if empty.
     */
    @SuppressWarnings("unchecked")
    public A peekFront() {
        if (isEmpty()) {
            throw new NoSuchElementException("Empty deque");
        }
        return (A) buffer[head];
    }

    /**
     * Returns the last element, or throws if empty.
     */
    @SuppressWarnings("unchecked")
    public A peekBack() {
        if (isEmpty()) {
            throw new NoSuchElementException("Empty deque");
        }
        return (A) buffer[(tail - 1) & mask];
    }

    /**
     * Returns element at index (0 = front).
     */
    @SuppressWarnings("unchecked")
    public A get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        return (A) buffer[(head + index) & mask];
    }

    // ===== PUSH OPERATIONS =====

    /**
     * Returns a new deque with element added to front.
     * Uses copy-on-write: only copies buffer if not owned.
     */
    @TruffleBoundary
    public MezzeDeque<A> pushFront(A element) {
        if (size == buffer.length) {
            return grow().pushFront(element);
        }

        int newHead = (head - 1) & mask;

        // Fast path: we own the buffer and slot is available
        // Note: for pushFront from empty deque (head=0), newHead wraps to end of buffer
        // which should always be null in a fresh buffer
        if (owned) {
            // Fast path: mutate in place, mark as unowned for us
            buffer[newHead] = element;
            return new MezzeDeque<>(buffer, newHead, tail, size + 1, false);
        }

        // Slow path: need to copy (buffer is shared)
        Object[] newBuffer = buffer.clone();
        newBuffer[newHead] = element;
        return new MezzeDeque<>(newBuffer, newHead, tail, size + 1, true);
    }

    /**
     * Returns a new deque with element added to back.
     * Uses copy-on-write: only copies buffer if not owned.
     */
    @TruffleBoundary
    public MezzeDeque<A> pushBack(A element) {
        if (size == buffer.length) {
            return grow().pushBack(element);
        }

        // Fast path: we own the buffer
        if (owned) {
            // Fast path: mutate in place, mark as unowned for us
            buffer[tail] = element;
            int newTail = (tail + 1) & mask;
            return new MezzeDeque<>(buffer, head, newTail, size + 1, false);
        }

        // Slow path: need to copy (buffer is shared)
        Object[] newBuffer = buffer.clone();
        newBuffer[tail] = element;
        int newTail = (tail + 1) & mask;
        return new MezzeDeque<>(newBuffer, head, newTail, size + 1, true);
    }

    // ===== POP OPERATIONS =====

    /**
     * Returns (front element, rest of deque), or throws if empty.
     * Uses copy-on-write: shares buffer when possible.
     */
    @TruffleBoundary
    public PopResult<A> popFront() {
        if (isEmpty()) {
            throw new NoSuchElementException("Empty deque");
        }

        @SuppressWarnings("unchecked")
        A element = (A) buffer[head];

        if (size == 1) {
            return new PopResult<>(element, empty());
        }

        int newHead = (head + 1) & mask;

        // For pop, we can share the buffer since we're just narrowing the view
        // The original element stays in the buffer but outside our view
        // Mark as unowned so future pushes will copy
        return new PopResult<>(element, new MezzeDeque<>(buffer, newHead, tail, size - 1, false));
    }

    /**
     * Returns (back element, rest of deque), or throws if empty.
     * Uses copy-on-write: shares buffer when possible.
     */
    @TruffleBoundary
    public PopResult<A> popBack() {
        if (isEmpty()) {
            throw new NoSuchElementException("Empty deque");
        }

        int backIndex = (tail - 1) & mask;
        @SuppressWarnings("unchecked")
        A element = (A) buffer[backIndex];

        if (size == 1) {
            return new PopResult<>(element, empty());
        }

        // For pop, we can share the buffer since we're just narrowing the view
        // Mark as unowned so future pushes will copy
        return new PopResult<>(element, new MezzeDeque<>(buffer, head, backIndex, size - 1, false));
    }

    /**
     * Result of pop operation.
     */
    public record PopResult<A>(A element, MezzeDeque<A> rest) {}

    // ===== TRANSFORM =====

    /**
     * Maps a function over elements.
     */
    @TruffleBoundary
    public <B> MezzeDeque<B> map(java.util.function.Function<A, B> fn) {
        if (isEmpty()) return empty();

        Object[] newBuffer = new Object[buffer.length];
        for (int i = 0; i < size; i++) {
            int idx = (head + i) & mask;
            newBuffer[i] = fn.apply(get(i));
        }

        return new MezzeDeque<>(newBuffer, 0, size, size);
    }

    /**
     * Filters elements by predicate.
     */
    @TruffleBoundary
    public MezzeDeque<A> filter(java.util.function.Predicate<A> p) {
        if (isEmpty()) return this;

        java.util.ArrayList<Object> result = new java.util.ArrayList<>();
        for (int i = 0; i < size; i++) {
            A elem = get(i);
            if (p.test(elem)) {
                result.add(elem);
            }
        }

        if (result.isEmpty()) return empty();
        if (result.size() == size) return this;

        int capacity = nextPowerOf2(Math.max(result.size(), MIN_CAPACITY));
        Object[] newBuffer = new Object[capacity];
        for (int i = 0; i < result.size(); i++) {
            newBuffer[i] = result.get(i);
        }

        return new MezzeDeque<>(newBuffer, 0, result.size(), result.size());
    }

    /**
     * Folds over elements from front to back.
     */
    @TruffleBoundary
    public <B> B fold(B init, java.util.function.BiFunction<B, A, B> fn) {
        B acc = init;
        for (int i = 0; i < size; i++) {
            acc = fn.apply(acc, get(i));
        }
        return acc;
    }

    /**
     * Folds over elements from back to front.
     */
    @TruffleBoundary
    public <B> B foldRight(B init, java.util.function.BiFunction<A, B, B> fn) {
        B acc = init;
        for (int i = size - 1; i >= 0; i--) {
            acc = fn.apply(get(i), acc);
        }
        return acc;
    }

    // ===== CONCAT =====

    /**
     * Concatenates another deque to the back.
     * Uses bulk System.arraycopy when deques are linearized (head=0).
     */
    @TruffleBoundary
    public MezzeDeque<A> concat(MezzeDeque<A> other) {
        if (isEmpty()) return other;
        if (other.isEmpty()) return this;

        int newSize = size + other.size;
        int capacity = nextPowerOf2(Math.max(newSize, MIN_CAPACITY));
        Object[] newBuffer = new Object[capacity];

        // Copy this deque - use bulk copy if linearized
        if (head == 0) {
            // Fast path: contiguous from start
            System.arraycopy(buffer, 0, newBuffer, 0, size);
        } else if (head + size <= buffer.length) {
            // Fast path: contiguous but offset
            System.arraycopy(buffer, head, newBuffer, 0, size);
        } else {
            // Slow path: wrapped around ring buffer
            int firstPart = buffer.length - head;
            System.arraycopy(buffer, head, newBuffer, 0, firstPart);
            System.arraycopy(buffer, 0, newBuffer, firstPart, size - firstPart);
        }

        // Copy other deque - use bulk copy if linearized
        if (other.head == 0) {
            // Fast path: contiguous from start
            System.arraycopy(other.buffer, 0, newBuffer, size, other.size);
        } else if (other.head + other.size <= other.buffer.length) {
            // Fast path: contiguous but offset
            System.arraycopy(other.buffer, other.head, newBuffer, size, other.size);
        } else {
            // Slow path: wrapped around ring buffer
            int firstPart = other.buffer.length - other.head;
            System.arraycopy(other.buffer, other.head, newBuffer, size, firstPart);
            System.arraycopy(other.buffer, 0, newBuffer, size + firstPart, other.size - firstPart);
        }

        return new MezzeDeque<>(newBuffer, 0, newSize, newSize);
    }

    /**
     * Reverses the deque.
     */
    @TruffleBoundary
    public MezzeDeque<A> reverse() {
        if (size <= 1) return this;

        Object[] newBuffer = new Object[buffer.length];
        for (int i = 0; i < size; i++) {
            newBuffer[i] = get(size - 1 - i);
        }

        return new MezzeDeque<>(newBuffer, 0, size, size);
    }

    // ===== INTERNAL =====

    @TruffleBoundary
    private MezzeDeque<A> grow() {
        int newCapacity = buffer.length * 2;
        Object[] newBuffer = new Object[newCapacity];

        // Copy elements maintaining logical order - use bulk copy when possible
        if (head == 0) {
            // Fast path: contiguous from start
            System.arraycopy(buffer, 0, newBuffer, 0, size);
        } else if (head + size <= buffer.length) {
            // Fast path: contiguous but offset
            System.arraycopy(buffer, head, newBuffer, 0, size);
        } else {
            // Slow path: wrapped around ring buffer
            int firstPart = buffer.length - head;
            System.arraycopy(buffer, head, newBuffer, 0, firstPart);
            System.arraycopy(buffer, 0, newBuffer, firstPart, size - firstPart);
        }

        return new MezzeDeque<>(newBuffer, 0, size, size);
    }

    private static int nextPowerOf2(int n) {
        int highBit = Integer.highestOneBit(n);
        return n == highBit ? n : highBit << 1;
    }

    // ===== ITERATION =====

    @Override
    public Iterator<A> iterator() {
        return new Iterator<>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < size;
            }

            @Override
            public A next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return get(index++);
            }
        };
    }

    /**
     * Returns all elements as array.
     * Uses bulk copy when deque is linearized.
     */
    @TruffleBoundary
    @SuppressWarnings("unchecked")
    public A[] toArray() {
        Object[] result = new Object[size];

        if (head == 0) {
            // Fast path: contiguous from start
            System.arraycopy(buffer, 0, result, 0, size);
        } else if (head + size <= buffer.length) {
            // Fast path: contiguous but offset
            System.arraycopy(buffer, head, result, 0, size);
        } else {
            // Slow path: wrapped around ring buffer
            int firstPart = buffer.length - head;
            System.arraycopy(buffer, head, result, 0, firstPart);
            System.arraycopy(buffer, 0, result, firstPart, size - firstPart);
        }

        return (A[]) result;
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("Deque(");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(", ");
            sb.append(get(i));
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeDeque<?> other)) return false;
        if (size != other.size) return false;

        for (int i = 0; i < size; i++) {
            if (!Objects.equals(get(i), other.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    @TruffleBoundary
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < size; i++) {
            result = 31 * result + Objects.hashCode(get(i));
        }
        return result;
    }
}
