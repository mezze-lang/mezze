package com.mezze.language.interpreter.nodes.literal

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeArrayList
import com.mezze.language.interpreter.MezzeArraySlice

/**
 * Truffle node for Array literal with spread elements.
 * Handles the pattern [...xs, a, b, c] where spread arrays come first
 * and regular elements are appended.
 *
 * Note: Prepend syntax [a, b, ...xs] is not allowed and is caught at parse time.
 */
@NodeInfo(shortName = "array-spread")
class ArraySpreadNode(
    private val spreadArrays: Array<MezzeNode>,
    private val appendElements: Array<MezzeNode>
) : MezzeNode() {

    @field:Children
    private val spreadChildren: Array<MezzeNode> = spreadArrays

    @field:Children
    private val appendChildren: Array<MezzeNode> = appendElements

    override fun execute(frame: VirtualFrame): MezzeValue {
        val result = ArrayList<MezzeValue>()

        // First, add all elements from spread arrays (handles both MezzeArrayList and MezzeArraySlice)
        for (spreadNode in spreadChildren) {
            val spreadValue = spreadNode.execute(frame)
            when (spreadValue) {
                is MezzeArrayList -> result.addAll(spreadValue.toList())
                is MezzeArraySlice -> {
                    for (i in 0 until spreadValue.size()) {
                        result.add(spreadValue.get(i))
                    }
                }
                else -> throw RuntimeException("Spread operator requires an array, got: ${spreadValue::class.simpleName}")
            }
        }

        // Then append the regular elements
        for (appendNode in appendChildren) {
            val appendValue = appendNode.execute(frame)
            result.add(appendValue)
        }

        return MezzeArrayList(result)
    }
}
