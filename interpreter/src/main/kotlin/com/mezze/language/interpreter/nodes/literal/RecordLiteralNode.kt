package com.mezze.language.interpreter.nodes.literal

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeRecord
import com.mezze.language.interpreter.RecordShape

/**
 * Truffle node for record literal construction.
 *
 * JIT-friendly: Pre-computes the RecordShape at construction time,
 * then uses direct array construction at runtime.
 */
@NodeInfo(shortName = "record-literal")
class RecordLiteralNode(
    fieldNodes: Map<String, MezzeNode>
) : MezzeNode() {

    // Pre-compute the shape at construction time (parse time)
    @CompilationFinal
    private val shape: RecordShape = computeShape(fieldNodes.keys.toList())

    // Child nodes ordered to match the shape's field ordering
    @field:Children
    private val childNodes: Array<MezzeNode>

    @CompilationFinal
    private val numFields: Int

    init {
        // Reorder child nodes to match shape's field ordering
        val nodeMap = fieldNodes
        childNodes = Array(shape.size) { i ->
            nodeMap[shape.fieldNamesArray[i]]!!
        }
        numFields = shape.size
    }

    override fun execute(frame: VirtualFrame): MezzeValue {
        // Execute field nodes in the hot path
        val fieldValues = Array(numFields) { index ->
            childNodes[index].execute(frame)
        }

        // Build record directly from shape and values (no map needed!)
        return MezzeRecord.fromShapeAndValues(shape, fieldValues)
    }

    companion object {
        @TruffleBoundary
        private fun computeShape(fieldNames: List<String>): RecordShape {
            return RecordShape.forFields(fieldNames)
        }
    }
}