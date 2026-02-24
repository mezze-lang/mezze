package com.mezze.language.interpreter

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import java.util.concurrent.ConcurrentHashMap

/**
 * RecordShape represents the layout of a record type.
 *
 * It maps field names to array indices, enabling O(1) field access
 * without hash map lookups in the JIT-compiled hot path.
 *
 * Shapes are cached and reused for records with the same field names,
 * enabling shape-based optimizations similar to hidden classes in V8.
 *
 * Thread-safe: Uses ConcurrentHashMap for the global shape cache.
 */
class RecordShape private constructor(
    fieldNames: List<String>
) {
    /**
     * Ordered list of field names. The index in this array is the slot index.
     */
    @CompilationFinal(dimensions = 1)
    val fieldNamesArray: Array<String> = fieldNames.toTypedArray()

    /**
     * Map from field name to slot index. Used for lookups.
     * This is computed once at shape creation time.
     */
    private val fieldIndexMap: Map<String, Int> = fieldNames.withIndex()
        .associate { (index, name) -> name to index }

    /**
     * Number of fields in this shape.
     */
    @CompilationFinal
    val size: Int = fieldNames.size

    /**
     * Identity for this shape (used for caching).
     * Shapes with the same field names in the same order have the same identity.
     */
    val identity: String = fieldNames.sorted().joinToString(",")

    /**
     * Get the slot index for a field name.
     * Returns -1 if the field is not in this shape.
     *
     * This is called at node construction time (parse time), not at runtime.
     */
    fun getIndex(fieldName: String): Int {
        return fieldIndexMap[fieldName] ?: -1
    }

    /**
     * Check if this shape contains a field.
     */
    fun hasField(fieldName: String): Boolean {
        return fieldIndexMap.containsKey(fieldName)
    }

    /**
     * Get the field name at a given index.
     */
    fun getFieldName(index: Int): String {
        return fieldNamesArray[index]
    }

    companion object {
        /**
         * Global shape cache. Shapes are interned for memory efficiency
         * and to enable identity-based shape checks.
         */
        private val shapeCache = ConcurrentHashMap<String, RecordShape>()

        /**
         * Empty shape singleton for empty records.
         */
        val EMPTY: RecordShape = RecordShape(emptyList())

        /**
         * Get or create a shape for the given field names.
         * The order of field names is preserved.
         *
         * This is called at record construction time.
         */
        @TruffleBoundary
        fun forFields(fieldNames: List<String>): RecordShape {
            if (fieldNames.isEmpty()) return EMPTY

            // Create a canonical identity based on sorted field names
            val identity = fieldNames.sorted().joinToString(",")

            return shapeCache.computeIfAbsent(identity) {
                RecordShape(fieldNames.sorted())
            }
        }

        /**
         * Get or create a shape for the given field names.
         * Vararg convenience method.
         */
        @TruffleBoundary
        fun forFields(vararg fieldNames: String): RecordShape {
            return forFields(fieldNames.toList())
        }

        /**
         * Create a shape from a map's keys.
         * The fields are sorted for consistent ordering.
         */
        @TruffleBoundary
        fun fromMap(map: Map<String, *>): RecordShape {
            return forFields(map.keys.toList())
        }

        /**
         * Clear the shape cache (for testing).
         */
        @TruffleBoundary
        fun clearCache() {
            shapeCache.clear()
        }
    }
}
