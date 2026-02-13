package com.funk.language.interpreter.nodes.function

import com.funk.language.interpreter.FunkValue
import com.oracle.truffle.api.nodes.ControlFlowException

/**
 * Exception used for tail call optimization.
 *
 * When a self-recursive tail call is detected, instead of creating a new stack frame,
 * we throw this exception carrying the new arguments. The TailCallLoopNode catches it,
 * rebinds the arguments, and continues the loop.
 *
 * This extends Truffle's ControlFlowException which:
 * - Has no stack trace overhead
 * - Is designed for control flow (not error handling)
 * - Is JIT-friendly and can be optimized away
 */
class TailCallException(
    /** The new arguments for the next iteration */
    val arguments: FunkValue
) : ControlFlowException()
