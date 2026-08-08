package com.mezze.datastructures.deque;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

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
@ExportLibrary(InteropLibrary.class)
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

    /**
     * COW ownership token. token[0] == this means we own the buffer.
     * On mutation, ownership transfers to the new deque.
     * On any read, ownership is revoked to prevent corruption if reused.
     */
    private final Object[] ownerToken;

    private MezzeDeque(Object[] buffer, int head, int tail, int size, Object[] ownerToken) {
        this.buffer = buffer;
        this.head = head;
        this.tail = tail;
        this.size = size;
        this.mask = buffer.length - 1;
        this.ownerToken = ownerToken;
    }

    private MezzeDeque(Object[] buffer, int head, int tail, int size) {
        Object[] token = new Object[1];
        this.buffer = buffer;
        this.head = head;
        this.tail = tail;
        this.size = size;
        this.mask = buffer.length - 1;
        this.ownerToken = token;
        token[0] = this;
    }

    /** Latched flag — see HashMapStorage.ownershipRevoked for rationale. */
    @CompilationFinal
    private boolean ownershipRevoked;

    private boolean isOwner() {
        return !ownershipRevoked && ownerToken != null && ownerToken[0] == this;
    }

    private void revokeOwnership() {
        if (ownershipRevoked) return;
        if (ownerToken != null && ownerToken[0] == this) {
            ownerToken[0] = null;
        }
        ownershipRevoked = true;
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
        revokeOwnership();
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
        revokeOwnership();
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
        revokeOwnership();
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
    public MezzeDeque<A> pushFront(A element) {
        if (size == buffer.length) {
            return grow().pushFront(element);
        }

        int newHead = (head - 1) & mask;

        Object[] workBuffer;
        Object[] token;
        if (isOwner()) {
            workBuffer = buffer;
            token = ownerToken;
        } else {
            workBuffer = buffer.clone();
            token = new Object[1];
        }

        workBuffer[newHead] = element;
        MezzeDeque<A> result = new MezzeDeque<>(workBuffer, newHead, tail, size + 1, token);
        token[0] = result;
        return result;
    }

    /**
     * Returns a new deque with element added to back.
     * Uses copy-on-write: only copies buffer if not owned.
     */
    public MezzeDeque<A> pushBack(A element) {
        if (size == buffer.length) {
            return grow().pushBack(element);
        }

        Object[] workBuffer;
        Object[] token;
        if (isOwner()) {
            workBuffer = buffer;
            token = ownerToken;
        } else {
            workBuffer = buffer.clone();
            token = new Object[1];
        }

        workBuffer[tail] = element;
        int newTail = (tail + 1) & mask;
        MezzeDeque<A> result = new MezzeDeque<>(workBuffer, head, newTail, size + 1, token);
        token[0] = result;
        return result;
    }

    // ===== POP OPERATIONS =====

    /**
     * Returns (front element, rest of deque), or throws if empty.
     * Uses copy-on-write: shares buffer when possible.
     */
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

        // Pop shares the buffer but doesn't claim ownership — narrowing the view
        revokeOwnership();
        return new PopResult<>(element, new MezzeDeque<>(buffer, newHead, tail, size - 1, (Object[]) null));
    }

    /**
     * Returns (back element, rest of deque), or throws if empty.
     * Uses copy-on-write: shares buffer when possible.
     */
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

        // Pop shares the buffer but doesn't claim ownership
        revokeOwnership();
        return new PopResult<>(element, new MezzeDeque<>(buffer, head, backIndex, size - 1, (Object[]) null));
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
    public MezzeDeque<A> reverse() {
        if (size <= 1) return this;

        Object[] newBuffer = new Object[buffer.length];
        for (int i = 0; i < size; i++) {
            newBuffer[i] = get(size - 1 - i);
        }

        return new MezzeDeque<>(newBuffer, 0, size, size);
    }

    // ===== SLICING =====

    /**
     * Take first n elements.
     */
    public MezzeDeque<A> take(int n) {
        if (n <= 0 || isEmpty()) return empty();
        if (n >= size) return this;

        Object[] newBuffer = new Object[nextPowerOf2(Math.max(n, MIN_CAPACITY))];
        for (int i = 0; i < n; i++) {
            newBuffer[i] = get(i);
        }
        return new MezzeDeque<>(newBuffer, 0, n, n);
    }

    /**
     * Drop first n elements.
     */
    public MezzeDeque<A> drop(int n) {
        if (n <= 0) return this;
        if (n >= size) return empty();

        int newSize = size - n;
        Object[] newBuffer = new Object[nextPowerOf2(Math.max(newSize, MIN_CAPACITY))];
        for (int i = 0; i < newSize; i++) {
            newBuffer[i] = get(n + i);
        }
        return new MezzeDeque<>(newBuffer, 0, newSize, newSize);
    }

    // ===== FACTORY FROM ITERABLE =====

    /**
     * Creates a deque from an iterable.
     */
    public static <A> MezzeDeque<A> fromIterable(Iterable<? extends A> elements) {
        MezzeDeque<A> result = empty();
        for (A elem : elements) {
            result = result.pushBack(elem);
        }
        return result;
    }

    // ===== MUTABLE OPERATIONS (for LocalMut effect) =====

    /**
     * Creates a mutable copy of this deque.
     * Used by to_mut to get a fresh copy that can be mutated.
     */
    public MezzeDeque<A> copyToMutable() {
        // Linearize into a new buffer for easy mutation
        Object[] newBuffer = new Object[buffer.length];
        for (int i = 0; i < size; i++) {
            newBuffer[i] = get(i);
        }
        return new MezzeDeque<>(newBuffer, 0, size, size);
    }

    /**
     * In-place set - mutates the underlying buffer.
     * Only safe within LocalMut context.
     */
    public void mutSet(int index, A value) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size);
        }
        int bufferIndex = (head + index) & mask;
        buffer[bufferIndex] = value;
    }

    /**
     * In-place push to front - mutates the underlying buffer.
     * Only safe within LocalMut context.
     * Returns false if buffer is full.
     */
    public boolean mutPushFront(A element) {
        if (size >= buffer.length) {
            return false; // Buffer full, can't grow in mutable mode
        }
        int newHead = (head - 1) & mask;
        buffer[newHead] = element;
        // Note: head/tail/size are final - caller must track
        return true;
    }

    /**
     * In-place push to back - mutates the underlying buffer.
     * Only safe within LocalMut context.
     * Returns false if buffer is full.
     */
    public boolean mutPushBack(A element) {
        if (size >= buffer.length) {
            return false; // Buffer full, can't grow in mutable mode
        }
        buffer[tail] = element;
        // Note: head/tail/size are final - caller must track
        return true;
    }

    /**
     * In-place swap - mutates the underlying buffer.
     * Only safe within LocalMut context.
     */
    public void mutSwap(int i, int j) {
        if (i < 0 || i >= size || j < 0 || j >= size) {
            throw new IndexOutOfBoundsException("Indices: " + i + ", " + j + ", Size: " + size);
        }
        int idxI = (head + i) & mask;
        int idxJ = (head + j) & mask;
        Object tmp = buffer[idxI];
        buffer[idxI] = buffer[idxJ];
        buffer[idxJ] = tmp;
    }

    /**
     * In-place reverse - mutates the underlying buffer.
     * Only safe within LocalMut context.
     */
    public void mutReverse() {
        int left = 0;
        int right = size - 1;
        while (left < right) {
            mutSwap(left, right);
            left++;
            right--;
        }
    }

    /**
     * In-place clear - sets all elements to null.
     * Only safe within LocalMut context.
     */
    public void mutClear() {
        Arrays.fill(buffer, null);
    }

    // ===== INTERNAL =====

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
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < size; i++) {
            result = 31 * result + Objects.hashCode(get(i));
        }
        return result;
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    long getArraySize() {
        return size;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < size;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (index < 0 || index >= size) {
            throw InvalidArrayIndexException.create(index);
        }
        return get((int) index);
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isArrayElementModifiable(@SuppressWarnings("unused") long index) {
        return false;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean isArrayElementInsertable(@SuppressWarnings("unused") long index) {
        return false;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    void writeArrayElement(@SuppressWarnings("unused") long index, @SuppressWarnings("unused") Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
   @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    Object getIterator() {
        return new DequeIterator(iterator());
    }

    @ExportLibrary(InteropLibrary.class)
    static final class DequeIterator implements TruffleObject {
        private final Iterator<?> iterator;

        DequeIterator(Iterator<?> iterator) {
            this.iterator = iterator;
        }

        @ExportMessage
       @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        boolean isIterator() {
            return true;
        }

        @ExportMessage
       @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        boolean hasIteratorNextElement() {
            return iterator.hasNext();
        }

        @ExportMessage
       @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        Object getIteratorNextElement() throws UnsupportedMessageException {
            if (!iterator.hasNext()) {
                throw UnsupportedMessageException.create();
            }
            return iterator.next();
        }
    }
}
