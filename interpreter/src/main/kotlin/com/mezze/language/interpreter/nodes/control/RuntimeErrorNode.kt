package com.mezze.language.interpreter.nodes.control

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue

/**
 * Node that throws a runtime error with a given message.
 *
 * This is used for cases where the code should never be reached
 * but the type checker didn't catch the error.
 *
 * The error throwing is done via @TruffleBoundary to avoid
 * JIT blockers from exception construction.
 */
@NodeInfo(shortName = "runtime-error")
class RuntimeErrorNode(
    private val errorMessage: String
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        throwError()
    }

    @TruffleBoundary
    private fun throwError(): Nothing {
        throw RuntimeException(errorMessage)
    }
}
