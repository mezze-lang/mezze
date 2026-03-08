package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * Represents the unit type {} in Mezze.
 * This is a singleton - use MezzeUnit.INSTANCE.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeUnit implements TruffleObject {

    public static final MezzeUnit INSTANCE = new MezzeUnit();

    private MezzeUnit() {}

    @ExportMessage
    boolean hasMembers() {
        return false;
    }

    @ExportMessage
    Object getMembers(boolean includeInternal) {
        return new String[0];
    }

    @Override
    public String toString() {
        return "{}";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MezzeUnit;
    }

    @Override
    public int hashCode() {
        return 0;
    }
}
