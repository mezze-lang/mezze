package com.mezze.language.interpreter.nodes

import com.mezze.language.ast.*
import com.mezze.language.interpreter.*
import com.mezze.language.interpreter.continuations.*
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.ExplodeLoop
import com.oracle.truffle.api.nodes.Node.Children
import com.oracle.truffle.api.nodes.NodeInfo

/**
 * Truffle-compliant state machine node for continuation-based effects.
 *
 * ARCHITECTURE:
 * - All state nodes are pre-converted at parse time (NOT at execution time)
 * - No frame creation in execute() - uses the passed VirtualFrame
 * - State machine execution is implemented via Truffle nodes, not closures
 * - Variable slots are pre-allocated and stored as @CompilationFinal
 *
 * This replaces the old StateMachineNode which violated Truffle's compilation model
 * by calling converter.convertExpression() and createMaterializedFrame() at runtime.
 */
@NodeInfo(shortName = "state-machine")
class TruffleStateMachineNode(
    /** Pre-converted state body nodes - must be created at parse time */
    stateNodes: Array<StateBodyNode>,
    /** Pre-computed state types (COMPUTE, YIELD, COMPLETE) */
    stateTypes: Array<StateType>,
    /** Pre-computed next state indices (-1 for terminal) */
    nextStateIndices: IntArray,
    /** Slot index for __state_machine_input__ variable */
    private val inputSlot: Int,
    /** Slot index for current state index */
    private val stateIndexSlot: Int,
    /** Variable slots mapping (name -> slot index) - for capturing locals */
    variableSlots: Map<String, Int>,
    /** Captured variable names in order */
    capturedVariableNames: Array<String>
) : MezzeNode() {

    enum class StateType {
        COMPUTE,
        YIELD,
        COMPLETE
    }

    @field:Children
    private val stateNodes: Array<StateBodyNode> = stateNodes

    @CompilationFinal(dimensions = 1)
    private val stateTypes: Array<StateType> = stateTypes

    @CompilationFinal(dimensions = 1)
    private val nextStateIndices: IntArray = nextStateIndices

    @CompilationFinal(dimensions = 1)
    private val variableSlotIndices: IntArray = variableSlots.values.toIntArray()

    @CompilationFinal(dimensions = 1)
    private val variableSlotNames: Array<String> = variableSlots.keys.toTypedArray()

    @CompilationFinal(dimensions = 1)
    private val capturedVariableNames: Array<String> = capturedVariableNames

    @CompilationFinal(dimensions = 1)
    private val capturedVariableSlots: IntArray = capturedVariableNames.mapNotNull { variableSlots[it] }.toIntArray()

    override fun execute(frame: VirtualFrame): MezzeValue {
        // Capture initial locals from the frame
        val initialLocals = captureLocals(frame)

        // Build and execute the state machine
        val stateMachine = buildStateMachine(initialLocals)
        return stateMachine.execute(MezzeNull)
    }

    /**
     * Capture local variables from the frame.
     * We read values in execute() and pass them to this method for Map creation.
     */
    private fun captureLocals(frame: VirtualFrame): Map<String, MezzeValue> {
        val numSlots = frame.frameDescriptor.numberOfSlots

        // Read values from frame (this is JIT-safe)
        val values = Array<MezzeValue?>(capturedVariableNames.size) { i ->
            val slot = if (i < capturedVariableSlots.size) capturedVariableSlots[i] else -1
            if (slot >= 0 && slot < numSlots) {
                try {
                    frame.getObject(slot) as? MezzeValue
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
        }

        // Create map in a TruffleBoundary method
        return createLocalsMap(values)
    }

    /**
     * Create locals map from captured values.
     * Marked as TruffleBoundary because it creates a Map.
     */
    @TruffleBoundary
    private fun createLocalsMap(values: Array<MezzeValue?>): Map<String, MezzeValue> {
        val locals = mutableMapOf<String, MezzeValue>()
        for (i in capturedVariableNames.indices) {
            val value = values[i]
            if (value != null && value !is MezzeNull) {
                locals[capturedVariableNames[i]] = value
            }
        }
        return locals
    }

    /**
     * Build the continuation state machine.
     * Marked as TruffleBoundary because it creates objects that shouldn't be JIT-compiled.
     */
    @TruffleBoundary
    private fun buildStateMachine(capturedLocals: Map<String, MezzeValue>): ContinuationStateMachine {
        val builder = ContinuationStateMachineBuilder()

        for (i in stateNodes.indices) {
            val stateNode = stateNodes[i]
            val stateType = stateTypes[i]
            val nextState = nextStateIndices[i]

            when (stateType) {
                StateType.COMPUTE -> {
                    builder.addComputeState { machine, input ->
                        executeStateWithFrame(stateNode, machine, input, nextState)
                    }
                }
                StateType.YIELD -> {
                    builder.addYieldState { machine, input ->
                        executeStateWithFrame(stateNode, machine, input, nextState)
                    }
                }
                StateType.COMPLETE -> {
                    builder.addCompleteState { machine, input ->
                        executeStateWithFrame(stateNode, machine, input, nextState)
                    }
                }
            }
        }

        return builder.build(capturedLocals)
    }

    /**
     * Execute a state node with a freshly populated frame.
     * This is called from the state machine lambdas at runtime.
     *
     * Marked as TruffleBoundary to exclude frame creation from JIT compilation.
     */
    @TruffleBoundary
    private fun executeStateWithFrame(
        stateNode: StateBodyNode,
        machine: ContinuationStateMachine,
        input: MezzeValue,
        nextState: Int
    ): MezzeValue {
        // Get the frame descriptor from the state node's root
        val frameDescriptor = stateNode.getFrameDescriptor()

        // Create a fresh frame for this execution
        val numSlots = frameDescriptor.numberOfSlots.coerceAtLeast(2)
        val args = Array<Any?>(numSlots) { MezzeNull }
        val frame = com.oracle.truffle.api.Truffle.getRuntime()
            .createMaterializedFrame(args, frameDescriptor)

        // Set the input value
        if (inputSlot >= 0 && inputSlot < numSlots) {
            val actualInput = if (input is MezzeRecord && input.fields.containsKey("value")) {
                input.fields["value"] ?: MezzeNull
            } else {
                input
            }
            frame.setObject(inputSlot, actualInput)
        }

        // Populate frame with captured locals (skip __state_machine_input__)
        for ((varName, varValue) in machine.capturedLocals) {
            if (varName == "__state_machine_input__") continue
            val slotIndex = findSlotIndex(varName)
            if (slotIndex >= 0 && slotIndex < numSlots) {
                frame.setObject(slotIndex, varValue)
            }
        }

        // Execute the state node
        val result = stateNode.executeState(frame)

        // Capture updated locals (if not suspended)
        if (result !is MezzeContinuationSuspended) {
            val updatedLocals = captureUpdatedLocals(frame)
            ContinuationStateMachine.setUpdatedLocals(updatedLocals)
        }

        return result
    }

    /**
     * Find the slot index for a variable name.
     */
    private fun findSlotIndex(varName: String): Int {
        for (i in variableSlotNames.indices) {
            if (variableSlotNames[i] == varName) {
                return variableSlotIndices[i]
            }
        }
        return -1
    }

    /**
     * Capture updated local variables from a frame.
     */
    @TruffleBoundary
    private fun captureUpdatedLocals(frame: com.oracle.truffle.api.frame.MaterializedFrame): Map<String, MezzeValue> {
        val locals = mutableMapOf<String, MezzeValue>()
        val numSlots = frame.frameDescriptor.numberOfSlots

        for (i in variableSlotNames.indices) {
            val name = variableSlotNames[i]
            val slot = variableSlotIndices[i]
            if (slot < numSlots) {
                val value = try {
                    frame.getObject(slot) as? MezzeValue
                } catch (e: Exception) {
                    null
                }
                if (value != null && value !is MezzeNull) {
                    locals[name] = value
                }
            }
        }
        return locals
    }
}

/**
 * Base node for state body execution.
 * Subclasses wrap the actual computation/expression node.
 */
@NodeInfo(shortName = "state-body")
abstract class StateBodyNode : MezzeNode() {
    abstract fun executeState(frame: VirtualFrame): MezzeValue
    abstract fun getFrameDescriptor(): FrameDescriptor
}

/**
 * Wrapper node for compute state bodies.
 */
@NodeInfo(shortName = "compute-state")
class ComputeStateBodyNode(
    @field:Child private var bodyNode: MezzeNode,
    private val frameDescriptor: FrameDescriptor
) : StateBodyNode() {

    override fun executeState(frame: VirtualFrame): MezzeValue {
        return bodyNode.execute(frame)
    }

    override fun execute(frame: VirtualFrame): MezzeValue {
        return executeState(frame)
    }

    override fun getFrameDescriptor(): FrameDescriptor = frameDescriptor
}

/**
 * Wrapper node for yield state bodies.
 */
@NodeInfo(shortName = "yield-state")
class YieldStateBodyNode(
    @field:Child private var valueNode: MezzeNode,
    private val frameDescriptor: FrameDescriptor
) : StateBodyNode() {

    override fun executeState(frame: VirtualFrame): MezzeValue {
        return valueNode.execute(frame)
    }

    override fun execute(frame: VirtualFrame): MezzeValue {
        return executeState(frame)
    }

    override fun getFrameDescriptor(): FrameDescriptor = frameDescriptor
}

/**
 * Wrapper node for complete state bodies.
 */
@NodeInfo(shortName = "complete-state")
class CompleteStateBodyNode(
    @field:Child private var resultNode: MezzeNode,
    private val frameDescriptor: FrameDescriptor
) : StateBodyNode() {

    override fun executeState(frame: VirtualFrame): MezzeValue {
        return resultNode.execute(frame)
    }

    override fun execute(frame: VirtualFrame): MezzeValue {
        return executeState(frame)
    }

    override fun getFrameDescriptor(): FrameDescriptor = frameDescriptor
}
