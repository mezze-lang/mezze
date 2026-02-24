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
    spreadArrays: Array<MezzeNode>,
    appendElements: Array<MezzeNode>
) : MezzeNode() {

    @field:Children
    private val spreadChildren: Array<MezzeNode> = spreadArrays

    @field:Children
    private val appendChildren: Array<MezzeNode> = appendElements

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private val numSpreadChildren: Int = spreadArrays.size

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private val numAppendChildren: Int = appendElements.size

    override fun execute(frame: VirtualFrame): MezzeValue {
        // Execute child nodes in hot path
        val spreadValues = Array(numSpreadChildren) { i ->
            spreadChildren[i].execute(frame)
        }
        val appendValues = Array(numAppendChildren) { i ->
            appendChildren[i].execute(frame)
        }

        // Build result list in boundary method
        return buildArrayWithSpread(spreadValues, appendValues)
    }

    /**
     * Build the array with spread elements.
     * Marked @TruffleBoundary to exclude ArrayList operations and exception handling.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private fun buildArrayWithSpread(spreadValues: Array<MezzeValue>, appendValues: Array<MezzeValue>): MezzeValue {
        val result = ArrayList<MezzeValue>()

        // First, add all elements from spread arrays (handles both MezzeArrayList and MezzeArraySlice)
        for (spreadValue in spreadValues) {
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
        for (appendValue in appendValues) {
            result.add(appendValue)
        }

        return MezzeArrayList(result)
    }
}
