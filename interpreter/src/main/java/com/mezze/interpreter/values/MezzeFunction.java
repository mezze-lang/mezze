package com.mezze.interpreter.values;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.HostCompilerDirectives.InliningCutoff;
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
 * - parameterNames: Expected parameter names for partial application
 * - partialArgs: Already-provided arguments for partial application (null if none)
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

    // Parameter names for partial application support
    @CompilationFinal(dimensions = 1)
    private final String[] parameterNames;

    // Partial arguments already provided (null = no partial application)
    // Maps parameter name -> value
    private final java.util.Map<String, Object> partialArgs;

    private static final Object[] EMPTY_CLOSURE = new Object[0];
    private static final String[] EMPTY_PARAMS = new String[0];

    public MezzeFunction(CallTarget callTarget, String name, Object[] closureValues) {
        this(callTarget, name, closureValues, EMPTY_PARAMS, null);
    }

    public MezzeFunction(CallTarget callTarget, String name, Object[] closureValues, String[] parameterNames) {
        this(callTarget, name, closureValues, parameterNames, null);
    }

    public MezzeFunction(CallTarget callTarget, String name, Object[] closureValues,
                         String[] parameterNames, java.util.Map<String, Object> partialArgs) {
        this.callTarget = callTarget;
        this.name = name;
        this.closureValues = (closureValues == null || closureValues.length == 0) ? EMPTY_CLOSURE : closureValues;
        this.closureLength = this.closureValues.length;
        this.parameterNames = parameterNames != null ? parameterNames : EMPTY_PARAMS;
        this.partialArgs = partialArgs;
    }

    /**
     * Create a function with no closure.
     */
    public static MezzeFunction create(CallTarget callTarget, String name) {
        return new MezzeFunction(callTarget, name, EMPTY_CLOSURE, EMPTY_PARAMS, null);
    }

    /**
     * Create a function with parameter names for partial application support.
     */
    public static MezzeFunction create(CallTarget callTarget, String name, String[] parameterNames) {
        return new MezzeFunction(callTarget, name, EMPTY_CLOSURE, parameterNames, null);
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

    public String[] getParameterNames() {
        return parameterNames;
    }

    public java.util.Map<String, Object> getPartialArgs() {
        return partialArgs;
    }

    /**
     * PE-transparent null check + boundary isEmpty. For the vast majority
     * of functions partialArgs is null (set once in the ctor) so PE folds
     * the entire call to {@code false} — no boundary entry at all.
     * Only partial-application paths reach {@link #partialArgsNotEmpty},
     * which stays behind the boundary for SVM's Map.isEmpty blocklist (#402).
     */
    public boolean hasPartialArgs() {
        return partialArgs != null && partialArgsNotEmpty();
    }

    @TruffleBoundary
    private boolean partialArgsNotEmpty() {
        return !partialArgs.isEmpty();
    }

    /**
     * Get remaining parameter names (those not yet provided).
     * Excludes hidden dictionary params (__dict_*).
     */
    @TruffleBoundary
    public String[] getRemainingParameterNames() {
        java.util.List<String> remaining = new java.util.ArrayList<>();
        for (String param : parameterNames) {
            // Skip hidden dictionary params — not user-visible
            if (com.mezze.language.ast.MagicParams.INSTANCE.isMagic(param)) continue;
            if (partialArgs != null && partialArgs.containsKey(param)) continue;
            remaining.add(param);
        }
        return remaining.toArray(new String[0]);
    }

    /**
     * Check if all required parameters are satisfied by the given record.
     * Hidden dictionary params (__dict_*) are excluded — they are injected by
     * the compiler for dictionary passing and are not part of the user-visible API.
     */
    @TruffleBoundary
    public boolean hasAllParameters(MezzeRecord record) {
        for (String param : parameterNames) {
            // Skip hidden dictionary params — these are injected by the compiler
            // for dictionary passing and should not affect partial application checks.
            if (com.mezze.language.ast.MagicParams.INSTANCE.isMagic(param)) {
                continue;
            }
            // Check if param is already provided via partial args
            if (partialArgs != null && partialArgs.containsKey(param)) {
                continue;
            }
            // Check if param is in the record
            if (record.getFieldIndex(param) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Create a partially applied function by adding the given arguments.
     */
    @TruffleBoundary
    public MezzeFunction withPartialArgs(MezzeRecord record) {
        java.util.Map<String, Object> newPartial = partialArgs != null
            ? new java.util.LinkedHashMap<>(partialArgs)
            : new java.util.LinkedHashMap<>();

        // Add arguments from the record using shape to get field names
        MezzeRecord.Shape shape = record.getShape();
        for (int i = 0; i < record.getFieldCount(); i++) {
            String fieldName = shape.getFieldName(i);
            Object fieldValue = record.getField(i);
            newPartial.put(fieldName, fieldValue);
        }

        return new MezzeFunction(callTarget, name, closureValues, parameterNames, newPartial);
    }

    /**
     * Build a complete record by merging partial args with new args.
     */
    @TruffleBoundary
    public MezzeRecord buildCompleteRecord(MezzeRecord newArgs) {
        if (partialArgs == null || partialArgs.isEmpty()) {
            return newArgs;
        }

        // Merge partial args with new args (new args override)
        java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>(partialArgs);
        MezzeRecord.Shape shape = newArgs.getShape();
        for (int i = 0; i < newArgs.getFieldCount(); i++) {
            merged.put(shape.getFieldName(i), newArgs.getField(i));
        }

        // Build array of field names and values in parameter order
        String[] names = new String[parameterNames.length];
        Object[] values = new Object[parameterNames.length];
        for (int i = 0; i < parameterNames.length; i++) {
            names[i] = parameterNames[i];
            values[i] = merged.get(parameterNames[i]);
        }

        return MezzeRecord.fromFields(names, values);
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
    @InliningCutoff
    public String toString() {
        return "<function " + name + ">";
    }
}
