package com.mezze.interpreter.v2.nodes.control;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * If-let binding node for match guard evaluation.
 *
 * Evaluates an expression and tries to match it against a pattern.
 * If the pattern matches, binds variables to the frame and returns true.
 * Otherwise returns false (failure).
 *
 * Example: `if let Some { val = x } = lookup { key = k }`
 * - Evaluates `lookup { key = k }`
 * - If result is `Some { val = ... }`, binds `x` to the val field
 * - Returns true if matched, false otherwise
 *
 * Optimized for JIT compilation:
 * - Pre-computed pattern info at construction time
 * - Shape caching for record field access
 * - @ExplodeLoop for field binding
 */
@NodeInfo(shortName = "if-let-binding")
public final class IfLetBindingNode extends MezzeNode {

    @Child
    private MezzeNode expressionNode;

    // Pattern type: VARIANT (0), RECORD (1), WILDCARD (2)
    @CompilationFinal
    private final int patternType;

    // For variant patterns: expected tag (from constructor name)
    @CompilationFinal
    private final int expectedTag;

    // For variant/record patterns: field names to extract
    @CompilationFinal(dimensions = 1)
    private final String[] fieldNames;

    // Frame slots to bind extracted values
    @CompilationFinal(dimensions = 1)
    private final int[] fieldSlots;

    // For wildcard patterns: slot to bind entire value, or -1
    @CompilationFinal
    private final int valueSlot;

    // Cached shape for fast field lookup
    @CompilationFinal
    private MezzeRecord.Shape cachedShape;

    @CompilationFinal(dimensions = 1)
    private int[] cachedFieldIndices;

    // Pattern type constants
    private static final int PATTERN_VARIANT = 0;
    private static final int PATTERN_RECORD = 1;
    private static final int PATTERN_WILDCARD = 2;

    /**
     * Create a variant pattern if-let binding.
     * Example: `if let Some { val = x } = expr`
     */
    public static IfLetBindingNode variantPattern(
            MezzeNode expressionNode, int expectedTag,
            String[] fieldNames, int[] fieldSlots) {
        return new IfLetBindingNode(expressionNode, PATTERN_VARIANT, expectedTag,
                                    fieldNames, fieldSlots, -1);
    }

    /**
     * Create a record pattern if-let binding.
     * Example: `if let { name, age } = expr`
     */
    public static IfLetBindingNode recordPattern(
            MezzeNode expressionNode, String[] fieldNames, int[] fieldSlots) {
        return new IfLetBindingNode(expressionNode, PATTERN_RECORD, -1,
                                    fieldNames, fieldSlots, -1);
    }

    /**
     * Create a wildcard/variable pattern if-let binding.
     * Example: `if let x = expr` (always matches)
     */
    public static IfLetBindingNode wildcardPattern(MezzeNode expressionNode, int valueSlot) {
        return new IfLetBindingNode(expressionNode, PATTERN_WILDCARD, -1,
                                    null, null, valueSlot);
    }

    private IfLetBindingNode(MezzeNode expressionNode, int patternType, int expectedTag,
                             String[] fieldNames, int[] fieldSlots, int valueSlot) {
        this.expressionNode = expressionNode;
        this.patternType = patternType;
        this.expectedTag = expectedTag;
        this.fieldNames = fieldNames;
        this.fieldSlots = fieldSlots;
        this.valueSlot = valueSlot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Returns Boolean true/false indicating match success
        return tryMatch(frame);
    }

    /**
     * Try to match the expression against the pattern.
     * @return true if matched (bindings made), false otherwise
     */
    public boolean tryMatch(VirtualFrame frame) {
        Object value = expressionNode.execute(frame);

        return switch (patternType) {
            case PATTERN_VARIANT -> tryMatchVariant(frame, value);
            case PATTERN_RECORD -> tryMatchRecord(frame, value);
            case PATTERN_WILDCARD -> tryMatchWildcard(frame, value);
            default -> false;
        };
    }

    private boolean tryMatchVariant(VirtualFrame frame, Object value) {
        if (!(value instanceof MezzeVariant variant)) {
            return false;
        }

        // Check tag matches
        if (variant.getTag() != expectedTag) {
            return false;
        }

        // No payload bindings needed
        if (fieldNames == null || fieldNames.length == 0) {
            return true;
        }

        // Extract payload record fields
        Object payloadValue = variant.getPayloadValue();
        if (!(payloadValue instanceof MezzeRecord record)) {
            return false;
        }

        return bindRecordFields(frame, record);
    }

    private boolean tryMatchRecord(VirtualFrame frame, Object value) {
        if (!(value instanceof MezzeRecord record)) {
            return false;
        }

        return bindRecordFields(frame, record);
    }

    private boolean tryMatchWildcard(VirtualFrame frame, Object value) {
        if (valueSlot >= 0) {
            frame.setObject(valueSlot, value);
        }
        return true;
    }

    @ExplodeLoop
    private boolean bindRecordFields(VirtualFrame frame, MezzeRecord record) {
        MezzeRecord.Shape shape = record.getShape();

        // Fast path: use cached field indices
        if (shape == cachedShape && cachedFieldIndices != null) {
            return bindFieldsCached(frame, record);
        }

        // Slow path: compute and cache field indices
        return bindFieldsSlow(frame, record);
    }

    @ExplodeLoop
    private boolean bindFieldsCached(VirtualFrame frame, MezzeRecord record) {
        for (int i = 0; i < fieldNames.length; i++) {
            int fieldIndex = cachedFieldIndices[i];
            if (fieldIndex < 0) {
                return false; // Field not found in record
            }
            // Skip binding for slots marked as -1 (e.g., wildcard patterns)
            int slot = fieldSlots[i];
            if (slot >= 0) {
                frame.setObject(slot, record.getField(fieldIndex));
            }
        }
        return true;
    }

    private boolean bindFieldsSlow(VirtualFrame frame, MezzeRecord record) {
        int[] indices = computeFieldIndices(record);
        if (indices == null) {
            return false;
        }

        // Bind values, skipping -1 slots (no binding needed)
        for (int i = 0; i < fieldNames.length; i++) {
            int slot = fieldSlots[i];
            if (slot >= 0) {
                frame.setObject(slot, record.getField(indices[i]));
            }
        }
        return true;
    }

    @TruffleBoundary
    private int[] computeFieldIndices(MezzeRecord record) {
        MezzeRecord.Shape shape = record.getShape();
        int[] indices = new int[fieldNames.length];

        for (int i = 0; i < fieldNames.length; i++) {
            indices[i] = shape.findFieldIndex(fieldNames[i]);
            if (indices[i] < 0) {
                return null; // Field not found
            }
        }

        // Cache for next time
        this.cachedShape = shape;
        this.cachedFieldIndices = indices;
        return indices;
    }
}
