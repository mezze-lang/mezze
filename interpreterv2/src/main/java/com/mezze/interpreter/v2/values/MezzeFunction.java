package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

/**
 * Function value in Mezze v2.
 *
 * Contains:
 * - callTarget: The compiled Truffle CallTarget
 * - name: Function name (for debugging/display)
 * - closureValues: Captured closure variables as Object[]
 *
 * Closure variables are captured eagerly when the function is created,
 * stored in a positional array for fast access.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeFunction implements TruffleObject {

    private final CallTarget callTarget;
    private final String name;

    @CompilationFinal(dimensions = 1)
    private final Object[] closureValues;

    public MezzeFunction(CallTarget callTarget, String name, Object[] closureValues) {
        this.callTarget = callTarget;
        this.name = name;
        this.closureValues = closureValues != null ? closureValues : new Object[0];
    }

    /**
     * Create a function with no closure.
     */
    public static MezzeFunction create(CallTarget callTarget, String name) {
        return new MezzeFunction(callTarget, name, new Object[0]);
    }

    public CallTarget getCallTarget() {
        return callTarget;
    }

    public String getName() {
        return name;
    }

    public Object[] getClosureValues() {
        return closureValues;
    }

    public Object getClosureValue(int index) {
        return closureValues[index];
    }

    public boolean hasClosure() {
        return closureValues.length > 0;
    }

    // InteropLibrary exports
    @ExportMessage
    boolean isExecutable() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object execute(Object[] arguments) {
        // Prepend closure values to arguments
        if (closureValues.length == 0) {
            return callTarget.call(arguments);
        } else {
            Object[] fullArgs = new Object[closureValues.length + arguments.length];
            System.arraycopy(closureValues, 0, fullArgs, 0, closureValues.length);
            System.arraycopy(arguments, 0, fullArgs, closureValues.length, arguments.length);
            return callTarget.call(fullArgs);
        }
    }

    @ExportMessage
    boolean hasMembers() {
        return true;
    }

    @ExportMessage
    @TruffleBoundary
    Object getMembers(boolean includeInternal) {
        return new String[] { "name" };
    }

    @ExportMessage
    @TruffleBoundary
    boolean isMemberReadable(String member) {
        return "name".equals(member);
    }

    @ExportMessage
    @TruffleBoundary
    Object readMember(String member) {
        if ("name".equals(member)) return name;
        return null;
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return "<function " + name + ">";
    }
}
