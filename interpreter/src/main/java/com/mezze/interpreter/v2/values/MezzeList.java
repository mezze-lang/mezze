package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import io.vavr.collection.List;

import java.util.Iterator;
import java.util.Objects;

/**
 * Mezze List backed by Vavr List (functional linked list) for O(1) head/tail/prepend.
 *
 * Vavr List provides:
 * - O(1) prepend (cons)
 * - O(1) head
 * - O(1) tail
 * - O(n) append, get by index
 * - Structural sharing for memory efficiency
 * - Immutable semantics with persistent updates
 *
 * This is the classic functional linked list, ideal for recursive algorithms
 * and pattern matching. For random access, use MezzeSeq (Vavr Vector) instead.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeList implements TruffleObject, Iterable<Object> {

    private static final MezzeList EMPTY = new MezzeList(List.empty());

    private final List<Object> elements;

    private MezzeList(List<Object> elements) {
        this.elements = elements;
    }

    // ===== FACTORY METHODS =====

    public static MezzeList empty() {
        return EMPTY;
    }

    public static MezzeList of(Object element) {
        return new MezzeList(List.of(element));
    }

    public static MezzeList of(Object e1, Object e2) {
        return new MezzeList(List.of(e1, e2));
    }

    public static MezzeList of(Object e1, Object e2, Object e3) {
        return new MezzeList(List.of(e1, e2, e3));
    }

    @TruffleBoundary
    public static MezzeList of(Object... elements) {
        if (elements.length == 0) return EMPTY;
        return new MezzeList(List.of(elements));
    }

    @TruffleBoundary
    public static MezzeList fromIterable(Iterable<?> elements) {
        return new MezzeList(List.ofAll(elements));
    }

    @TruffleBoundary
    public static MezzeList fromArray(Object[] elements) {
        if (elements.length == 0) return EMPTY;
        return new MezzeList(List.of(elements));
    }

    // ===== BASIC OPERATIONS =====

    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    public List<Object> getElements() {
        return elements;
    }

    // ===== HEAD/TAIL OPERATIONS (O(1)) =====

    @TruffleBoundary
    public Object head() {
        return elements.head();
    }

    @TruffleBoundary
    public Object headOrNull() {
        return elements.isEmpty() ? null : elements.head();
    }

    @TruffleBoundary
    public MezzeList tail() {
        List<Object> t = elements.tail();
        return t.isEmpty() ? EMPTY : new MezzeList(t);
    }

    @TruffleBoundary
    public MezzeList tailOrEmpty() {
        if (elements.isEmpty()) return EMPTY;
        List<Object> t = elements.tail();
        return t.isEmpty() ? EMPTY : new MezzeList(t);
    }

    @TruffleBoundary
    public Object last() {
        return elements.last();
    }

    @TruffleBoundary
    public MezzeList init() {
        List<Object> i = elements.init();
        return i.isEmpty() ? EMPTY : new MezzeList(i);
    }

    // ===== INDEX ACCESS (O(n)) =====

    @TruffleBoundary
    public Object get(int index) {
        return elements.get(index);
    }

    public boolean isValidIndex(int index) {
        return index >= 0 && index < elements.size();
    }

    // ===== PERSISTENT UPDATE OPERATIONS =====

    @TruffleBoundary
    public MezzeList prepend(Object element) {
        return new MezzeList(elements.prepend(element));
    }

    @TruffleBoundary
    public MezzeList append(Object element) {
        return new MezzeList(elements.append(element));
    }

    @TruffleBoundary
    public MezzeList update(int index, Object element) {
        return new MezzeList(elements.update(index, element));
    }

    @TruffleBoundary
    public MezzeList removeAt(int index) {
        return new MezzeList(elements.removeAt(index));
    }

    @TruffleBoundary
    public MezzeList concat(MezzeList other) {
        return new MezzeList(elements.appendAll(other.elements));
    }

    @TruffleBoundary
    public MezzeList reverse() {
        return new MezzeList(elements.reverse());
    }

    @TruffleBoundary
    public MezzeList take(int n) {
        List<Object> taken = elements.take(n);
        return taken.isEmpty() ? EMPTY : new MezzeList(taken);
    }

    @TruffleBoundary
    public MezzeList drop(int n) {
        List<Object> dropped = elements.drop(n);
        return dropped.isEmpty() ? EMPTY : new MezzeList(dropped);
    }

    @TruffleBoundary
    public MezzeList slice(int start, int end) {
        List<Object> sliced = elements.slice(start, end);
        return sliced.isEmpty() ? EMPTY : new MezzeList(sliced);
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
    public java.util.List<Object> toJavaList() {
        return elements.asJava();
    }

    @TruffleBoundary
    public Object[] toArray() {
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
    @TruffleBoundary
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < elements.size();
    }

    @ExportMessage
    @TruffleBoundary
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

    @ExportMessage
    boolean hasIterator() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getIterator() {
        return new ListIterator(elements.iterator());
    }

    // ===== OBJECT METHODS =====

    @Override
    @TruffleBoundary
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MezzeList other)) return false;
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
        StringBuilder sb = new StringBuilder("List(");
        boolean first = true;
        for (Object elem : elements) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(elem);
        }
        sb.append(")");
        return sb.toString();
    }

    // ===== ITERATOR HELPER =====

    @ExportLibrary(InteropLibrary.class)
    static final class ListIterator implements TruffleObject {
        private final Iterator<Object> iterator;

        ListIterator(Iterator<Object> iterator) {
            this.iterator = iterator;
        }

        @ExportMessage
        boolean isIterator() {
            return true;
        }

        @ExportMessage
        @TruffleBoundary
        boolean hasIteratorNextElement() {
            return iterator.hasNext();
        }

        @ExportMessage
        @TruffleBoundary
        Object getIteratorNextElement() throws UnsupportedMessageException {
            if (!iterator.hasNext()) {
                throw UnsupportedMessageException.create();
            }
            return iterator.next();
        }
    }
}
