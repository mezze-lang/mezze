package com.mezze.language.interpreter.nodes.literal

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeArrayList

/**
 * Truffle node for ArrayList literal construction
 */
@NodeInfo(shortName = "array-literal")
class ArrayLiteralNode(
    private val elementNodes: Array<MezzeNode>
) : MezzeNode() {

    @field:Children
    private val children: Array<MezzeNode> = elementNodes

    override fun execute(frame: VirtualFrame): MezzeValue {
        val elements = ArrayList<MezzeValue>(elementNodes.size)

        for (elementNode in elementNodes) {
            val elementValue = elementNode.execute(frame)
            elements.add(elementValue)
        }

        return MezzeArrayList(elements)
    }
}