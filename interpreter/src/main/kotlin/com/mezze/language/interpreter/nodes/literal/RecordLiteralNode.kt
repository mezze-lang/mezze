package com.mezze.language.interpreter.nodes.literal

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeRecord
import com.mezze.language.interpreter.MezzeNull

/**
 * Truffle node for record literal construction
 */
@NodeInfo(shortName = "record-literal")
class RecordLiteralNode(
    private val fieldNodes: Map<String, MezzeNode>
) : MezzeNode() {
    
    @field:Children
    private val childNodes: Array<MezzeNode> = fieldNodes.values.toTypedArray()
    
    override fun execute(frame: VirtualFrame): MezzeValue {
        val fields = mutableMapOf<String, MezzeValue>()
        for ((fieldName, fieldNode) in fieldNodes) {
            val fieldValue = fieldNode.execute(frame)
            fields[fieldName] = fieldValue
        }
        return MezzeRecord(fields)
    }
}