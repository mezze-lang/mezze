package com.funk.language.interpreter.nodes.literal

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkRecord
import com.funk.language.interpreter.FunkNull

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
        println("DEBUG RecordLiteralNode: fieldNodes.keys = ${fieldNodes.keys}")
        for ((fieldName, fieldNode) in fieldNodes) {
            println("DEBUG RecordLiteralNode: executing field '$fieldName', node type = ${fieldNode::class.simpleName}")
            val fieldValue = fieldNode.execute(frame)
            println("DEBUG RecordLiteralNode: field '$fieldName' = ${fieldValue::class.simpleName}")
            fields[fieldName] = fieldValue
        }
        println("DEBUG RecordLiteralNode: final fields = ${fields.keys}")
        return FunkRecord(fields)
    }
}