package com.mezze.interpreter.nodes.builtin.polyglot;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.StopIterationException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownKeyException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Guest-readable list and dict views over Mezze-side values — the runtime half
 * of BUG 2 (`.to_py {}` / `.to_js {}` over containers).
 *
 * The polyglot surface could read an existing guest array (`read_array`,
 * `array_size`) but could not CONSTRUCT one, so a provider's `encode_list` had
 * nothing to call: it receives `elems: [PyForeign]`, each element already
 * encoded, and must hand back a single list-shaped Foreign.
 *
 * These are interop views, not copies into the guest heap: the guest reads
 * elements through InteropLibrary on demand. That is what makes one
 * implementation serve every language — Python sees a sequence, JavaScript sees
 * an array-like, and neither needs a language-specific builder. Both are
 * immutable, because a codec's output has no meaningful in-place update and an
 * exception at the write is clearer than a write that silently vanishes.
 */
final class ForeignContainers {

    private ForeignContainers() {}

    /** A guest-readable sequence over already-unwrapped delegate values. */
    @ExportLibrary(InteropLibrary.class)
    static final class ForeignList implements TruffleObject {
        private final Object[] elements;

        ForeignList(Object[] elements) {
            this.elements = elements;
        }

        @ExportMessage
        boolean hasArrayElements() {
            return true;
        }

        @ExportMessage
        long getArraySize() {
            return elements.length;
        }

        @ExportMessage
        boolean isArrayElementReadable(long index) {
            return index >= 0 && index < elements.length;
        }

        @ExportMessage
        Object readArrayElement(long index) throws InvalidArrayIndexException {
            if (index < 0 || index >= elements.length) {
                throw InvalidArrayIndexException.create(index);
            }
            return elements[(int) index];
        }

        @ExportMessage
        boolean isArrayElementModifiable(@SuppressWarnings("unused") long index) {
            return false;
        }

        @ExportMessage
        boolean isArrayElementInsertable(@SuppressWarnings("unused") long index) {
            return false;
        }

        @ExportMessage
        void writeArrayElement(
            @SuppressWarnings("unused") long index,
            @SuppressWarnings("unused") Object value
        ) throws UnsupportedMessageException {
            throw UnsupportedMessageException.create();
        }
    }

    /** A guest-readable mapping over already-unwrapped delegate keys and values. */
    @ExportLibrary(InteropLibrary.class)
    static final class ForeignDict implements TruffleObject {
        private final LinkedHashMap<Object, Object> entries;

        ForeignDict(LinkedHashMap<Object, Object> entries) {
            this.entries = entries;
        }

        @ExportMessage
        boolean hasHashEntries() {
            return true;
        }

        @ExportMessage
        @TruffleBoundary
        long getHashSize() {
            return entries.size();
        }

        /**
         * A guest STRING key is normalized to a Java String before lookup.
         *
         * `entries` is keyed by whatever the encoder put in — for a record, the
         * field names, which are Java Strings. A guest asking `m['alpha']` passes
         * ITS OWN string object, and `LinkedHashMap.containsKey` compares by Java
         * `equals`, so a Python `str` never matched a Java `String` and every
         * subscript raised UnknownKey. Iteration worked (the keys come back out as
         * guest strings), which is why the dict looked right while reads failed.
         *
         * Only strings are normalized. A non-string key keeps identity semantics —
         * there is no cross-language equality to appeal to for an arbitrary object,
         * and inventing one would be a guess.
         */
        @TruffleBoundary
        private static Object normalizeKey(Object key) {
            InteropLibrary interop = InteropLibrary.getUncached();
            if (key instanceof String || !interop.isString(key)) {
                return key;
            }
            try {
                return interop.asString(key);
            } catch (UnsupportedMessageException e) {
                return key;
            }
        }

        @ExportMessage
        @TruffleBoundary
        boolean isHashEntryReadable(Object key) {
            return entries.containsKey(normalizeKey(key));
        }

        @ExportMessage
        @TruffleBoundary
        Object readHashValue(Object key) throws UnknownKeyException {
            Object normalized = normalizeKey(key);
            Object found = entries.get(normalized);
            if (found == null && !entries.containsKey(normalized)) {
                throw UnknownKeyException.create(key);
            }
            return found;
        }

        @ExportMessage
        boolean isHashEntryModifiable(@SuppressWarnings("unused") Object key) {
            return false;
        }

        @ExportMessage
        boolean isHashEntryInsertable(@SuppressWarnings("unused") Object key) {
            return false;
        }

        @ExportMessage
        void writeHashEntry(
            @SuppressWarnings("unused") Object key,
            @SuppressWarnings("unused") Object value
        ) throws UnsupportedMessageException {
            throw UnsupportedMessageException.create();
        }

        @ExportMessage
        @TruffleBoundary
        Object getHashEntriesIterator() {
            Object[] pairs = new Object[entries.size()];
            int i = 0;
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                pairs[i++] = new ForeignList(new Object[] { entry.getKey(), entry.getValue() });
            }
            return new ArrayIterator(pairs);
        }
    }

    /**
     * Cursor over a fixed array. Needed because interop asks for hash entries as
     * an ITERATOR of key/value pairs, not as an array.
     */
    @ExportLibrary(InteropLibrary.class)
    static final class ArrayIterator implements TruffleObject {
        private final Object[] items;
        private int position = 0;

        ArrayIterator(Object[] items) {
            this.items = items;
        }

        @ExportMessage
        boolean isIterator() {
            return true;
        }

        @ExportMessage
        boolean hasIteratorNextElement() {
            return position < items.length;
        }

        @ExportMessage
        Object getIteratorNextElement() throws StopIterationException {
            if (position >= items.length) {
                throw StopIterationException.create();
            }
            return items[position++];
        }
    }
}
