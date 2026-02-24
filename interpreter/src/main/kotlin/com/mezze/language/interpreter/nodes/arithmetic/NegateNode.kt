package com.mezze.language.interpreter.nodes.arithmetic

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.MezzeFloat

/**
 * Node that performs numeric negation.
 *
 * Exception throwing is done via @TruffleBoundary to avoid JIT blockers.
 */
@NodeInfo(shortName = "negate")
class NegateNode(
    @field:Child private var operandChild: MezzeNode
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        val value = operandChild.execute(frame)
        return when (value) {
            is MezzeInt -> MezzeInt(-value.value)
            is MezzeFloat -> MezzeFloat(-value.value)
            else -> throwCannotNegate(value)
        }
    }

    @TruffleBoundary
    private fun throwCannotNegate(value: MezzeValue): Nothing {
        throw RuntimeException("Cannot negate ${value::class.simpleName}")
    }
}
