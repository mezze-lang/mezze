package com.mezze.datastructures.list;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import java.util.Iterator;

/**
 * Helper class for list interop - provides shared iterator implementation.
 */
public final class ListInteropHelper {

    private ListInteropHelper() {}

    /**
     * Singleton empty iterator for Nil.
     */
    public static final EmptyIterator EMPTY_ITERATOR = new EmptyIterator();

    @ExportLibrary(InteropLibrary.class)
    public static final class EmptyIterator implements TruffleObject {

        @ExportMessage
       @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        boolean isIterator() {
            return true;
        }

        @ExportMessage
       @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        boolean hasIteratorNextElement() {
            return false;
        }

        @ExportMessage
       @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
        Object getIteratorNextElement() throws UnsupportedMessageException {
            throw UnsupportedMessageException.create();
        }
    }

    @ExportLibrary(InteropLibrary.class)
    public static final class ListIterator implements TruffleObject {
        private final Iterator<?> iterator;

        public ListIterator(Iterator<?> iterator) {
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
