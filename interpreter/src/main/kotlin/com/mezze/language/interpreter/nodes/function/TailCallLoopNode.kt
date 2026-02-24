package com.mezze.language.interpreter.nodes.function

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.LoopNode
import com.oracle.truffle.api.nodes.Node
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.nodes.RepeatingNode
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeRecord
import com.mezze.language.interpreter.MezzeNull

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
    parameterNames: List<String>,
    @field:Child private var bodyNode: MezzeNode
) : MezzeNode() {

    // Pre-compute parameter names array and count for fast access
    @CompilationFinal(dimensions = 1)
    private val paramNamesArray: Array<String> = parameterNames.toTypedArray()

    @CompilationFinal
    private val paramCount: Int = parameterNames.size

    /**
     * LoopNode created at construction time per Truffle's compilation model.
     * Nodes must be created at parse time, not during execution.
     */
    @field:Child private var loopNode: LoopNode = createLoopNodeAtConstruction()

    /**
     * Mutable state for the current iteration.
     * These are stored at the node level and accessed during loop execution.
     * Thread safety: Each frame execution is single-threaded in Truffle.
     */
    private var currentArguments: MezzeValue? = null
    private var result: MezzeValue? = null

    override fun execute(frame: VirtualFrame): MezzeValue {
        // Initialize current arguments from the frame
        // Arguments were already bound by MezzeFunctionRootNode before calling body
        // Extract values in hot path, build record via boundary
        val values = Array(paramCount) { i ->
            frame.getObject(i) as? MezzeValue ?: MezzeNull
        }
        currentArguments = buildRecordFromValues(values)

        // Execute the loop - this will run until a normal return or an exception
        loopNode.execute(frame)

        // Return the result stored by the repeating node
        return result ?: MezzeNull
    }

    /**
     * Create the loop node at construction time.
     * This is called from the field initializer to ensure nodes are created at parse time.
     */
    private fun createLoopNodeAtConstruction(): LoopNode {
        return Truffle.getRuntime().createLoopNode(TailCallRepeatingNode(this))
    }

    /**
     * Build a record from extracted values.
     * Marked as @TruffleBoundary because Map operations trigger JIT blockers.
     */
    @TruffleBoundary
    private fun buildRecordFromValues(values: Array<MezzeValue>): MezzeRecord {
        val fields = mutableMapOf<String, MezzeValue>()
        for (i in 0 until paramCount) {
            fields[paramNamesArray[i]] = values[i]
        }
        return MezzeRecord(fields)
    }

    /**
     * Rebind arguments in the frame for the next iteration.
     * Must extract values via boundary, then write to frame in hot path.
     */
    fun rebindArguments(frame: VirtualFrame, arguments: MezzeValue) {
        if (arguments is MezzeRecord) {
            // Extract values via boundary (Map operations)
            val values = extractValuesFromRecord(arguments)
            // Write to frame in hot path
            var i = 0
            while (i < paramCount) {
                frame.setObject(i, values[i])
                i++
            }
        }
        currentArguments = arguments
    }

    /**
     * Extract values from record in parameter order.
     * Marked as @TruffleBoundary because Map.get() triggers JIT blockers.
     */
    @TruffleBoundary
    private fun extractValuesFromRecord(record: MezzeRecord): Array<MezzeValue> {
        return Array(paramCount) { i ->
            record.fields[paramNamesArray[i]] ?: MezzeNull
        }
    }

    /**
     * Store the final result.
     */
    fun setResult(value: MezzeValue) {
        result = value
    }

    /**
     * Get the body node for execution.
     */
    fun getBodyNode(): MezzeNode = bodyNode

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
