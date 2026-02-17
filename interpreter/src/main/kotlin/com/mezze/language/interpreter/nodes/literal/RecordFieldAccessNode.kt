package com.mezze.language.interpreter.nodes.literal

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeRecord
import com.mezze.language.interpreter.MezzeNull

/**
 * Node for accessing a field from a record value
 */
@NodeInfo(shortName = "record-field-access")
class RecordFieldAccessNode(
    @field:Child private var recordNode: MezzeNode,
    private val fieldName: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        val recordValue = recordNode.execute(frame)

        if (recordValue !is MezzeRecord) {
            throw RuntimeException("Cannot access field '$fieldName' on non-record value: ${recordValue::class.simpleName}")
        }

        return recordValue.fields[fieldName] ?: MezzeNull
    }
}
