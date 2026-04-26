package com.mezze.interpreter.nodes.record;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeRecord.FieldLocation;
import com.mezze.interpreter.values.MezzeRecord.Shape;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Idempotent;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * POLYMORPHIC INLINE CACHE for record field access.
 *
 * This is a key optimization for dynamic field access patterns.
 * Instead of doing a HashMap lookup on every access, we cache:
 * 1. The record's Shape
 * 2. The field's location within that shape
 *
 * Cache levels:
 * - Monomorphic: Single shape, direct indexed access
 * - Polymorphic: Up to 6 shapes, check shape then direct access
 * - Megamorphic: Fall back to @TruffleBoundary lookup
 *
 * Primitive specializations:
 * - Long fields: Unboxed access via executeLong()
 * - Double fields: Unboxed access via executeDouble()
 * - Object fields: Reference access
 *
 * The JIT can fully inline monomorphic and polymorphic accesses.
 */
@NodeChild(value = "recordNode", type = MezzeNode.class)
@NodeInfo(shortName = "cached-field")
@ReportPolymorphism
@SuppressWarnings("truffle-splitting")
public abstract class CachedFieldAccessNode extends MezzeNode {

    @CompilationFinal
    protected final String fieldName;

    protected CachedFieldAccessNode(String fieldName) {
        this.fieldName = fieldName;
    }

    // ===== PRIMITIVE SPECIALIZATIONS FOR UNBOXED ACCESS =====

    /**
     * Long field access - fully unboxed, no boxing overhead.
     */
    @Specialization(
        guards = {
            "isRecord(recordObj)",
            "getRecordShape(recordObj) == cachedShape",
            "storageType == 0"
        },
        limit = "6"
    )
    protected long accessLongCached(
            Object recordObj,
            @Cached("getRecordShape(recordObj)") Shape cachedShape,
            @Cached("resolveFieldIndex(cachedShape)") int fieldIndex,
            @Cached("resolveStorageType(cachedShape, fieldIndex)") int storageType,
            @Cached("resolveLocationIndex(cachedShape, fieldIndex)") int locationIndex
    ) {
        MezzeRecord record = (MezzeRecord) recordObj;
        return record.getLongField(locationIndex);
    }

    /**
     * Double field access - fully unboxed, no boxing overhead.
     */
    @Specialization(
        guards = {
            "isRecord(recordObj)",
            "getRecordShape(recordObj) == cachedShape",
            "storageType == 1"
        },
        limit = "6"
    )
    protected double accessDoubleCached(
            Object recordObj,
            @Cached("getRecordShape(recordObj)") Shape cachedShape,
            @Cached("resolveFieldIndex(cachedShape)") int fieldIndex,
            @Cached("resolveStorageType(cachedShape, fieldIndex)") int storageType,
            @Cached("resolveLocationIndex(cachedShape, fieldIndex)") int locationIndex
    ) {
        MezzeRecord record = (MezzeRecord) recordObj;
        return record.getDoubleField(locationIndex);
    }

    /**
     * Object field access - for reference types.
     */
    @Specialization(
        guards = {
            "isRecord(recordObj)",
            "getRecordShape(recordObj) == cachedShape",
            "storageType == 2"
        },
        limit = "6"
    )
    protected Object accessObjectCached(
            Object recordObj,
            @Cached("getRecordShape(recordObj)") Shape cachedShape,
            @Cached("resolveFieldIndex(cachedShape)") int fieldIndex,
            @Cached("resolveStorageType(cachedShape, fieldIndex)") int storageType,
            @Cached("resolveLocationIndex(cachedShape, fieldIndex)") int locationIndex
    ) {
        MezzeRecord record = (MezzeRecord) recordObj;
        return record.getObjectField(locationIndex);
    }

    /**
     * MEGAMORPHIC fallback: Too many different shapes.
     * Uses @TruffleBoundary but this path should be rare after warmup.
     */
    @Specialization(guards = "isRecord(recordObj)", replaces = {"accessLongCached", "accessDoubleCached", "accessObjectCached"})
    @ReportPolymorphism.Megamorphic
    protected Object accessMegamorphic(Object recordObj) {
        return megamorphicAccess(recordObj);
    }

    /**
     * Handle method-like field access on Boolean values.
     * Supports .not, .and, .or on Boolean primitives.
     * Used when ability impls chain boolean ops like: it.eq {r = r}.not {}
     */
    @Specialization(guards = {"isBooleanMethod(recordObj)"})
    protected Object accessBooleanMethod(Object recordObj) {
        boolean b = (Boolean) recordObj;
        if ("not".equals(fieldName)) return !b;
        if ("and".equals(fieldName)) return b;  // Short-circuit: returns receiver for .and
        if ("or".equals(fieldName)) return b;   // Short-circuit: returns receiver for .or
        // Shouldn't reach here due to guard, but fallback
        throw new RuntimeException("Expected record for field '" + fieldName + "', got: Boolean");
    }

    /**
     * Handle field access on MezzeVariant by unwrapping to the payload record.
     * This handles cases like codec method access (.decode_repr, .encode_repr)
     * on variant-wrapped records (e.g., Json, Promise types).
     */
    @Specialization(guards = {"isVariantWithRecordPayload(recordObj)"})
    protected Object accessVariantPayload(Object recordObj) {
        return unwrapVariantField(recordObj);
    }

    /**
     * Error case: not a record.
     */
    @Specialization(guards = {"!isRecord(recordObj)", "!isBooleanMethod(recordObj)", "!isVariantWithRecordPayload(recordObj)"})
    protected Object accessNotRecord(Object recordObj) {
        throw new RuntimeException("Expected record for field '" + fieldName + "', got: " +
            (recordObj == null ? "null" : recordObj.getClass().getSimpleName()));
    }

    protected boolean isBooleanMethod(Object obj) {
        return obj instanceof Boolean && ("not".equals(fieldName) || "and".equals(fieldName) || "or".equals(fieldName));
    }

    @Idempotent
    protected static boolean isVariantWithRecordPayload(Object obj) {
        if (obj instanceof MezzeVariant v && v.hasPayload()) {
            Object payload = v.getPayloadValue();
            return payload instanceof MezzeRecord;
        }
        return false;
    }

    @TruffleBoundary
    private Object unwrapVariantField(Object recordObj) {
        MezzeVariant variant = (MezzeVariant) recordObj;
        MezzeRecord record = (MezzeRecord) variant.getPayloadValue();
        Object value = record.getField(fieldName);
        if (value != null) return value;
        throw new RuntimeException("Field '" + fieldName + "' not found in variant payload record");
    }

    // ===== Primitive execute overrides for unboxed access =====

    @Override
    public long executeLong(com.oracle.truffle.api.frame.VirtualFrame frame) throws UnexpectedResultException {
        Object result = execute(frame);
        if (result instanceof Long l) {
            return l;
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new UnexpectedResultException(result);
    }

    @Override
    public double executeDouble(com.oracle.truffle.api.frame.VirtualFrame frame) throws UnexpectedResultException {
        Object result = execute(frame);
        if (result instanceof Double d) {
            return d;
        }
        if (result instanceof Long l) {
            return l.doubleValue();
        }
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new UnexpectedResultException(result);
    }

    // ===== Guard methods =====

    @Idempotent
    protected static boolean isRecord(Object obj) {
        return obj instanceof MezzeRecord;
    }

    protected static Shape getRecordShape(Object obj) {
        return ((MezzeRecord) obj).getShape();
    }

    @TruffleBoundary
    private Object megamorphicAccess(Object recordObj) {
        if (!(recordObj instanceof MezzeRecord record)) {
            throw new RuntimeException("Expected record, got: " + recordObj.getClass().getSimpleName());
        }
        Object value = record.getField(fieldName);
        if (value == null) {
            throw new RuntimeException("Field not found: " + fieldName);
        }
        return value;
    }

    // ===== Cache resolution methods (called once during specialization) =====

    /**
     * Resolve field index from shape. Called once when caching.
     */
    @TruffleBoundary
    protected int resolveFieldIndex(Shape shape) {
        int index = shape.findFieldIndex(fieldName);
        if (index < 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Field not found in shape: " + fieldName);
        }
        return index;
    }

    /**
     * Resolve storage type from shape and field index.
     * Returns: 0=Long, 1=Double, 2=Object
     */
    @TruffleBoundary
    protected int resolveStorageType(Shape shape, int fieldIndex) {
        FieldLocation loc = shape.getFieldLocation(fieldIndex);
        return switch (loc.storageType) {
            case LONG -> 0;
            case DOUBLE -> 1;
            case OBJECT -> 2;
        };
    }

    /**
     * Resolve location index (index within typed array) from shape.
     */
    @TruffleBoundary
    protected int resolveLocationIndex(Shape shape, int fieldIndex) {
        return shape.getFieldLocation(fieldIndex).index;
    }

    /**
     * Factory method.
     */
    public static CachedFieldAccessNode create(MezzeNode recordNode, String fieldName) {
        return CachedFieldAccessNodeGen.create(fieldName, recordNode);
    }
}
