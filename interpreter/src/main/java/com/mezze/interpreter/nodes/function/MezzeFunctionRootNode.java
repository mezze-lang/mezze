package com.mezze.interpreter.nodes.function;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

/**
 * Function root node with record argument unpacking - MAXIMUM PERFORMANCE.
 *
 * Mezze functions receive a single record argument { x = 1, y = 2 }
 * and this node unpacks field values directly into typed frame slots.
 *
 * Key optimizations:
 * 1. @CompilationFinal parameter names - JIT constant folds field lookups
 * 2. Shape-cached field indices for O(1) field access after warmup
 * 3. @ExplodeLoop unrolls parameter binding completely
 * 4. Typed slot binding (Long/Double/Boolean/Object) avoids boxing
 * 5. Inline closure binding - no array copy overhead
 * 6. Zero-allocation hot path after JIT compilation
 *
 * Slot layout: [closure0, closure1, ..., param0, param1, ...]
 */
@NodeInfo(language = "mezze", description = "Mezze function with record unpacking")
public final class MezzeFunctionRootNode extends RootNode {

    private final String name;

    @Child private MezzeNode bodyNode;

    // Parameter names for record field extraction
    @CompilationFinal(dimensions = 1)
    private final String[] parameterNames;

    // Closure variable slots in parent frame
    @CompilationFinal(dimensions = 1)
    private final int[] closureSlots;

    // Slot types: 0=Long, 1=Double, 2=Boolean, 3=Object
    @CompilationFinal(dimensions = 1)
    private final int[] slotTypes;

    // Ability operation slot indices - where to write passed ability operations
    // Layout: abilities are passed after the record argument
    @CompilationFinal(dimensions = 1)
    private final int[] abilitySlots;

    // Cached field indices for fast record access (populated after shape stabilizes)
    @CompilationFinal(dimensions = 1)
    private int[] cachedFieldIndices;

    // Cached shape for field index validity check
    @CompilationFinal
    private Object cachedShape;

    @CompilationFinal
    private final int closureCount;

    @CompilationFinal
    private final int parameterCount;

    @CompilationFinal
    private final int abilityCount;

    // Source section for debugger/profiler integration — set after construction by TruffleCompiler
    @CompilationFinal
    private SourceSection sourceSection;

    /**
     * Standard constructor without ability slots.
     */
    public MezzeFunctionRootNode(
            MezzeLanguage language,
            FrameDescriptor frameDescriptor,
            MezzeNode bodyNode,
            String name,
            String[] parameterNames,
            int[] closureSlots,
            int[] slotTypes
    ) {
        this(language, frameDescriptor, bodyNode, name, parameterNames, closureSlots, slotTypes, null);
    }

    /**
     * Full constructor with ability slots for polymorphic functions.
     * Ability operations are passed after the record argument and written to the specified slots.
     */
    public MezzeFunctionRootNode(
            MezzeLanguage language,
            FrameDescriptor frameDescriptor,
            MezzeNode bodyNode,
            String name,
            String[] parameterNames,
            int[] closureSlots,
            int[] slotTypes,
            int[] abilitySlots
    ) {
        super(language, frameDescriptor);
        this.bodyNode = bodyNode;
        this.name = name;
        this.parameterNames = parameterNames != null ? parameterNames : EMPTY_STRINGS;
        this.closureSlots = closureSlots != null ? closureSlots : EMPTY_INTS;
        this.slotTypes = slotTypes != null ? slotTypes : EMPTY_INTS;
        this.abilitySlots = abilitySlots != null ? abilitySlots : EMPTY_INTS;
        this.closureCount = this.closureSlots.length;
        this.parameterCount = this.parameterNames.length;
        this.abilityCount = this.abilitySlots.length;
    }

    /**
     * Set the source section for this root node. Called by TruffleCompiler after construction.
     * Enables debuggers and profilers (e.g. Chrome DevTools) to show source locations in stack traces.
     */
    public void setSourceSection(SourceSection section) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        this.sourceSection = section;
    }

    @Override
    public SourceSection getSourceSection() {
        return sourceSection;
    }

    @Override
    public boolean isInternal() {
        // Functions with source sections are user-visible (not internal/framework)
        return sourceSection == null;
    }

    private static final String[] EMPTY_STRINGS = new String[0];
    private static final int[] EMPTY_INTS = new int[0];

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();

        // Bind closure variables first (slots 0..closureCount-1)
        // Closure values are passed as individual arguments before the record
        for (int i = 0; i < closureCount; i++) {
            if (i < args.length) {
                bindSlot(frame, i, args[i]);
            }
        }

        // Unpack record argument to parameter slots (slots closureCount..closureCount+paramCount-1)
        if (parameterCount > 0 && args.length > closureCount) {
            Object recordArg = args[closureCount];
            if (recordArg instanceof MezzeRecord record) {
                unpackRecordFast(frame, record);
            } else {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new IllegalArgumentException("Expected record argument for fn=" + name +
                    " paramNames=" + java.util.Arrays.toString(parameterNames) +
                    " closureCount=" + closureCount +
                    " args.length=" + args.length +
                    ", got: " + (recordArg == null ? "null" : recordArg.getClass().getName()));
            }
        }

        // Bind ability operation functions (passed after the record argument)
        // Layout: [closure0, ..., closureN, recordArg, abilityOp0, ..., abilityOpM]
        if (abilityCount > 0) {
            int abilityArgStart = closureCount + 1; // After closure args and record arg
            for (int i = 0; i < abilityCount; i++) {
                int argIndex = abilityArgStart + i;
                if (argIndex < args.length) {
                    int slotIndex = abilitySlots[i];
                    frame.setObject(slotIndex, args[argIndex]);
                }
            }
        }

        // Execute body with tail call loop
        while (true) {
            try {
                return bodyNode.execute(frame);
            } catch (TailCallException e) {
                // Tail call: re-execute with new arguments
                Object[] newArgs = e.getArguments();
                if (parameterCount > 0 && newArgs.length > 0) {
                    Object recordArg = newArgs[0];
                    if (recordArg instanceof MezzeRecord record) {
                        unpackRecordFast(frame, record);
                    }
                }
            }
        }
    }

    /**
     * Fast path: unpack record using cached field indices.
     * After shape stabilizes, this becomes O(1) per field.
     */
    @ExplodeLoop
    private void unpackRecordFast(VirtualFrame frame, MezzeRecord record) {
        Object shape = record.getShape();

        // Check if we can use cached indices
        if (shape == cachedShape && cachedFieldIndices != null) {
            // FAST PATH: Use cached field indices - zero overhead after JIT
            for (int i = 0; i < parameterCount; i++) {
                int fieldIndex = cachedFieldIndices[i];
                int slotIndex = closureCount + i;
                if (fieldIndex >= 0) {
                    Object value = record.getField(fieldIndex);
                    bindSlot(frame, slotIndex, value);
                }
            }
        } else {
            // SLOW PATH: First call or shape changed - compute and cache indices
            CompilerDirectives.transferToInterpreterAndInvalidate();
            unpackRecordSlow(frame, record, shape);
        }
    }

    /**
     * Slow path: compute field indices and cache them.
     * Split into two parts - boundary for index computation, inline for frame binding.
     */
    private void unpackRecordSlow(VirtualFrame frame, MezzeRecord record, Object shape) {
        // Compute indices in boundary method (can't access frame there)
        Object[] result = computeFieldIndicesAndValues(record, shape);
        int[] indices = (int[]) result[0];
        Object[] values = (Object[]) result[1];

        // Bind values to frame slots (must be inline, not boundary)
        for (int i = 0; i < parameterCount; i++) {
            int slotIndex = closureCount + i;
            if (indices[i] >= 0) {
                frame.setObject(slotIndex, values[i]);
            }
        }

        // Cache indices for next call
        this.cachedFieldIndices = indices;
        this.cachedShape = shape;
    }

    /**
     * Compute field indices and extract values - can be @TruffleBoundary.
     */
    @TruffleBoundary
    private Object[] computeFieldIndicesAndValues(MezzeRecord record, Object shape) {
        int[] indices = new int[parameterCount];
        Object[] values = new Object[parameterCount];

        for (int i = 0; i < parameterCount; i++) {
            String paramName = parameterNames[i];
            int fieldIndex = record.getFieldIndex(paramName);
            indices[i] = fieldIndex;
            if (fieldIndex >= 0) {
                values[i] = record.getField(fieldIndex);
            }
        }

        return new Object[] { indices, values };
    }

    /**
     * Bind value to typed slot - JIT eliminates switch after profiling.
     */
    @ExplodeLoop
    private void bindSlot(VirtualFrame frame, int slotIndex, Object value) {
        if (slotIndex < slotTypes.length) {
            int slotType = slotTypes[slotIndex];
            switch (slotType) {
                case 0 -> { // KIND_LONG
                    if (value instanceof Long l) {
                        frame.setLong(slotIndex, l);
                    } else {
                        frame.setObject(slotIndex, value);
                    }
                }
                case 1 -> { // KIND_DOUBLE
                    if (value instanceof Double d) {
                        frame.setDouble(slotIndex, d);
                    } else if (value instanceof Long l) {
                        frame.setDouble(slotIndex, l.doubleValue());
                    } else {
                        frame.setObject(slotIndex, value);
                    }
                }
                case 2 -> { // KIND_BOOLEAN
                    if (value instanceof Boolean b) {
                        frame.setBoolean(slotIndex, b);
                    } else {
                        frame.setObject(slotIndex, value);
                    }
                }
                default -> frame.setObject(slotIndex, value);
            }
        } else {
            frame.setObject(slotIndex, value);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "MezzeFunctionRootNode[" + name + "]";
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

    public MezzeNode getBodyNode() {
        return bodyNode;
    }
}
