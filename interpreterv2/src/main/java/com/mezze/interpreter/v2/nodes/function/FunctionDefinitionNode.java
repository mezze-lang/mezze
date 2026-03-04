package com.mezze.interpreter.v2.nodes.function;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.runtime.MezzeContext;
import com.mezze.interpreter.v2.runtime.MezzeLanguage;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotTypeException;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
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

    private final String name;

    // Content hash for CallTarget caching (null = no caching)
    @CompilationFinal
    private final String contentHash;

    @CompilationFinal(dimensions = 1)
    private final int[] closureSlots;

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

    @CompilationFinal
    private CallTarget callTarget;

    /**
     * Full constructor with parameter names for record unpacking.
     */
    public FunctionDefinitionNode(
            String name,
            int[] closureSlots,
            String[] parameterNames,
            MezzeNode bodyNode,
            FrameDescriptor frameDescriptor
    ) {
        this(name, null, closureSlots, parameterNames, bodyNode, frameDescriptor);
    }

    /**
     * Constructor with content hash for CallTarget caching.
     */
    public FunctionDefinitionNode(
            String name,
            String contentHash,
            int[] closureSlots,
            String[] parameterNames,
            MezzeNode bodyNode,
            FrameDescriptor frameDescriptor
    ) {
        this.name = name;
        this.contentHash = contentHash;
        this.closureSlots = closureSlots != null && closureSlots.length > 0 ? closureSlots : EMPTY_SLOTS;
        this.parameterNames = parameterNames != null && parameterNames.length > 0 ? parameterNames : EMPTY_STRINGS;
        this.closureCount = this.closureSlots.length;
        this.parameterCount = this.parameterNames.length;
        this.bodyNode = bodyNode;
        this.frameDescriptor = frameDescriptor;
    }

    /**
     * Backwards compatible constructor (uses parameter count, no names).
     * Only for simple cases where record unpacking isn't needed.
     */
    public FunctionDefinitionNode(
            String name,
            int[] closureSlots,
            int parameterCount,
            MezzeNode bodyNode,
            FrameDescriptor frameDescriptor
    ) {
        this.name = name;
        this.contentHash = null;
        this.closureSlots = closureSlots != null && closureSlots.length > 0 ? closureSlots : EMPTY_SLOTS;
        this.parameterNames = EMPTY_STRINGS;
        this.closureCount = this.closureSlots.length;
        this.parameterCount = parameterCount;
        this.bodyNode = bodyNode;
        this.frameDescriptor = frameDescriptor;
    }

    /**
     * Create with eager CallTarget initialization.
     */
    public static FunctionDefinitionNode createEager(
            MezzeLanguage language,
            String name,
            int[] closureSlots,
            String[] parameterNames,
            MezzeNode bodyNode,
            FrameDescriptor frameDescriptor
    ) {
        FunctionDefinitionNode node = new FunctionDefinitionNode(
            name, closureSlots, parameterNames, bodyNode, frameDescriptor
        );
        node.initializeCallTarget(language);
        return node;
    }

    private void initializeCallTarget(MezzeLanguage language) {
        // Use MezzeFunctionRootNode for proper record argument unpacking
        MezzeFunctionRootNode rootNode = new MezzeFunctionRootNode(
            language,
            frameDescriptor,
            bodyNode,
            name,
            parameterNames,
            closureSlots,
            null  // slotTypes - auto-detect
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

        // Fast path: no closure variables
        if (closureCount == 0) {
            return new MezzeFunction(callTarget, name, EMPTY_CLOSURE, parameterNames);
        }

        // Capture closure variables from current frame
        // @ExplodeLoop unrolls this based on closureCount
        // Handle different slot types (Object, Long, Double, Boolean)
        Object[] closureValues = new Object[closureCount];
        for (int i = 0; i < closureCount; i++) {
            closureValues[i] = readSlotValue(frame, closureSlots[i]);
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

        // Create new CallTarget
        MezzeFunctionRootNode rootNode = new MezzeFunctionRootNode(
            MezzeLanguage.get(this),
            frameDescriptor,
            bodyNode,
            name,
            parameterNames,
            closureSlots,
            null  // slotTypes - auto-detect
        );
        callTarget = rootNode.getCallTarget();

        // Cache for future use
        if (contentHash != null) {
            cacheCallTarget(callTarget);
        }
    }

    @TruffleBoundary
    private CallTarget getCachedCallTarget() {
        MezzeContext context = MezzeContext.get(this);
        return context.getCachedCallTarget(contentHash);
    }

    @TruffleBoundary
    private void cacheCallTarget(CallTarget target) {
        MezzeContext context = MezzeContext.get(this);
        context.cacheCallTarget(contentHash, target);
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
     * Read a value from a frame slot, handling different slot types.
     * Slots can hold Long, Double, Boolean, or Object values.
     */
    private static Object readSlotValue(VirtualFrame frame, int slot) {
        try {
            return frame.getObject(slot);
        } catch (FrameSlotTypeException e1) {
            // Not an Object slot, try primitive types
        }
        try {
            return frame.getLong(slot);
        } catch (FrameSlotTypeException e2) {
            // Not a Long slot
        }
        try {
            return frame.getDouble(slot);
        } catch (FrameSlotTypeException e3) {
            // Not a Double slot
        }
        try {
            return frame.getBoolean(slot);
        } catch (FrameSlotTypeException e4) {
            // Not a Boolean slot
        }
        // Slot is empty or has unsupported type
        return null;
    }
}
