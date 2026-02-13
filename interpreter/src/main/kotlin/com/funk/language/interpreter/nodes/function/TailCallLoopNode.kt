package com.funk.language.interpreter.nodes.function

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.LoopNode
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.nodes.RepeatingNode
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkRecord
import com.funk.language.interpreter.FunkNull

/**
 * Loop node that enables tail call optimization for self-recursive functions.
 *
 * This node wraps the function body and catches TailCallException.
 * When a tail call is detected (via TailCallNode throwing TailCallException),
 * we rebind the arguments and continue the loop instead of creating a new stack frame.
 *
 * Uses Truffle's LoopNode/RepeatingNode pattern for JIT optimization:
 * - The LoopNode manages on-stack replacement (OSR)
 * - The RepeatingNode determines when to continue vs stop
 *
 * Flow:
 * 1. TailCallLoopNode.execute() calls LoopNode.execute()
 * 2. LoopNode repeatedly calls TailCallRepeatingNode.executeRepeating()
 * 3. If body throws TailCallException, rebind args and return true (continue)
 * 4. If body returns normally, store result and return false (stop)
 */
@NodeInfo(shortName = "tail-call-loop")
class TailCallLoopNode(
    private val parameterNames: List<String>,
    @field:Child private var bodyNode: FunkNode
) : FunkNode() {

    @field:Child private var loopNode: LoopNode? = null

    /**
     * Mutable state for the current iteration.
     * These are stored at the node level and accessed during loop execution.
     * Thread safety: Each frame execution is single-threaded in Truffle.
     */
    private var currentArguments: FunkValue? = null
    private var result: FunkValue? = null

    override fun execute(frame: VirtualFrame): FunkValue {
        // Initialize current arguments from the frame
        // Arguments were already bound by FunkFunctionRootNode before calling body
        currentArguments = extractArgumentsFromFrame(frame)

        // Initialize loop node lazily
        if (loopNode == null) {
            loopNode = createLoopNode()
        }

        // Execute the loop - this will run until a normal return or an exception
        loopNode!!.execute(frame)

        // Return the result stored by the repeating node
        return result ?: FunkNull
    }

    private fun createLoopNode(): LoopNode {
        return Truffle.getRuntime().createLoopNode(TailCallRepeatingNode(this))
    }

    /**
     * Extract current arguments from frame as a record.
     * This is used when we need to capture the current state.
     */
    private fun extractArgumentsFromFrame(frame: VirtualFrame): FunkValue {
        val fields = mutableMapOf<String, FunkValue>()
        for ((index, paramName) in parameterNames.withIndex()) {
            val value = frame.getObject(index) as? FunkValue ?: FunkNull
            fields[paramName] = value
        }
        return FunkRecord(fields)
    }

    /**
     * Rebind arguments in the frame for the next iteration.
     */
    fun rebindArguments(frame: VirtualFrame, arguments: FunkValue) {
        if (arguments is FunkRecord) {
            for ((index, paramName) in parameterNames.withIndex()) {
                val value = arguments.fields[paramName] ?: FunkNull
                frame.setObject(index, value)
            }
        }
        currentArguments = arguments
    }

    /**
     * Store the final result.
     */
    fun setResult(value: FunkValue) {
        result = value
    }

    /**
     * Get the body node for execution.
     */
    fun getBodyNode(): FunkNode = bodyNode

    /**
     * RepeatingNode that executes the body and handles tail calls.
     * Must extend Node for Truffle's node graph.
     */
    private class TailCallRepeatingNode(
        private val parent: TailCallLoopNode
    ) : Node(), RepeatingNode {

        override fun executeRepeating(frame: VirtualFrame): Boolean {
            return try {
                // Execute the body
                val bodyResult = parent.getBodyNode().execute(frame)

                // Normal return - store result and stop
                parent.setResult(bodyResult)
                false // Stop the loop
            } catch (e: TailCallException) {
                // Tail call detected - rebind arguments and continue
                parent.rebindArguments(frame, e.arguments)
                true // Continue the loop
            }
        }
    }
}
