package com.mezze.language.interpreter.nodes.arithmetic

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeBool

/**
 * Node that performs logical not operation.
 *
 * Exception throwing is done via @TruffleBoundary to avoid JIT blockers.
 */
@NodeInfo(shortName = "logical-not")
class LogicalNotNode(
    @field:Child private var operandChild: MezzeNode
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        val value = operandChild.execute(frame)
        return when (value) {
            is MezzeBool -> MezzeBool(!value.value)
            else -> throwCannotApplyNot(value)
        }
    }

    @TruffleBoundary
    private fun throwCannotApplyNot(value: MezzeValue): Nothing {
        throw RuntimeException("Cannot apply logical not to ${value::class.simpleName}")
    }
}
