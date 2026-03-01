package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * UNBOXED float array for Mezze - ZERO BOXING OVERHEAD.
 *
 * Stores floats as primitive double[] instead of Object[].
 * This eliminates boxing/unboxing overhead for numeric operations.
 *
 * Performance benefits:
 * 1. No boxing when storing floats
 * 2. No unboxing when reading floats
 * 3. Better cache locality (contiguous memory)
 * 4. Smaller memory footprint
 *
 * InteropLibrary is implemented for polyglot compatibility.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeFloatArray implements TruffleObject {

    // Immutable backing array
    @CompilationFinal(dimensions = 1)
    private final double[] elements;

    @CompilationFinal
    private final int length;

    public MezzeFloatArray(double[] elements) {
        this.elements = elements;
        this.length = elements.length;
    }

    /**
     * Create from an Object array (converts Double to double).
     */
    public static MezzeFloatArray fromObjects(Object[] objects) {
        double[] elements = new double[objects.length];
        for (int i = 0; i < objects.length; i++) {
            if (objects[i] instanceof Double d) {
                elements[i] = d;
            } else if (objects[i] instanceof Long l) {
                elements[i] = l.doubleValue();
            } else {
                elements[i] = ((Number) objects[i]).doubleValue();
            }
        }
        return new MezzeFloatArray(elements);
    }

    /**
     * Create an empty array.
     */
    public static MezzeFloatArray empty() {
        return EMPTY;
    }

    private static final MezzeFloatArray EMPTY = new MezzeFloatArray(new double[0]);

    // ===== Direct Access (NO BOXING) =====

    /**
     * Get element as primitive double - NO BOXING.
     */
    public double getDouble(int index) {
        return elements[index];
    }

    /**
     * Get length.
     */
    public int length() {
        return length;
    }

    /**
     * Get backing array (for iteration).
     */
    public double[] getElements() {
        return elements;
    }

    // ===== InteropLibrary =====

    @ExportMessage
    boolean hasArrayElements() {
        return true;
    }

    @ExportMessage
    long getArraySize() {
        return length;
    }

    @ExportMessage
    boolean isArrayElementReadable(long index) {
        return index >= 0 && index < length;
    }

    @ExportMessage
    Object readArrayElement(long index) throws InvalidArrayIndexException {
        if (index < 0 || index >= length) {
            throw InvalidArrayIndexException.create(index);
        }
        return elements[(int) index];  // Boxing only for interop
    }

    @ExportMessage
    boolean isArrayElementModifiable(long index) {
        return false;  // Immutable
    }

    @ExportMessage
    boolean isArrayElementInsertable(long index) {
        return false;  // Immutable
    }

    @ExportMessage
    void writeArrayElement(long index, Object value) throws UnsupportedMessageException {
        throw UnsupportedMessageException.create();
    }

    @Override
    @TruffleBoundary
    public String toString() {
        if (length == 0) return "[]";
        // Pre-size: "[" + (length * ~15 chars per double) + "]"
        StringBuilder sb = new StringBuilder(Math.max(16, 2 + length * 15));
        sb.append("[");
        for (int i = 0; i < length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(elements[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
