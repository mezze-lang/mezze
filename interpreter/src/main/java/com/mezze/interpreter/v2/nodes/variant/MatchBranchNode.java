package com.mezze.interpreter.v2.nodes.variant;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * A single branch in a match expression - OPTIMIZED.
 *
 * Key optimizations:
 * 1. @ExplodeLoop for payload binding
 * 2. Pre-computed payload slot count
 * 3. Primitive-typed literal comparison (avoids boxing)
 * 4. Shared empty payload slots array
 *
 * Handles:
 * - Variant patterns: Some(x) -> ...
 * - Literal patterns: 1 -> ..., "hello" -> ...
 * - Wildcard patterns: _ -> ..., x -> ...
 */
@NodeInfo(shortName = "match-branch")
public final class MatchBranchNode extends MezzeNode {

    // Shared empty array to avoid allocation
    private static final int[] EMPTY_SLOTS = new int[0];

    // For variant patterns: the tag to match, or -1 for wildcard
    @CompilationFinal
    private final int tag;

    // Frame slots to bind payload values (for variant patterns)
    @CompilationFinal(dimensions = 1)
    private final int[] payloadSlots;

    // Pre-computed for @ExplodeLoop
    @CompilationFinal
    private final int payloadSlotCount;

    // Frame slot to bind the entire matched value (for wildcard with binding)
    @CompilationFinal
    private final int valueSlot;

    // For literal patterns: the value to match (stored typed for fast comparison)
    @CompilationFinal
    private final Object literalValue;

    // Type tag for literal: 0=Long, 1=Double, 2=Boolean, 3=String, 4=Object
    @CompilationFinal
    private final int literalType;

    // Typed literal values to avoid boxing in hot path
    @CompilationFinal
    private final long literalLong;

    @CompilationFinal
    private final double literalDouble;

    @CompilationFinal
    private final boolean literalBoolean;

    @Child
    private MezzeNode bodyNode;

    // Optional guard node - if present, must evaluate to true for match
    @Child
    private MezzeNode guardNode;

    // Optional if-let bindings - all must match for pattern to succeed
    @Children
    private final com.mezze.interpreter.v2.nodes.control.IfLetBindingNode[] ifLetBindings;

    // Pre-computed for @ExplodeLoop
    @CompilationFinal
    private final int ifLetBindingCount;

    // For variant patterns: field names to extract from payload record
    // (for patterns like `Some { val }`, this contains ["val"])
    @CompilationFinal(dimensions = 1)
    private final String[] variantPayloadFieldNames;

    // Cached shape and field indices for fast variant payload extraction
    @CompilationFinal
    private MezzeRecord.Shape cachedVariantPayloadShape;

    @CompilationFinal(dimensions = 1)
    private int[] cachedVariantPayloadFieldIndices;

    // Structured pattern payload for optimized binding (Option, Result, List patterns)
    @CompilationFinal
    private final PatternPayload patternPayload;

    /**
     * Create a variant pattern branch with field names for proper record extraction.
     * For patterns like `Some { val }`, fieldNames = ["val"], fieldSlots = [slot_for_val].
     */
    public static MatchBranchNode variantPatternWithFieldNames(
            int tag, String[] fieldNames, int[] fieldSlots, MezzeNode bodyNode) {
        return new MatchBranchNode(tag, fieldSlots != null && fieldSlots.length > 0 ? fieldSlots : EMPTY_SLOTS,
                                   -1, null, bodyNode, fieldNames);
    }

    /**
     * Create a wildcard branch (matches anything).
     */
    public static MatchBranchNode wildcard(int valueSlot, MezzeNode bodyNode) {
        return new MatchBranchNode(-1, EMPTY_SLOTS, valueSlot, null, bodyNode, null);
    }

    /**
     * Create a wildcard branch with no binding.
     */
    public static MatchBranchNode wildcardNoBinding(MezzeNode bodyNode) {
        return new MatchBranchNode(-1, EMPTY_SLOTS, -1, null, bodyNode, null);
    }

    /**
     * Create a literal pattern branch.
     */
    public static MatchBranchNode literalPattern(Object literalValue, MezzeNode bodyNode) {
        return new MatchBranchNode(-2, EMPTY_SLOTS, -1, literalValue, bodyNode,
                EMPTY_SLOTS, -1, false, 0);
    }

    /**
     * Create an array pattern branch.
     * Tag = -3 indicates array pattern.
     *
     * @param elementSlots Frame slots for each element binding
     * @param restSlot Frame slot for rest pattern, or -1 for no rest
     * @param hasRestPattern Whether pattern has ...rest
     * @param bodyNode The body to execute when matched
     */
    public static MatchBranchNode seqPattern(int[] elementSlots, int restSlot,
                                               boolean hasRestPattern, MezzeNode bodyNode) {
        int requiredLength = elementSlots != null ? elementSlots.length : 0;
        return new MatchBranchNode(-3, EMPTY_SLOTS, -1, null, bodyNode,
                elementSlots != null ? elementSlots : EMPTY_SLOTS, restSlot,
                hasRestPattern, requiredLength,
                null, null, null, null, false, null);
    }

    /**
     * Create a string interpolation pattern branch.
     * Tag = -4 indicates string pattern.
     *
     * @param partTypes Array of LITERAL (0) or CAPTURE (1) for each part
     * @param literals String values for literal parts (null for captures)
     * @param captureSlots Frame slots to bind captured values
     * @param bodyNode Body to execute when matched
     */
    public static MatchBranchNode stringPattern(
            int[] partTypes, String[] literals, int[] captureSlots, MezzeNode bodyNode) {
        return new MatchBranchNode(-4, EMPTY_SLOTS, -1, null, bodyNode,
                EMPTY_SLOTS, -1, false, 0,
                partTypes, literals, captureSlots, null, false, null);
    }

    /**
     * Create a string interpolation pattern branch with guard and if-let bindings.
     * Tag = -4 indicates string pattern.
     */
    public static MatchBranchNode stringPatternWithGuard(
            int[] partTypes, String[] literals, int[] captureSlots, MezzeNode bodyNode,
            MezzeNode guardNode, com.mezze.interpreter.v2.nodes.control.IfLetBindingNode[] ifLetBindings) {
        return new MatchBranchNode(-4, EMPTY_SLOTS, -1, null, bodyNode,
                EMPTY_SLOTS, -1, false, 0,
                partTypes, literals, captureSlots, null, false, null,
                guardNode, ifLetBindings);
    }

    /**
     * Create a record pattern branch.
     * Tag = -5 indicates record pattern.
     *
     * @param fieldNames Names of fields to extract
     * @param fieldSlots Frame slots to bind field values
     * @param isOpen Whether extra fields are allowed
     * @param bodyNode Body to execute when matched
     */
    public static MatchBranchNode recordPattern(
            String[] fieldNames, int[] fieldSlots, boolean isOpen, MezzeNode bodyNode) {
        return new MatchBranchNode(-5, EMPTY_SLOTS, -1, null, bodyNode,
                EMPTY_SLOTS, -1, false, 0,
                null, null, fieldSlots, fieldNames, isOpen, null);
    }

    // ===== FACTORY METHODS WITH GUARDS/IF-LET =====

    /**
     * Create a variant pattern branch with guard and if-let bindings.
     */
    public static MatchBranchNode variantPatternWithGuard(
            int tag, String[] fieldNames, int[] fieldSlots, MezzeNode bodyNode,
            MezzeNode guardNode, com.mezze.interpreter.v2.nodes.control.IfLetBindingNode[] ifLetBindings) {
        return new MatchBranchNode(tag, fieldSlots != null && fieldSlots.length > 0 ? fieldSlots : EMPTY_SLOTS,
                                   -1, null, bodyNode,
                                   EMPTY_SLOTS, -1, false, 0,
                                   null, null, null, null, false, fieldNames,
                                   guardNode, ifLetBindings);
    }

    /**
     * Create a wildcard branch with guard and if-let bindings.
     */
    public static MatchBranchNode wildcardWithGuard(int valueSlot, MezzeNode bodyNode,
            MezzeNode guardNode, com.mezze.interpreter.v2.nodes.control.IfLetBindingNode[] ifLetBindings) {
        return new MatchBranchNode(-1, EMPTY_SLOTS, valueSlot, null, bodyNode,
                                   EMPTY_SLOTS, -1, false, 0,
                                   null, null, null, null, false, null,
                                   guardNode, ifLetBindings);
    }

    /**
     * Create a literal pattern branch with guard.
     */
    public static MatchBranchNode literalPatternWithGuard(Object literalValue, MezzeNode bodyNode,
            MezzeNode guardNode, com.mezze.interpreter.v2.nodes.control.IfLetBindingNode[] ifLetBindings) {
        return new MatchBranchNode(-2, EMPTY_SLOTS, -1, literalValue, bodyNode,
                                   EMPTY_SLOTS, -1, false, 0,
                                   null, null, null, null, false, null,
                                   guardNode, ifLetBindings);
    }

    /**
     * Create a record pattern branch with guard and if-let bindings.
     */
    public static MatchBranchNode recordPatternWithGuard(
            String[] fieldNames, int[] fieldSlots, boolean isOpen, MezzeNode bodyNode,
            MezzeNode guardNode, com.mezze.interpreter.v2.nodes.control.IfLetBindingNode[] ifLetBindings) {
        return new MatchBranchNode(-5, EMPTY_SLOTS, -1, null, bodyNode,
                                   EMPTY_SLOTS, -1, false, 0,
                                   null, null, fieldSlots, fieldNames, isOpen, null,
                                   guardNode, ifLetBindings);
    }

    // ===== ARRAY PATTERN FIELDS =====

    // Frame slots for array element bindings
    @CompilationFinal(dimensions = 1)
    private final int[] arrayElementSlots;

    // Frame slot for rest pattern (...tail), or -1
    @CompilationFinal
    private final int arrayRestSlot;

    // Whether this array pattern has a rest pattern
    @CompilationFinal
    private final boolean arrayHasRest;

    // Required number of elements for this array pattern
    @CompilationFinal
    private final int arrayRequiredLength;

    // ===== STRING PATTERN FIELDS =====

    // Part types: 0=LITERAL, 1=CAPTURE
    @CompilationFinal(dimensions = 1)
    private final int[] stringPartTypes;

    // Literal values for string pattern parts (null for captures)
    @CompilationFinal(dimensions = 1)
    private final String[] stringLiterals;

    // Slots to bind captured string values
    @CompilationFinal(dimensions = 1)
    private final int[] stringCaptureSlots;

    // Pre-computed for fast path: prefix/suffix for single capture patterns
    @CompilationFinal
    private final String stringPrefix;

    @CompilationFinal
    private final String stringSuffix;

    @CompilationFinal
    private final boolean stringHasSingleCapture;

    // ===== RECORD PATTERN FIELDS =====

    // Field names to extract
    @CompilationFinal(dimensions = 1)
    private final String[] recordFieldNames;

    // Whether record pattern is open (allows extra fields)
    @CompilationFinal
    private final boolean recordIsOpen;

    // Cached shape for fast field lookup
    @CompilationFinal
    private MezzeRecord.Shape cachedRecordShape;

    @CompilationFinal(dimensions = 1)
    private int[] cachedRecordFieldIndices;

    // Empty if-let bindings array
    private static final com.mezze.interpreter.v2.nodes.control.IfLetBindingNode[] EMPTY_IFLET = new com.mezze.interpreter.v2.nodes.control.IfLetBindingNode[0];

    private MatchBranchNode(int tag, int[] payloadSlots, int valueSlot,
                            Object literalValue, MezzeNode bodyNode, String[] variantPayloadFieldNames) {
        this(tag, payloadSlots, valueSlot, literalValue, bodyNode,
                EMPTY_SLOTS, -1, false, 0,
                null, null, null, null, false, variantPayloadFieldNames,
                null, EMPTY_IFLET);
    }

    private MatchBranchNode(int tag, int[] payloadSlots, int valueSlot,
                            Object literalValue, MezzeNode bodyNode,
                            int[] arrayElementSlots, int arrayRestSlot,
                            boolean arrayHasRest, int arrayRequiredLength) {
        this(tag, payloadSlots, valueSlot, literalValue, bodyNode,
                arrayElementSlots, arrayRestSlot, arrayHasRest, arrayRequiredLength,
                null, null, null, null, false, null,
                null, EMPTY_IFLET);
    }

    private MatchBranchNode(int tag, int[] payloadSlots, int valueSlot,
                            Object literalValue, MezzeNode bodyNode,
                            int[] arrayElementSlots, int arrayRestSlot,
                            boolean arrayHasRest, int arrayRequiredLength,
                            int[] stringPartTypes, String[] stringLiterals, int[] captureOrFieldSlots,
                            String[] recordFieldNames, boolean recordIsOpen,
                            String[] variantPayloadFieldNames) {
        this(tag, payloadSlots, valueSlot, literalValue, bodyNode,
                arrayElementSlots, arrayRestSlot, arrayHasRest, arrayRequiredLength,
                stringPartTypes, stringLiterals, captureOrFieldSlots,
                recordFieldNames, recordIsOpen, variantPayloadFieldNames,
                null, EMPTY_IFLET);
    }

    private MatchBranchNode(int tag, int[] payloadSlots, int valueSlot,
                            Object literalValue, MezzeNode bodyNode,
                            int[] arrayElementSlots, int arrayRestSlot,
                            boolean arrayHasRest, int arrayRequiredLength,
                            int[] stringPartTypes, String[] stringLiterals, int[] captureOrFieldSlots,
                            String[] recordFieldNames, boolean recordIsOpen,
                            String[] variantPayloadFieldNames,
                            MezzeNode guardNode,
                            com.mezze.interpreter.v2.nodes.control.IfLetBindingNode[] ifLetBindings) {
        this.tag = tag;
        this.payloadSlots = payloadSlots;
        this.payloadSlotCount = payloadSlots.length;
        this.valueSlot = valueSlot;
        this.bodyNode = bodyNode;
        this.variantPayloadFieldNames = variantPayloadFieldNames;
        this.guardNode = guardNode;
        this.ifLetBindings = ifLetBindings != null ? ifLetBindings : EMPTY_IFLET;
        this.ifLetBindingCount = this.ifLetBindings.length;

        // Array pattern fields
        this.arrayElementSlots = arrayElementSlots;
        this.arrayRestSlot = arrayRestSlot;
        this.arrayHasRest = arrayHasRest;
        this.arrayRequiredLength = arrayRequiredLength;

        // Pre-compute typed literal values for fast comparison
        if (literalValue instanceof Long l) {
            this.literalType = 0;
            this.literalLong = l;
            this.literalDouble = 0.0;
            this.literalBoolean = false;
            this.literalValue = null; // Don't need boxed value
        } else if (literalValue instanceof Double d) {
            this.literalType = 1;
            this.literalLong = 0L;
            this.literalDouble = d;
            this.literalBoolean = false;
            this.literalValue = null;
        } else if (literalValue instanceof Boolean b) {
            this.literalType = 2;
            this.literalLong = 0L;
            this.literalDouble = 0.0;
            this.literalBoolean = b;
            this.literalValue = null;
        } else if (literalValue instanceof String s) {
            this.literalType = 3;
            this.literalLong = 0L;
            this.literalDouble = 0.0;
            this.literalBoolean = false;
            this.literalValue = s;
        } else {
            this.literalType = 4;
            this.literalLong = 0L;
            this.literalDouble = 0.0;
            this.literalBoolean = false;
            this.literalValue = literalValue;
        }

        // String pattern fields
        this.stringPartTypes = stringPartTypes;
        this.stringLiterals = stringLiterals;
        this.recordFieldNames = recordFieldNames;
        this.recordIsOpen = recordIsOpen;

        // Handle slots based on pattern type
        // Both string patterns (-4) and record patterns (-5) use captureOrFieldSlots
        if ((tag == -4 || tag == -5) && captureOrFieldSlots != null) {
            // String pattern - capture slots, or Record pattern - field slots
            this.stringCaptureSlots = captureOrFieldSlots;
        } else {
            this.stringCaptureSlots = null;
        }

        // Pre-compute string pattern fast path
        if (stringPartTypes != null && stringPartTypes.length > 0) {
            // Detect "prefix$(x)suffix" patterns
            if (stringPartTypes.length == 3 &&
                stringPartTypes[0] == 0 && stringPartTypes[1] == 1 && stringPartTypes[2] == 0) {
                this.stringPrefix = stringLiterals[0];
                this.stringSuffix = stringLiterals[2];
                this.stringHasSingleCapture = true;
            } else if (stringPartTypes.length == 2 && stringPartTypes[0] == 0 && stringPartTypes[1] == 1) {
                this.stringPrefix = stringLiterals[0];
                this.stringSuffix = null;
                this.stringHasSingleCapture = true;
            } else if (stringPartTypes.length == 2 && stringPartTypes[0] == 1 && stringPartTypes[1] == 0) {
                this.stringPrefix = null;
                this.stringSuffix = stringLiterals[1];
                this.stringHasSingleCapture = true;
            } else if (stringPartTypes.length == 1 && stringPartTypes[0] == 1) {
                this.stringPrefix = null;
                this.stringSuffix = null;
                this.stringHasSingleCapture = true;
            } else {
                this.stringPrefix = null;
                this.stringSuffix = null;
                this.stringHasSingleCapture = false;
            }
        } else {
            this.stringPrefix = null;
            this.stringSuffix = null;
            this.stringHasSingleCapture = false;
        }

        // Initialize Cons/Nil pattern info - precompute for O(1) runtime checks
        initListPatternInfo();

        // Initialize structured pattern payload for optimized variant binding
        // Only for variant patterns (tag >= 0) with field names
        if (tag >= 0 && variantPayloadFieldNames != null && variantPayloadFieldNames.length > 0) {
            this.patternPayload = PatternPayload.detect(variantPayloadFieldNames, payloadSlots);
        } else {
            this.patternPayload = null;
        }
    }

    /**
     * Check if this branch matches the given tag.
     */
    public boolean matches(int variantTag) {
        return tag == variantTag;
    }

    /**
     * Get the tag for this branch (for jump table construction).
     * Returns -1 for wildcard, -2 for literal patterns.
     */
    public int getTag() {
        return tag;
    }

    /**
     * Check if this is a wildcard branch.
     */
    public boolean isWildcard() {
        return tag == -1;
    }

    /**
     * Check if this is a literal pattern branch.
     */
    public boolean isLiteralPattern() {
        return tag == -2;
    }

    /**
     * Get the literal value for this branch (for cache building).
     * Returns the typed literal value or null if not a literal pattern.
     */
    public Object getLiteralValue() {
        if (tag != -2) return null;
        // Return the typed value for cache lookup
        return switch (literalType) {
            case 0 -> literalLong;      // Long
            case 1 -> literalDouble;    // Double
            case 2 -> literalBoolean;   // Boolean
            case 3, 4 -> literalValue;  // String or Object
            default -> null;
        };
    }

    /**
     * Check if this matches a literal value - OPTIMIZED.
     * Uses pre-computed typed values to avoid boxing overhead.
     * Wildcards (tag == -1) match any value.
     */
    public boolean matchesLiteral(Object value) {
        // Wildcards match any value - needed for guarded wildcards like "k if let ..."
        if (tag == -1) {
            return true;
        }
        // Fast path: use pre-computed typed values for literal patterns
        return switch (literalType) {
            case 0 -> value instanceof Long v && v == literalLong; // Long comparison (unboxed)
            case 1 -> value instanceof Double v && v == literalDouble; // Double comparison (unboxed)
            case 2 -> value instanceof Boolean v && v == literalBoolean; // Boolean comparison (unboxed)
            case 3 -> value instanceof String v && ((String) literalValue).equals(v); // String comparison
            case 4 -> literalValue != null && literalValue.equals(value); // Object comparison
            default -> false;
        };
    }

    /**
     * Bind payload values from a variant to frame slots - OPTIMIZED.
     * For variant patterns like `Some { val }`, extracts fields from the payload record.
     * Uses PatternPayload for structured patterns (Option, Result, List) or
     * shape caching for O(1) field access after warmup.
     */
    @ExplodeLoop
    public void bindPayload(VirtualFrame frame, MezzeVariant variant) {
        if (payloadSlotCount == 0) {
            return; // Fast path for no payload
        }

        // FAST PATH: Use structured pattern payload if available
        // This avoids string comparisons for common patterns like Some, Cons, Err
        if (patternPayload != null) {
            patternPayload.bind(frame, variant);
            return;
        }

        // Check if we need record field extraction
        if (variantPayloadFieldNames != null && variantPayloadFieldNames.length > 0) {
            bindPayloadFromRecord(frame, variant);
            return;
        }

        // Legacy path: direct payload array access (for patterns without field names)
        Object[] payload = variant.getPayload();
        for (int i = 0; i < payloadSlotCount; i++) {
            frame.setObject(payloadSlots[i], payload[i]);
        }
    }

    /**
     * Bind payload fields from variant's record payload - OPTIMIZED.
     * For patterns like `Some { val }`, extracts `val` from the payload record.
     */
    private void bindPayloadFromRecord(VirtualFrame frame, MezzeVariant variant) {
        Object payloadValue = variant.getPayloadValue();
        if (!(payloadValue instanceof MezzeRecord record)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Expected record payload, got: " +
                (payloadValue == null ? "null" : payloadValue.getClass().getName()));
        }

        MezzeRecord.Shape shape = record.getShape();

        // Fast path: use cached field indices
        if (shape == cachedVariantPayloadShape && cachedVariantPayloadFieldIndices != null) {
            bindPayloadCached(frame, record);
            return;
        }

        // Slow path: compute and cache field indices
        CompilerDirectives.transferToInterpreterAndInvalidate();
        cacheVariantPayloadFieldIndices(shape);
        bindPayloadCached(frame, record);
    }

    @TruffleBoundary
    private void cacheVariantPayloadFieldIndices(MezzeRecord.Shape shape) {
        int[] indices = new int[variantPayloadFieldNames.length];
        for (int i = 0; i < variantPayloadFieldNames.length; i++) {
            indices[i] = shape.findFieldIndex(variantPayloadFieldNames[i]);
        }
        this.cachedVariantPayloadFieldIndices = indices;
        this.cachedVariantPayloadShape = shape;
    }

    @ExplodeLoop
    private void bindPayloadCached(VirtualFrame frame, MezzeRecord record) {
        for (int i = 0; i < payloadSlotCount; i++) {
            int fieldIndex = cachedVariantPayloadFieldIndices[i];
            int slot = payloadSlots[i];

            if (slot >= 0 && fieldIndex >= 0) {
                Object fieldValue = record.getField(fieldIndex);
                frame.setObject(slot, fieldValue);
            }
        }
    }

    /**
     * Bind the entire matched value to a frame slot.
     */
    public void bindValue(VirtualFrame frame, Object value) {
        if (valueSlot >= 0) {
            frame.setObject(valueSlot, value);
        }
    }

    // ===== ARRAY PATTERN METHODS =====

    /**
     * Check if this is an array pattern branch.
     */
    public boolean isSeqPattern() {
        return tag == -3;
    }

    /**
     * Check if this array pattern matches the given list - OPTIMIZED.
     * NO @TruffleBoundary - this is the hot path!
     */
    public boolean matchesArray(Object value) {
        if (!(value instanceof MezzeList list)) {
            return false;
        }

        int listSize = list.size();

        // Check length constraints
        if (arrayHasRest) {
            // With rest pattern: need at least arrayRequiredLength elements
            return listSize >= arrayRequiredLength;
        } else {
            // Without rest pattern: need exactly arrayRequiredLength elements
            return listSize == arrayRequiredLength;
        }
    }

    /**
     * Check if this array pattern matches the given Object[] - V2 OPTIMIZED.
     * NO @TruffleBoundary - this is the hot path!
     */
    public boolean matchesObjectArray(Object[] array) {
        int arraySize = array.length;

        // Check length constraints
        if (arrayHasRest) {
            // With rest pattern: need at least arrayRequiredLength elements
            return arraySize >= arrayRequiredLength;
        } else {
            // Without rest pattern: need exactly arrayRequiredLength elements
            return arraySize == arrayRequiredLength;
        }
    }

    /**
     * Bind array elements to frame slots - OPTIMIZED.
     * Uses storage strategy for unboxed access.
     */
    @ExplodeLoop
    public void bindArrayElements(VirtualFrame frame, MezzeList list) {
        int elementCount = arrayElementSlots.length;
        if (elementCount == 0 && arrayRestSlot < 0) {
            return; // Fast path for empty pattern []
        }

        // Bind each element position - Vavr List is always boxed, no strategy needed
        for (int i = 0; i < elementCount; i++) {
            int slot = arrayElementSlots[i];
            if (slot >= 0) {
                frame.setObject(slot, list.get(i));
            }
        }

        // Bind rest pattern if present
        if (arrayRestSlot >= 0) {
            MezzeList rest = list.slice(elementCount, list.size());
            frame.setObject(arrayRestSlot, rest);
        }
    }

    /**
     * Bind Object[] array elements to frame slots - V2 OPTIMIZED.
     * Direct array access, no strategy needed.
     */
    @ExplodeLoop
    public void bindObjectArrayElements(VirtualFrame frame, Object[] array) {
        int elementCount = arrayElementSlots.length;
        if (elementCount == 0 && arrayRestSlot < 0) {
            return; // Fast path for empty pattern []
        }

        // Bind each element position
        for (int i = 0; i < elementCount; i++) {
            int slot = arrayElementSlots[i];
            if (slot >= 0) {
                frame.setObject(slot, array[i]);
            }
        }

        // Bind rest pattern if present (creates a new Object[] with remaining elements)
        if (arrayRestSlot >= 0) {
            int restSize = array.length - elementCount;
            Object[] rest = new Object[restSize];
            System.arraycopy(array, elementCount, rest, 0, restSize);
            frame.setObject(arrayRestSlot, rest);
        }
    }

    /**
     * Check if this array pattern matches the given MezzeSeq - V2 OPTIMIZED.
     * NO @TruffleBoundary - this is the hot path!
     */
    public boolean matchesMezzeSeq(MezzeSeq seq) {
        int seqSize = seq.size();

        // Check length constraints
        if (arrayHasRest) {
            // With rest pattern: need at least arrayRequiredLength elements
            return seqSize >= arrayRequiredLength;
        } else {
            // Without rest pattern: need exactly arrayRequiredLength elements
            return seqSize == arrayRequiredLength;
        }
    }

    /**
     * Bind MezzeSeq elements to frame slots - V2 OPTIMIZED.
     * Direct seq access via get().
     */
    @ExplodeLoop
    public void bindMezzeSeqElements(VirtualFrame frame, MezzeSeq seq) {
        int elementCount = arrayElementSlots.length;
        if (elementCount == 0 && arrayRestSlot < 0) {
            return; // Fast path for empty pattern []
        }

        // Bind each element position
        for (int i = 0; i < elementCount; i++) {
            int slot = arrayElementSlots[i];
            if (slot >= 0) {
                frame.setObject(slot, seq.get(i));
            }
        }

        // Bind rest pattern if present (creates a new MezzeSeq with remaining elements)
        if (arrayRestSlot >= 0) {
            MezzeSeq rest = seq.slice(elementCount, seq.size());
            frame.setObject(arrayRestSlot, rest);
        }
    }

    // ===== STRING PATTERN METHODS =====

    /**
     * Check if this is a string pattern branch.
     */
    public boolean isStringPattern() {
        return tag == -4;
    }

    /**
     * Check if this string pattern matches the given value - OPTIMIZED.
     */
    public boolean matchesString(Object value) {
        if (!(value instanceof String str)) {
            return false;
        }

        // Fast path for single capture patterns
        if (stringHasSingleCapture) {
            return matchesStringSingleCapture(str);
        }

        // General path
        return matchesStringGeneral(str);
    }

    /**
     * Fast path for "prefix$(x)suffix" patterns.
     * NO @TruffleBoundary!
     */
    private boolean matchesStringSingleCapture(String str) {
        int len = str.length();

        if (stringPrefix != null) {
            int prefixLen = stringPrefix.length();
            if (len < prefixLen || !str.startsWith(stringPrefix)) {
                return false;
            }
        }

        if (stringSuffix != null) {
            int suffixLen = stringSuffix.length();
            if (len < suffixLen || !str.endsWith(stringSuffix)) {
                return false;
            }
        }

        // Ensure prefix and suffix don't overlap
        int prefixLen = stringPrefix != null ? stringPrefix.length() : 0;
        int suffixLen = stringSuffix != null ? stringSuffix.length() : 0;
        return len >= prefixLen + suffixLen;
    }

    @TruffleBoundary
    private boolean matchesStringGeneral(String str) {
        return performStringMatching(str, new String[countStringCaptures()]) != null;
    }

    private int countStringCaptures() {
        if (stringPartTypes == null) return 0;
        int count = 0;
        for (int type : stringPartTypes) {
            if (type == 1) count++;
        }
        return count;
    }

    /**
     * Bind captured string values to frame slots.
     */
    public void bindStringCaptures(VirtualFrame frame, String str) {
        if (stringHasSingleCapture) {
            bindStringSingleCapture(frame, str);
        } else {
            bindStringGeneral(frame, str);
        }
    }

    private void bindStringSingleCapture(VirtualFrame frame, String str) {
        int prefixLen = stringPrefix != null ? stringPrefix.length() : 0;
        int suffixLen = stringSuffix != null ? stringSuffix.length() : 0;

        String captured = str.substring(prefixLen, str.length() - suffixLen);

        // Find first capture slot
        if (stringCaptureSlots != null) {
            for (int i = 0; i < stringPartTypes.length; i++) {
                if (stringPartTypes[i] == 1 && i < stringCaptureSlots.length) {
                    int slot = stringCaptureSlots[i];
                    if (slot >= 0) {
                        frame.setObject(slot, captured);
                    }
                    break;
                }
            }
        }
    }

    private void bindStringGeneral(VirtualFrame frame, String str) {
        // Perform matching in boundary method, then bind results
        String[] captures = computeStringCaptures(str);

        // Bind captures (not in boundary)
        if (stringCaptureSlots != null && captures != null) {
            int captureIdx = 0;
            for (int i = 0; i < stringPartTypes.length && captureIdx < captures.length; i++) {
                if (stringPartTypes[i] == 1) {
                    int slot = stringCaptureSlots[i];
                    if (slot >= 0) {
                        frame.setObject(slot, captures[captureIdx]);
                    }
                    captureIdx++;
                }
            }
        }
    }

    @TruffleBoundary
    private String[] computeStringCaptures(String str) {
        int numCaptures = countStringCaptures();
        String[] captures = new String[numCaptures];
        performStringMatching(str, captures);
        return captures;
    }

    private String[] performStringMatching(String input, String[] captures) {
        if (matchStringPattern(input, 0, 0, captures, 0)) {
            return captures;
        }
        return null;
    }

    private boolean matchStringPattern(String input, int inputPos, int partIndex,
                                        String[] captures, int captureIndex) {
        if (stringPartTypes == null) return inputPos == input.length();

        if (partIndex >= stringPartTypes.length) {
            return inputPos == input.length();
        }

        int partType = stringPartTypes[partIndex];

        if (partType == 0) { // LITERAL
            String literal = stringLiterals[partIndex];
            int litLen = literal.length();

            if (inputPos + litLen > input.length()) return false;
            if (!input.regionMatches(inputPos, literal, 0, litLen)) return false;

            return matchStringPattern(input, inputPos + litLen, partIndex + 1, captures, captureIndex);
        } else { // CAPTURE
            if (partIndex + 1 >= stringPartTypes.length) {
                captures[captureIndex] = input.substring(inputPos);
                return true;
            }

            int nextPartType = stringPartTypes[partIndex + 1];

            if (nextPartType == 0) { // Next is LITERAL
                String nextLiteral = stringLiterals[partIndex + 1];
                int searchPos = inputPos;

                while (true) {
                    int literalPos = input.indexOf(nextLiteral, searchPos);
                    if (literalPos == -1) return false;

                    String oldCapture = captures[captureIndex];
                    captures[captureIndex] = input.substring(inputPos, literalPos);

                    if (matchStringPattern(input, literalPos, partIndex + 1, captures, captureIndex + 1)) {
                        return true;
                    }

                    captures[captureIndex] = oldCapture;
                    searchPos = literalPos + 1;
                }
            } else { // Adjacent captures
                for (int splitPos = inputPos; splitPos <= input.length(); splitPos++) {
                    String oldCapture = captures[captureIndex];
                    captures[captureIndex] = input.substring(inputPos, splitPos);

                    if (matchStringPattern(input, splitPos, partIndex + 1, captures, captureIndex + 1)) {
                        return true;
                    }

                    captures[captureIndex] = oldCapture;
                }
                return false;
            }
        }
    }

    // ===== RECORD PATTERN METHODS =====

    /**
     * Check if this is a record pattern branch.
     */
    public boolean isRecordPattern() {
        return tag == -5;
    }

    /**
     * Check if this record pattern matches the given value - OPTIMIZED.
     */
    public boolean matchesRecord(Object value) {
        if (!(value instanceof MezzeRecord record)) {
            return false;
        }

        if (recordFieldNames == null || recordFieldNames.length == 0) {
            return true; // Empty pattern matches any record
        }

        MezzeRecord.Shape shape = record.getShape();

        // Check field count for closed patterns
        if (!recordIsOpen && shape.getFieldCount() != recordFieldNames.length) {
            return false;
        }

        // Try cached path
        if (cachedRecordShape == shape && cachedRecordFieldIndices != null) {
            return true;
        }

        // Verify all fields exist
        return matchesRecordSlow(shape);
    }

    @TruffleBoundary
    private boolean matchesRecordSlow(MezzeRecord.Shape shape) {
        for (String fieldName : recordFieldNames) {
            if (shape.findFieldIndex(fieldName) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Bind record fields to frame slots - OPTIMIZED.
     */
    @ExplodeLoop
    public void bindRecordFields(VirtualFrame frame, MezzeRecord record) {
        if (recordFieldNames == null || recordFieldNames.length == 0) {
            return;
        }

        MezzeRecord.Shape shape = record.getShape();

        // Try cached path
        if (cachedRecordShape == shape && cachedRecordFieldIndices != null) {
            bindRecordCached(frame, record);
            return;
        }

        // Cache miss
        CompilerDirectives.transferToInterpreterAndInvalidate();
        cacheRecordFieldIndices(shape);
        bindRecordCached(frame, record);
    }

    @TruffleBoundary
    private void cacheRecordFieldIndices(MezzeRecord.Shape shape) {
        int[] indices = new int[recordFieldNames.length];
        for (int i = 0; i < recordFieldNames.length; i++) {
            indices[i] = shape.findFieldIndex(recordFieldNames[i]);
        }
        this.cachedRecordFieldIndices = indices;
        this.cachedRecordShape = shape;
    }

    @ExplodeLoop
    private void bindRecordCached(VirtualFrame frame, MezzeRecord record) {
        // Note: stringCaptureSlots is reused for record field slots in record patterns
        int[] fieldSlots = stringCaptureSlots;
        if (fieldSlots == null) return;

        for (int i = 0; i < recordFieldNames.length && i < fieldSlots.length; i++) {
            int fieldIndex = cachedRecordFieldIndices[i];
            int slot = fieldSlots[i];

            if (slot >= 0 && fieldIndex >= 0) {
                Object fieldValue = record.getField(fieldIndex);
                frame.setObject(slot, fieldValue);
            }
        }
    }

    /**
     * Execute the branch body after checking guards and if-let bindings.
     * Returns null if guard/if-let fails (to signal match failure).
     */
    @Override
    public Object execute(VirtualFrame frame) {
        // Check if-let bindings first (they may add bindings used by guard)
        if (!checkIfLetBindings(frame)) {
            return null; // If-let binding failed
        }

        // Check guard condition if present
        if (!checkGuard(frame)) {
            return null; // Guard failed
        }

        return bodyNode.execute(frame);
    }

    /**
     * Check if this branch has guards or if-let bindings that could fail.
     * Used by MatchNode to know if it needs to handle null returns.
     */
    public boolean hasGuardsOrIfLet() {
        return guardNode != null || ifLetBindingCount > 0;
    }

    /**
     * Check all if-let bindings.
     * @return true if all bindings match, false if any fail
     */
    @ExplodeLoop
    private boolean checkIfLetBindings(VirtualFrame frame) {
        for (int i = 0; i < ifLetBindingCount; i++) {
            if (!ifLetBindings[i].tryMatch(frame)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Check the guard condition.
     * @return true if guard passes (or no guard), false if guard fails
     */
    private boolean checkGuard(VirtualFrame frame) {
        if (guardNode == null) {
            return true;
        }
        Object result = guardNode.execute(frame);
        return result instanceof Boolean b && b;
    }

    // ===== CONS/NIL LIST PATTERN - PRECOMPUTED FOR PERFORMANCE =====

    // Precomputed: is this a Cons pattern? Computed once at construction.
    @CompilationFinal
    private boolean isConsPat;

    // Precomputed: is this a Nil pattern? Computed once at construction.
    @CompilationFinal
    private boolean isNilPat;

    // Precomputed slot indices for Cons binding - NO string comparison at runtime
    @CompilationFinal
    private int consHeadSlot = -1;

    @CompilationFinal
    private int consTailSlot = -1;

    /**
     * Initialize Cons/Nil pattern detection. Called once after construction.
     * This precomputes all pattern info to avoid runtime string comparisons.
     */
    private void initListPatternInfo() {
        // Check for Nil pattern: no payload fields
        if (tag >= 0 && payloadSlotCount == 0 &&
            (variantPayloadFieldNames == null || variantPayloadFieldNames.length == 0)) {
            this.isNilPat = true;
            this.isConsPat = false;
            return;
        }

        // Check for Cons pattern: exactly "head" and "tail" fields
        if (tag >= 0 && variantPayloadFieldNames != null && variantPayloadFieldNames.length == 2) {
            int headIdx = -1;
            int tailIdx = -1;
            for (int i = 0; i < variantPayloadFieldNames.length; i++) {
                if ("head".equals(variantPayloadFieldNames[i])) {
                    headIdx = i;
                } else if ("tail".equals(variantPayloadFieldNames[i])) {
                    tailIdx = i;
                }
            }
            if (headIdx >= 0 && tailIdx >= 0) {
                this.isConsPat = true;
                this.isNilPat = false;
                // Precompute slot indices for O(1) binding
                this.consHeadSlot = (headIdx < payloadSlotCount) ? payloadSlots[headIdx] : -1;
                this.consTailSlot = (tailIdx < payloadSlotCount) ? payloadSlots[tailIdx] : -1;
                return;
            }
        }

        this.isConsPat = false;
        this.isNilPat = false;
    }

    /**
     * Check if this is a Cons pattern (for MezzeList matching).
     * O(1) - uses precomputed flag.
     */
    public boolean isConsPattern() {
        return isConsPat;
    }

    /**
     * Check if this is a Nil pattern (for MezzeList matching).
     * O(1) - uses precomputed flag.
     */
    public boolean isNilPattern() {
        return isNilPat;
    }

    /**
     * FAST: Bind MezzeList head and tail to frame slots.
     * Uses precomputed slot indices - NO string comparison, NO iteration.
     */
    public void bindListConsPayload(VirtualFrame frame, MezzeList list) {
        // Direct slot assignment using precomputed indices
        if (consHeadSlot >= 0) {
            frame.setObject(consHeadSlot, list.get(0));
        }
        if (consTailSlot >= 0) {
            frame.setObject(consTailSlot, list.tail());
        }
    }
}
