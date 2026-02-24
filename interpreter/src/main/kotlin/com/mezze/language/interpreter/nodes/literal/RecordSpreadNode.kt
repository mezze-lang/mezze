package com.mezze.language.interpreter.nodes.literal

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeRecord

/**
 * Node that handles record spread syntax.
 * Example: {...person, age = 26} merges person fields with the age override.
 *
 * All Map operations are done via @TruffleBoundary to avoid JIT blockers.
 */
@NodeInfo(shortName = "record-spread")
class RecordSpreadNode(
    spreadNodes: List<MezzeNode>,
    fieldNames: List<String>,
    fieldNodesList: List<MezzeNode>
) : MezzeNode() {

    @field:Children
    private val spreadChildren: Array<MezzeNode> = spreadNodes.toTypedArray()

    @field:Children
    private val fieldChildren: Array<MezzeNode> = fieldNodesList.toTypedArray()

    @CompilationFinal(dimensions = 1)
    private val fieldNamesArray: Array<String> = fieldNames.toTypedArray()

    @CompilationFinal
    private val numSpreads: Int = spreadNodes.size

    @CompilationFinal
    private val numFields: Int = fieldNames.size

    override fun execute(frame: VirtualFrame): MezzeValue {
        // Execute spread nodes in the hot path
        val spreadValues = Array(numSpreads) { i ->
            spreadChildren[i].execute(frame)
        }

        // Execute field nodes in the hot path
        val fieldValues = Array(numFields) { i ->
            fieldChildren[i].execute(frame)
        }

        // Merge records via TruffleBoundary
        return mergeRecords(spreadValues, fieldValues)
    }

    @TruffleBoundary
    private fun mergeRecords(spreadValues: Array<MezzeValue>, fieldValues: Array<MezzeValue>): MezzeRecord {
        val mergedFields = mutableMapOf<String, MezzeValue>()

        // Merge all spread records
        for (spreadValue in spreadValues) {
            if (spreadValue !is MezzeRecord) {
                throw RuntimeException("Cannot spread non-record value: ${spreadValue::class.simpleName}")
            }
            mergedFields.putAll(spreadValue.fields)
        }

        // Override/add explicit field values
        for (i in 0 until numFields) {
            mergedFields[fieldNamesArray[i]] = fieldValues[i]
        }

        return MezzeRecord(mergedFields)
    }
}
