package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import io.vavr.collection.Vector;

import java.util.Iterator;
import java.util.Objects;

/**
 * Mezze Seq (Sequence) backed by Vavr Vector for O(log32 n) ≈ O(1) persistent operations.
 *
 * This is the general-purpose sequence type for Mezze's [] syntax.
 *
 * Vavr Vector provides:
 * - O(log32 n) ≈ O(1) get, update, append, prepend (bit-mapped vector trie)
 * - Structural sharing for memory efficiency
 * - Immutable semantics with persistent updates
 *
 * All "mutating" operations return new MezzeSeq instances with shared structure.
 * This is critical for functional programming performance - updates don't copy the whole sequence.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeSeq implements TruffleObject, Iterable<Object> {

    // Singleton empty sequence
    private static final MezzeSeq EMPTY = new MezzeSeq(Vector.empty());

    // Vavr Vector backing store - immutable with structural sharing
    private final Vector<Object> elements;

    private MezzeSeq(Vector<Object> elements) {
        this.elements = elements;
    }

    // ===== FACTORY METHODS =====

    public static MezzeSeq empty() {
        return EMPTY;
    }

    public static MezzeSeq of(Object element) {
        return new MezzeSeq(Vector.of(element));
    }

    public static MezzeSeq of(Object e1, Object e2) {
        return new MezzeSeq(Vector.of(e1, e2));
    }

    public static MezzeSeq of(Object e1, Object e2, Object e3) {
        return new MezzeSeq(Vector.of(e1, e2, e3));
    }

    @TruffleBoundary
    public static MezzeSeq of(Object... elements) {
        if (elements.length == 0) return EMPTY;
        return new MezzeSeq(Vector.of(elements));
    }

    @TruffleBoundary
    public static MezzeSeq fromIterable(Iterable<?> elements) {
        return new MezzeSeq(Vector.ofAll(elements));
    }

    /**
     * Create a sequence from a Java array.
     */
    @TruffleBoundary
    public static MezzeSeq fromArray(Object[] elements) {
        if (elements.length == 0) return EMPTY;
        return new MezzeSeq(Vector.of(elements));
    }

    // ===== BASIC OPERATIONS =====

    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    public Object get(int index) {
        return elements.get(index);
    }

    public boolean isValidIndex(int index) {
        return index >= 0 && index < elements.size();
    }

    public Vector<Object> getElements() {
        return elements;
    }

    // ===== PERSISTENT UPDATE OPERATIONS =====

    @TruffleBoundary
    public MezzeSeq append(Object element) {
        return new MezzeSeq(elements.append(element));
    }

    @TruffleBoundary
    public MezzeSeq prepend(Object element) {
        return new MezzeSeq(elements.prepend(element));
    }

    @TruffleBoundary
    public MezzeSeq update(int index, Object element) {
        return new MezzeSeq(elements.update(index, element));
    }

    @TruffleBoundary
    public MezzeSeq removeAt(int index) {
        return new MezzeSeq(elements.removeAt(index));
    }

    @TruffleBoundary
    public MezzeSeq concat(MezzeSeq other) {
        return new MezzeSeq(elements.appendAll(other.elements));
    }

    @TruffleBoundary
    public MezzeSeq slice(int start, int end) {
        return new MezzeSeq(elements.slice(start, end));
    }

    @TruffleBoundary
    public MezzeSeq reverse() {
        return new MezzeSeq(elements.reverse());
    }

    @TruffleBoundary
    public MezzeSeq take(int n) {
        return new MezzeSeq(elements.take(n));
    }

    @TruffleBoundary
    public MezzeSeq drop(int n) {
        return new MezzeSeq(elements.drop(n));
    }

    // ===== SEARCH OPERATIONS =====

    @TruffleBoundary
    public boolean contains(Object element) {
        return elements.contains(element);
    }

    @TruffleBoundary
    public int indexOf(Object element) {
        return elements.indexOf(element);
    }

    // ===== CONVERSION =====

    @TruffleBoundary
    public Object[] toJavaArray() {
        return elements.toJavaArray(Object[]::new);
    }

    @Override
    @TruffleBoundary
    public Iterator<Object> iterator() {
        return elements.iterator();
    }

    // ===== INTEROP LIBRARY =====

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return elements.size();
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < elements.size();
    }

    @ExportMessage
    Object readArrayElement(long index) {
        return elements.get((int) index);
    }

    @ExportMessage
    boolean isArrayElementModifiable(long index) {
        return false;
    }

    @ExportMessage
    boolean isArrayElementInsertable(long index) {
        return false;
    }

    @ExportMessage
    void writeArrayElement(long index, Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeSeq other)) return false;
        return Objects.equals(elements, other.elements);
    }

    @Override
    @TruffleBoundary
    public int hashCode() {
        return elements.hashCode();
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object elem : elements) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(elem);
        }
        sb.append("]");
        return sb.toString();
    }
}
