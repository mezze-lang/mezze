package com.mezze.interpreter.v2.nodes.variant;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.mezze.interpreter.v2.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * Structured pattern payload types for optimized variant matching.
 *
 * Replaces string-based field lookups with precomputed field indices
 * for common patterns like Option (Some/None), Result (Ok/Err), List (Cons/Nil).
 *
 * This eliminates runtime string comparisons in hot paths by:
 * 1. Pattern detection at construction time (O(1) string checks once)
 * 2. Direct field index access at runtime (no HashMap or string comparison)
 * 3. Specialized binding methods for each pattern type
 */
public sealed interface PatternPayload {

    /**
     * Bind payload values to frame slots.
     * @param frame The current virtual frame
     * @param variant The variant being matched
     */
    void bind(VirtualFrame frame, MezzeVariant variant);

    /**
     * Check if this pattern has any payload bindings.
     */
    boolean hasPayload();

    // ========== Factory Methods ==========

    /**
     * Detect and create the appropriate pattern payload type.
     * Returns null if no optimization is available.
     */
    static PatternPayload detect(String[] fieldNames, int[] fieldSlots) {
        if (fieldNames == null || fieldNames.length == 0) {
            return Empty.INSTANCE;
        }

        // Option pattern: Some { val }
        if (fieldNames.length == 1 && "val".equals(fieldNames[0])) {
            return new OptionSome(fieldSlots[0]);
        }

        // List Cons pattern: Cons { head, tail }
        if (fieldNames.length == 2) {
            int headIdx = -1, tailIdx = -1;
            for (int i = 0; i < 2; i++) {
                if ("head".equals(fieldNames[i])) headIdx = i;
                else if ("tail".equals(fieldNames[i])) tailIdx = i;
            }
            if (headIdx >= 0 && tailIdx >= 0) {
                return new ListCons(fieldSlots[headIdx], fieldSlots[tailIdx]);
            }

            // Result Ok pattern: Ok { val } - but 2 fields means it's not simple Ok
            // Result Err pattern: Err { err } - but 2 fields means it's not simple Err
        }

        // Single-field Result patterns
        if (fieldNames.length == 1) {
            if ("err".equals(fieldNames[0])) {
                return new ResultErr(fieldSlots[0]);
            }
            // "val" is already handled by OptionSome above
        }

        // No optimization available - use generic payload
        return new Generic(fieldNames, fieldSlots);
    }

    // ========== Pattern Implementations ==========

    /**
     * Empty pattern - no payload fields to bind.
     * Used for Nil, None, unit variants.
     */
    record Empty() implements PatternPayload {
        static final Empty INSTANCE = new Empty();

        @Override
        public void bind(VirtualFrame frame, MezzeVariant variant) {
            // Nothing to bind
        }

        @Override
        public boolean hasPayload() {
            return false;
        }
    }

    /**
     * Option Some pattern: Some { val }
     * Single field "val" at precomputed index.
     */
    record OptionSome(int valSlot) implements PatternPayload {
        // Cached field index for "val" in Some's record payload
        private static final int VAL_FIELD_INDEX = 0;

        @Override
        public void bind(VirtualFrame frame, MezzeVariant variant) {
            if (valSlot < 0) return;

            Object payload = variant.getPayloadValue();
            if (payload instanceof MezzeRecord record) {
                // Direct field access - Some { val = x } always has "val" at index 0
                Object value = record.getField(VAL_FIELD_INDEX);
                frame.setObject(valSlot, value);
            }
        }

        @Override
        public boolean hasPayload() {
            return valSlot >= 0;
        }
    }

    /**
     * Result Err pattern: Err { err }
     * Single field "err" at precomputed index.
     */
    record ResultErr(int errSlot) implements PatternPayload {
        // Cached field index for "err" in Err's record payload
        private static final int ERR_FIELD_INDEX = 0;

        @Override
        public void bind(VirtualFrame frame, MezzeVariant variant) {
            if (errSlot < 0) return;

            Object payload = variant.getPayloadValue();
            if (payload instanceof MezzeRecord record) {
                Object value = record.getField(ERR_FIELD_INDEX);
                frame.setObject(errSlot, value);
            }
        }

        @Override
        public boolean hasPayload() {
            return errSlot >= 0;
        }
    }

    /**
     * List Cons pattern: Cons { head, tail }
     * Two fields with precomputed indices.
     */
    record ListCons(int headSlot, int tailSlot) implements PatternPayload {

        @Override
        public void bind(VirtualFrame frame, MezzeVariant variant) {
            Object payload = variant.getPayloadValue();

            // Fast path: if payload is already a MezzeList (native list impl)
            if (payload instanceof MezzeList list) {
                if (headSlot >= 0) {
                    frame.setObject(headSlot, list.get(0));
                }
                if (tailSlot >= 0) {
                    frame.setObject(tailSlot, list.tail());
                }
                return;
            }

            // Record path: Cons { head = x, tail = xs }
            if (payload instanceof MezzeRecord record) {
                // Cons records have "head" at index 0, "tail" at index 1
                if (headSlot >= 0) {
                    frame.setObject(headSlot, record.getField(0));
                }
                if (tailSlot >= 0) {
                    frame.setObject(tailSlot, record.getField(1));
                }
            }
        }

        @Override
        public boolean hasPayload() {
            return headSlot >= 0 || tailSlot >= 0;
        }
    }

    /**
     * Generic pattern - falls back to cached field index lookup.
     * Used when no specialized pattern matches.
     */
    final class Generic implements PatternPayload {
        @CompilationFinal(dimensions = 1)
        private final String[] fieldNames;

        @CompilationFinal(dimensions = 1)
        private final int[] fieldSlots;

        @CompilationFinal
        private final int fieldCount;

        // Cached field indices after first lookup
        @CompilationFinal
        private MezzeRecord.Shape cachedShape;

        @CompilationFinal(dimensions = 1)
        private int[] cachedFieldIndices;

        Generic(String[] fieldNames, int[] fieldSlots) {
            this.fieldNames = fieldNames;
            this.fieldSlots = fieldSlots;
            this.fieldCount = fieldNames.length;
        }

        @Override
        public void bind(VirtualFrame frame, MezzeVariant variant) {
            Object payload = variant.getPayloadValue();
            if (!(payload instanceof MezzeRecord record)) {
                return;
            }

            MezzeRecord.Shape shape = record.getShape();

            // Fast path: use cached indices
            if (shape == cachedShape && cachedFieldIndices != null) {
                bindCached(frame, record);
                return;
            }

            // Slow path: compute and cache indices
            cacheFieldIndices(shape);
            bindCached(frame, record);
        }

        private void cacheFieldIndices(MezzeRecord.Shape shape) {
            int[] indices = new int[fieldCount];
            for (int i = 0; i < fieldCount; i++) {
                indices[i] = shape.findFieldIndex(fieldNames[i]);
            }
            this.cachedFieldIndices = indices;
            this.cachedShape = shape;
        }

        private void bindCached(VirtualFrame frame, MezzeRecord record) {
            for (int i = 0; i < fieldCount; i++) {
                int fieldIndex = cachedFieldIndices[i];
                int slot = fieldSlots[i];
                if (slot >= 0 && fieldIndex >= 0) {
                    frame.setObject(slot, record.getField(fieldIndex));
                }
            }
        }

        @Override
        public boolean hasPayload() {
            return fieldCount > 0;
        }
    }
}
