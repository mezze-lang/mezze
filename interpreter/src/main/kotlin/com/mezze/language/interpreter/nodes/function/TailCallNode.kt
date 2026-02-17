package com.mezze.language.interpreter.nodes.function

import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue

/**
 * Node that represents a self-recursive tail call.
 *
 * Instead of making an actual function call (which would create a new stack frame),
 * this node evaluates the arguments and throws a TailCallException.
 * The TailCallLoopNode wrapping the function body catches this exception,
 * rebinds the arguments, and continues execution.
 *
 * This implements the "trampoline via exception" pattern for TCO in Truffle.
 */
@NodeInfo(shortName = "tail-call")
class TailCallNode(
    @field:Child private var argumentsNode: MezzeNode
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        // Evaluate the arguments for the tail call
        val arguments = argumentsNode.execute(frame)

        // Throw the tail call exception - this will be caught by TailCallLoopNode
        throw TailCallException(arguments)
    }
}
