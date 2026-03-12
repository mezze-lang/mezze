package com.mezze.datastructures.nodes;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Extremely optimized record field accessor for Long fields.
 *
 * Uses monomorphic inline cache for field index.
 * After JIT, this becomes a direct array access.
 *
 * Key optimizations:
 * - Cached field index lookup (O(1) after first call)
 * - Unboxed long return (no Long wrapper)
 * - Shape check inline cache
 */
@NodeInfo(shortName = "record.get.long")
public final class RecordFieldGetLongNode extends Node {

    @CompilationFinal
    private final String fieldName;

    @CompilationFinal
    private int cachedIndex = -2; // -2 = not cached, -1 = not found

    @CompilationFinal
    private Object cachedShape;

    public RecordFieldGetLongNode(String fieldName) {
        this.fieldName = fieldName;
    }

    /**
     * Get a long field from a record with minimal overhead.
     * @param record The record (must have getFieldIndex and getLongField methods)
     * @param shape The record's shape (for cache invalidation)
     * @return The unboxed long value
     */
    public long execute(Object record, Object shape) {
        // Fast path: same shape as cached
        if (shape == cachedShape && cachedIndex >= 0) {
            return getLongFieldDirect(record, cachedIndex);
        }

        // Slow path: lookup and cache
        return executeSlow(record, shape);
    }

    private long executeSlow(Object record, Object shape) {
        CompilerDirectives.transferToInterpreterAndInvalidate();

        int index = lookupFieldIndex(record, fieldName);
        if (index < 0) {
            throw new RuntimeException("Field not found: " + fieldName);
        }

        cachedShape = shape;
        cachedIndex = index;

        return getLongFieldDirect(record, index);
    }

    // These methods should be overridden or implemented based on actual record type
    @CompilerDirectives.TruffleBoundary
    private static int lookupFieldIndex(Object record, String name) {
        // Would call record.getFieldIndex(name)
        throw new UnsupportedOperationException("Must be wired to MezzeRecord");
    }

    private static long getLongFieldDirect(Object record, int index) {
        // Would call record.getLongField(index)
        throw new UnsupportedOperationException("Must be wired to MezzeRecord");
    }

    public static RecordFieldGetLongNode create(String fieldName) {
        return new RecordFieldGetLongNode(fieldName);
    }
}
