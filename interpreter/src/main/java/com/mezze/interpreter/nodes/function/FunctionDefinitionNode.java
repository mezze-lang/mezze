package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeFunction;
import com.mezze.language.typedast.Hash;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Function definition node - MAXIMUM PERFORMANCE.
 *
 * Key optimizations:
 * 1. Shared empty arrays - zero allocation for simple functions
 * 2. @ExplodeLoop unrolls closure capture completely
 * 3. @CompilationFinal arrays enable JIT constant folding
 * 4. Lazy CallTarget creation (once only)
 * 5. Uses MezzeFunctionRootNode with record argument unpacking
 *
 * When executed, creates a MezzeFunction by:
 * 1. Creating the CallTarget (lazily, once)
 * 2. Capturing closure variables from the current frame
 */
@NodeInfo(shortName = "function-def")
public final class FunctionDefinitionNode extends MezzeNode {

    // Shared empty arrays - avoid allocation for functions with no closure/params
    private static final Object[] EMPTY_CLOSURE = new Object[0];
    private static final int[] EMPTY_SLOTS = new int[0];
    private static final String[] EMPTY_STRINGS = new String[0];
    private static final MezzeFunction.AbilitySlotRequirement[] EMPTY_ABILITY_REQS = new MezzeFunction.AbilitySlotRequirement[0];

    // Slot kind constants (must match ReadLocalNode)
    public static final int KIND_LONG = 0;
    public static final int KIND_DOUBLE = 1;
    public static final int KIND_BOOLEAN = 2;
    public static final int KIND_OBJECT = 3;

    private final String name;

    // Content hash for CallTarget caching (null = no caching)
    @CompilationFinal
    private final Hash contentHash;

    @CompilationFinal(dimensions = 1)
    private final int[] closureSlots;

    // Types of closure slots for typed reads (0=Long, 1=Double, 2=Boolean, 3=Object)
    @CompilationFinal(dimensions = 1)
    private final int[] closureKinds;

    @CompilationFinal(dimensions = 1)
    private final String[] parameterNames;

    @CompilationFinal
    private final int closureCount;

    @CompilationFinal
    private final int parameterCount;

    @Child
    private MezzeNode bodyNode;

    @CompilationFinal
    private final FrameDescriptor frameDescriptor;

    // Ability operation slot requirements for polymorphic functions
    // Each entry describes where an ability operation function should be stored
    @CompilationFinal(dimensions = 1)
    private final MezzeFunction.AbilitySlotRequirement[] abilitySlotRequirements;

    @CompilationFinal
    private CallTarget callTarget;

    /**
     * Full constructor with typed closure slots and ability slot requirements.
     */
    public FunctionDefinitionNode(
            String name,
            Hash contentHash,
            int[] closureSlots,
            int[] closureKinds,
            String[] parameterNames,
            MezzeNode bodyNode,
            FrameDescriptor frameDescriptor,
            MezzeFunction.AbilitySlotRequirement[] abilitySlotRequirements
    ) {
        this.name = name;
        this.contentHash = contentHash;
        this.closureSlots = closureSlots != null && closureSlots.length > 0 ? closureSlots : EMPTY_SLOTS;
        this.closureKinds = closureKinds != null && closureKinds.length > 0 ? closureKinds : null;
        this.parameterNames = parameterNames != null && parameterNames.length > 0 ? parameterNames : EMPTY_STRINGS;
        this.closureCount = this.closureSlots.length;
        this.parameterCount = this.parameterNames.length;
        this.bodyNode = bodyNode;
        this.frameDescriptor = frameDescriptor;
        this.abilitySlotRequirements = abilitySlotRequirements != null ? abilitySlotRequirements : EMPTY_ABILITY_REQS;
    }

    private void initializeCallTarget(MezzeLanguage language) {
        // Extract ability slot indices from requirements
        int[] abilitySlots = null;
        if (abilitySlotRequirements.length > 0) {
            abilitySlots = new int[abilitySlotRequirements.length];
            for (int i = 0; i < abilitySlotRequirements.length; i++) {
                abilitySlots[i] = abilitySlotRequirements[i].slotIndex;
            }
        }

        // Use MezzeFunctionRootNode for proper record argument unpacking
        MezzeFunctionRootNode rootNode = new MezzeFunctionRootNode(
            language,
            frameDescriptor,
            bodyNode,
            name,
            parameterNames,
            closureSlots,
            null,  // slotTypes - auto-detect
            abilitySlots
        );
        callTarget = rootNode.getCallTarget();
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        // Lazily create CallTarget if not already done
        if (callTarget == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            createCallTarget();
        }

        // Fast path: no closure variables and no ability requirements
        if (closureCount == 0 && abilitySlotRequirements.length == 0) {
            return new MezzeFunction(callTarget, name, EMPTY_CLOSURE, parameterNames);
        }

        // Fast path: no closure variables but has ability requirements
        if (closureCount == 0) {
            return new MezzeFunction(callTarget, name, EMPTY_CLOSURE, parameterNames, null, abilitySlotRequirements);
        }

        // Capture closure variables from current frame
        // @ExplodeLoop unrolls this based on closureCount
        // Always use frame.getValue() for captures — it works regardless of the
        // slot's current type tag (LONG, DOUBLE, OBJECT, etc.). Using typed reads
        // (e.g., getObject on a LONG slot) can produce null when the slot tag doesn't
        // match, especially after Truffle's type specialization changes the slot tag.
        Object[] closureValues = new Object[closureCount];
        for (int i = 0; i < closureCount; i++) {
            closureValues[i] = frame.getValue(closureSlots[i]);
        }

        // Pass ability slot requirements if present
        if (abilitySlotRequirements.length > 0) {
            return new MezzeFunction(callTarget, name, closureValues, parameterNames, null, abilitySlotRequirements);
        }

        return new MezzeFunction(callTarget, name, closureValues, parameterNames);
    }

    private void createCallTarget() {
        // If we have a content hash, try to use the cached CallTarget
        if (contentHash != null) {
            CallTarget cached = getCachedCallTarget();
            if (cached != null) {
                callTarget = cached;
                return;
            }
        }

        // Extract ability slot indices from requirements
        int[] abilitySlots = null;
        if (abilitySlotRequirements.length > 0) {
            abilitySlots = new int[abilitySlotRequirements.length];
            for (int i = 0; i < abilitySlotRequirements.length; i++) {
                abilitySlots[i] = abilitySlotRequirements[i].slotIndex;
            }
        }

        // Create new CallTarget with ability slots
        MezzeFunctionRootNode rootNode = new MezzeFunctionRootNode(
            MezzeLanguage.get(this),
            frameDescriptor,
            bodyNode,
            name,
            parameterNames,
            closureSlots,
            null,  // slotTypes - auto-detect
            abilitySlots
        );
        callTarget = rootNode.getCallTarget();

        // Cache for future use
        if (contentHash != null) {
            cacheCallTarget(callTarget);
        }
    }

    @TruffleBoundary
    private CallTarget getCachedCallTarget() {
        // Check Language-level cache first (shared across all tests/contexts)
        var langCt = MezzeLanguage.get(this).getCachedStdlibCallTarget(contentHash);
        if (langCt != null) return langCt;
        // Fall back to per-context cache
        MezzeContext context = MezzeContext.get(this);
        return context.getCachedCallTarget(contentHash);
    }

    @TruffleBoundary
    private void cacheCallTarget(CallTarget target) {
        // Cache on Language (shared across tests) — prevents CallTarget accumulation on Engine
        MezzeLanguage.get(this).getCachedStdlibCallTargetCache().put(contentHash, target);
    }

    public String getName() {
        return name;
    }

    public int getClosureCount() {
        return closureCount;
    }

    public int getParameterCount() {
        return parameterCount;
    }

    public String[] getParameterNames() {
        return parameterNames;
    }

    /**
     * Read a value from a frame slot using compile-time type info.
     * Falls back to getValue() if the slot type doesn't match (can happen with
     * closure capture across threads where Truffle's type specialization differs).
     */
    private static Object readSlotTyped(VirtualFrame frame, int slot, int kind) {
        // Try the declared kind first, then try all typed reads as fallback.
        // This handles captures where the parent wrote LONG but we read OBJECT.
        try {
            return switch (kind) {
                case KIND_LONG -> frame.getLong(slot);
                case KIND_DOUBLE -> frame.getDouble(slot);
                case KIND_BOOLEAN -> frame.getBoolean(slot);
                default -> frame.getObject(slot);
            };
        } catch (FrameSlotTypeException e) {
            // Slot was written with a different kind — try typed reads in order
            try { return frame.getLong(slot); } catch (FrameSlotTypeException e2) { /* not long */ }
            try { return frame.getDouble(slot); } catch (FrameSlotTypeException e2) { /* not double */ }
            try { return frame.getObject(slot); } catch (FrameSlotTypeException e2) { /* not object */ }
            // Last resort
            return frame.getValue(slot);
        }
    }
}
