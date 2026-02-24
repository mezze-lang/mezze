package com.mezze.language.interpreter.nodes.literal

import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeRecord
import com.mezze.language.interpreter.MezzeNull
import com.mezze.language.interpreter.RecordShape

/**
 * Node for accessing a field from a record value.
 *
 * Uses inline caching: caches the record shape and field index on first access.
 * If the record has the same shape, uses O(1) indexed access.
 * If the shape changes, falls back to map-based access.
 */
@NodeInfo(shortName = "record-field-access")
class RecordFieldAccessNode(
    @field:Child private var recordNode: MezzeNode,
    private val fieldName: String
) : MezzeNode() {

    // Inline cache for shape and field index
    @CompilationFinal
    private var cachedShape: RecordShape? = null

    @CompilationFinal
    private var cachedIndex: Int = -1

    override fun execute(frame: VirtualFrame): MezzeValue {
        val recordValue = recordNode.execute(frame)

        if (recordValue !is MezzeRecord) {
            return throwNotRecord(recordValue)
        }

        // Fast path: check if shape matches cached shape
        val shape = recordValue.shape
        if (shape === cachedShape && cachedIndex >= 0) {
            return recordValue.getByIndex(cachedIndex)
        }

        // Slow path: look up index and cache it
        return accessFieldSlow(recordValue)
    }

    @TruffleBoundary
    private fun accessFieldSlow(record: MezzeRecord): MezzeValue {
        val shape = record.shape
        val index = shape.getIndex(fieldName)

        if (index < 0) {
            return MezzeNull
        }

        // Update cache (this is safe because we're in @TruffleBoundary)
        // Note: In a fully optimized version, we'd use CompilerDirectives.transferToInterpreterAndInvalidate()
        if (cachedShape == null) {
            cachedShape = shape
            cachedIndex = index
        }

        return record.getByIndex(index)
    }

    @TruffleBoundary
    private fun throwNotRecord(recordValue: MezzeValue): Nothing {
        throw RuntimeException("Cannot access field '$fieldName' on non-record value: ${recordValue::class.simpleName}")
    }
}
