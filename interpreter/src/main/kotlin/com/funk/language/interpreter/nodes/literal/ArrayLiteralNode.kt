package com.funk.language.interpreter.nodes.literal

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkArrayList

/**
 * Truffle node for ArrayList literal construction
 */
@NodeInfo(shortName = "array-literal")
class ArrayLiteralNode(
    private val elementNodes: Array<FunkNode>
) : FunkNode() {
    
    @field:Children
    private val children: Array<FunkNode> = elementNodes
    
    override fun execute(frame: VirtualFrame): FunkValue {
        val elements = ArrayList<FunkValue>(elementNodes.size)
        
        for (elementNode in elementNodes) {
            val elementValue = elementNode.execute(frame)
            elements.add(elementValue)
        }
        
        return FunkArrayList(elements)
    }
}