package com.funk.language.interpreter.nodes.literal

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkRecord
import com.funk.language.interpreter.FunkNull

/**
 * Node for accessing a field from a record value
 */
@NodeInfo(shortName = "record-field-access")
class RecordFieldAccessNode(
    @field:Child private var recordNode: FunkNode,
    private val fieldName: String
) : FunkNode() {

    override fun execute(frame: VirtualFrame): FunkValue {
        println("DEBUG RecordFieldAccessNode: accessing field '$fieldName'")
        val recordValue = recordNode.execute(frame)
        println("DEBUG RecordFieldAccessNode: record = ${recordValue::class.simpleName}, fields = ${(recordValue as? FunkRecord)?.fields?.keys}")

        if (recordValue !is FunkRecord) {
            throw RuntimeException("Cannot access field '$fieldName' on non-record value: ${recordValue::class.simpleName}")
        }

        val result = recordValue.fields[fieldName] ?: FunkNull
        println("DEBUG RecordFieldAccessNode: field '$fieldName' = ${result::class.simpleName}")
        return result
    }
}
