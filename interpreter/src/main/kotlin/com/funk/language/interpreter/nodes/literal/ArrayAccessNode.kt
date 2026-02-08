package com.funk.language.interpreter.nodes.literal

import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkArrayList
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.nodes.FunkNode
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo

/**
 * Array access node for accessing elements by index: array[index]
 */
@NodeInfo(shortName = "array-access")
class ArrayAccessNode(
    private val arrayNode: FunkNode,
    private val indexNode: FunkNode
) : FunkNode() {

    @field:Child
    private var arrayChild: FunkNode = arrayNode
    
    @field:Child
    private var indexChild: FunkNode = indexNode

    override fun execute(frame: VirtualFrame): FunkValue {
        val arrayValue = arrayChild.execute(frame)
        val indexValue = indexChild.execute(frame)
        
        if (arrayValue !is FunkArrayList) {
            throw RuntimeException("Cannot index into non-array type: ${arrayValue::class.simpleName}")
        }
        
        if (indexValue !is FunkInt) {
            throw RuntimeException("Array index must be an integer, got: ${indexValue::class.simpleName}")
        }
        
        val indexInt = indexValue.value.toInt()
        if (indexInt < 0 || indexInt >= arrayValue.size()) {
            throw RuntimeException("Array index out of bounds: $indexInt (array size: ${arrayValue.size()})")
        }

        return arrayValue.get(indexInt)
    }
}