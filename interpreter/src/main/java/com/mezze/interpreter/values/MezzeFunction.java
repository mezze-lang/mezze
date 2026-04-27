package com.mezze.interpreter.values;

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

    /**
     * Assumption for inlining stability.
     * Invalidated when the function is redefined, causing deoptimization
     * of any inlined call sites.
     */
    private final Assumption inlineAssumption;

    /**
     * Ability slot requirements for polymorphic functions with ability constraints.
     * Each entry describes an ability operation that must be provided by the caller.
     * Null for functions without ability constraints.
     */
    @CompilationFinal(dimensions = 1)
    private final AbilitySlotRequirement[] abilitySlotRequirements;

    /**
     * Describes a required ability operation slot.
     * At call time, the caller must provide the operation function based on resolved abilities.
     */
    public static final class AbilitySlotRequirement {
        public final int constraintIndex;  // Index in the function's ability constraints
        public final String abilityName;   // e.g., "Encode"
        public final String operationName; // e.g., "encode"
        public final int slotIndex;        // Frame slot where the operation goes

        public AbilitySlotRequirement(int constraintIndex, String abilityName, String operationName, int slotIndex) {
            this.constraintIndex = constraintIndex;
            this.abilityName = abilityName;
            this.operationName = operationName;
            this.slotIndex = slotIndex;
        }
    }

    private static final Object[] EMPTY_CLOSURE = new Object[0];
    private static final String[] EMPTY_PARAMS = new String[0];
    private static final AbilitySlotRequirement[] EMPTY_ABILITY_SLOTS = new AbilitySlotRequirement[0];

    public MezzeFunction(CallTarget callTarget, String name, Object[] closureValues) {
        this(callTarget, name, closureValues, EMPTY_PARAMS, null, null);
    }

    public MezzeFunction(CallTarget callTarget, String name, Object[] closureValues, String[] parameterNames) {
        this(callTarget, name, closureValues, parameterNames, null, null);
    }

    public MezzeFunction(CallTarget callTarget, String name, Object[] closureValues,
                         String[] parameterNames, java.util.Map<String, Object> partialArgs) {
        this(callTarget, name, closureValues, parameterNames, partialArgs, null);
    }

    /**
     * Full constructor with ability slot requirements.
     */
    public MezzeFunction(CallTarget callTarget, String name, Object[] closureValues,
                         String[] parameterNames, java.util.Map<String, Object> partialArgs,
                         AbilitySlotRequirement[] abilitySlotRequirements) {
        this.callTarget = callTarget;
        this.name = name;
        this.closureValues = closureValues != null ? closureValues : EMPTY_CLOSURE;
        this.closureLength = this.closureValues.length;
        this.parameterNames = parameterNames != null ? parameterNames : EMPTY_PARAMS;
        this.partialArgs = partialArgs;
        this.inlineAssumption = Truffle.getRuntime().createAssumption("inline:" + name);
        this.abilitySlotRequirements = abilitySlotRequirements != null ? abilitySlotRequirements : EMPTY_ABILITY_SLOTS;
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

    public boolean hasPartialArgs() {
        return partialArgs != null && !partialArgs.isEmpty();
    }

    /**
     * Get ability slot requirements for this function.
     * Returns empty array if this function has no ability constraints.
     */
    public AbilitySlotRequirement[] getAbilitySlotRequirements() {
        return abilitySlotRequirements;
    }

    /**
     * Check if this function requires ability operations to be provided by the caller.
     */
    public boolean hasAbilityRequirements() {
        return abilitySlotRequirements.length > 0;
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

        return new MezzeFunction(callTarget, name, closureValues, parameterNames, newPartial, abilitySlotRequirements);
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
