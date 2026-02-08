package com.funk.language.interpreter.nodes.literal

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkArrayList
import com.funk.language.interpreter.FunkArraySlice

/**
 * Truffle node for Array literal with spread elements.
 * Handles the pattern [...xs, a, b, c] where spread arrays come first
 * and regular elements are appended.
 *
 * Note: Prepend syntax [a, b, ...xs] is not allowed and is caught at parse time.
 */
@NodeInfo(shortName = "array-spread")
class ArraySpreadNode(
    private val spreadArrays: Array<FunkNode>,
    private val appendElements: Array<FunkNode>
) : FunkNode() {

    @field:Children
    private val spreadChildren: Array<FunkNode> = spreadArrays

    @field:Children
    private val appendChildren: Array<FunkNode> = appendElements

    override fun execute(frame: VirtualFrame): FunkValue {
        val result = ArrayList<FunkValue>()

        // First, add all elements from spread arrays (handles both FunkArrayList and FunkArraySlice)
        for (spreadNode in spreadChildren) {
            val spreadValue = spreadNode.execute(frame)
            when (spreadValue) {
                is FunkArrayList -> result.addAll(spreadValue.toList())
                is FunkArraySlice -> {
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

        return FunkArrayList(result)
    }
}
