package com.mezze.language.interpreter.nodes.literal

import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeArrayList
import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.nodes.MezzeNode
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo

/**
 * Array access node for accessing elements by index: array[index]
 */
@NodeInfo(shortName = "array-access")
class ArrayAccessNode(
    private val arrayNode: MezzeNode,
    private val indexNode: MezzeNode
) : MezzeNode() {

    @field:Child
    private var arrayChild: MezzeNode = arrayNode
    
    @field:Child
    private var indexChild: MezzeNode = indexNode

    override fun execute(frame: VirtualFrame): MezzeValue {
        val arrayValue = arrayChild.execute(frame)
        val indexValue = indexChild.execute(frame)
        
        if (arrayValue !is MezzeArrayList) {
            throw RuntimeException("Cannot index into non-array type: ${arrayValue::class.simpleName}")
        }
        
        if (indexValue !is MezzeInt) {
            throw RuntimeException("Array index must be an integer, got: ${indexValue::class.simpleName}")
        }
        
        val indexInt = indexValue.value.toInt()
        if (indexInt < 0 || indexInt >= arrayValue.size()) {
            throw RuntimeException("Array index out of bounds: $indexInt (array size: ${arrayValue.size()})")
        }

        return arrayValue.get(indexInt)
    }
}