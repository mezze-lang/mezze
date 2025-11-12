package com.funk.language.interpreter.nodes.literal

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkRecord

/**
 * Truffle node for record literal construction
 */
@NodeInfo(shortName = "record-literal")
class RecordLiteralNode(
    private val fieldNodes: Map<String, FunkNode>
) : FunkNode() {
    
    @field:Children
    private val childNodes: Array<FunkNode> = fieldNodes.values.toTypedArray()
    
    override fun execute(frame: VirtualFrame): FunkValue {
        val fields = mutableMapOf<String, FunkValue>()
        
        for ((fieldName, fieldNode) in fieldNodes) {
            val fieldValue = fieldNode.execute(frame)
            fields[fieldName] = fieldValue
        }
        
        return FunkRecord(fields)
    }
}