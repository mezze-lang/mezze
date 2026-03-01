package com.mezze.interpreter.v2.values;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.nodes.ExplodeLoop;

/**
 * Function value in Mezze v2 - OPTIMIZED.
 *
 * Contains:
 * - callTarget: The compiled Truffle CallTarget
 * - name: Function name (for debugging/display)
 * - closureValues: Captured closure variables as Object[]
 *
 * Closure variables are captured eagerly when the function is created,
 * stored in a positional array for fast access.
 *
 * The execute() method for InteropLibrary is NO LONGER @TruffleBoundary,
 * enabling cross-language calls to be JIT compiled.
 */
@ExportLibrary(InteropLibrary.class)
public final class MezzeFunction implements TruffleObject {

    private final CallTarget callTarget;
    private final String name;

    @CompilationFinal(dimensions = 1)
    private final Object[] closureValues;

    @CompilationFinal
    private final int closureLength;

    /**
     * Assumption for inlining stability.
     * Invalidated when the function is redefined, causing deoptimization
     * of any inlined call sites.
     */
    private final Assumption inlineAssumption;

    public MezzeFunction(CallTarget callTarget, String name, Object[] closureValues) {
        this.callTarget = callTarget;
        this.name = name;
        this.closureValues = closureValues != null ? closureValues : EMPTY_CLOSURE;
        this.closureLength = this.closureValues.length;
        this.inlineAssumption = Truffle.getRuntime().createAssumption("inline:" + name);
    }

    private static final Object[] EMPTY_CLOSURE = new Object[0];

    /**
     * Create a function with no closure.
     */
    public static MezzeFunction create(CallTarget callTarget, String name) {
        return new MezzeFunction(callTarget, name, EMPTY_CLOSURE);
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
        return closureLength > 0;
    }

    /**
     * Get the assumption that guards inlined call sites.
     * When this assumption is invalidated, all inlined versions
     * of this function will deoptimize.
     */
    public Assumption getInlineAssumption() {
        return inlineAssumption;
    }

    /**
     * Invalidate the inline assumption (e.g., when function is redefined).
     * This triggers deoptimization of all inlined call sites.
     */
    public void invalidateInlineAssumption() {
        inlineAssumption.invalidate("function redefined: " + name);
    }

    // InteropLibrary exports
    @ExportMessage
    boolean isExecutable() {
        return true;
    }

    /**
     * Execute this function from another language.
     * NO @TruffleBoundary - fully JIT compilable for cross-language calls!
     *
     * AGGRESSIVELY OPTIMIZED:
     * - Zero-closure fast path (no argument array allocation overhead)
     * - Inline loop for closure copy (JIT unrolls better than System.arraycopy for small N)
     */
    @ExportMessage
    @ExplodeLoop
    Object execute(Object[] arguments) throws ArityException, UnsupportedTypeException, UnsupportedMessageException {
        // FAST PATH: No closure - direct call with provided arguments
        if (closureLength == 0) {
            return callTarget.call(arguments);
        }

        // CLOSURE PATH: Prepend closure values
        int argLen = arguments.length;
        Object[] fullArgs = new Object[closureLength + argLen];

        // Inline loop for closure (JIT fully unrolls this)
        for (int i = 0; i < closureLength; i++) {
            fullArgs[i] = closureValues[i];
        }

        // Inline loop for arguments (JIT fully unrolls this)
        for (int i = 0; i < argLen; i++) {
            fullArgs[closureLength + i] = arguments[i];
        }

        return callTarget.call(fullArgs);
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
    boolean isMemberReadable(String member) {
        return "name".equals(member);
    }

    @ExportMessage
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
