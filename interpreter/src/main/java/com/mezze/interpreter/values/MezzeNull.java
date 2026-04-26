package com.mezze.interpreter.values;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * Represents an uninitialized or null value in Mezze.
 * This is a singleton - use MezzeNull.INSTANCE.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeNull implements TruffleObject {

    public static final MezzeNull INSTANCE = new MezzeNull();

    private MezzeNull() {}

    @ExportMessage
    boolean isNull() {
        return true;
    }

    @Override
    public String toString() {
        return "null";
    }
}
