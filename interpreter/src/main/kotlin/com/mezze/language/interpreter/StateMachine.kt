package com.mezze.language.interpreter

import com.mezze.language.ast.Expression
import com.mezze.language.ast.Statement
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Represents different types of states in a state machine
 */
sealed class StateMachineState(val stateId: Int) {
    data class LetState(val id: Int, val variableName: String, val expression: Expression) : StateMachineState(id)
    data class ExpressionState(val id: Int, val expression: Expression) : StateMachineState(id)
    data class EffectState(val id: Int, val expression: Expression) : StateMachineState(id)
    data class ResultState(val id: Int, val expression: Expression) : StateMachineState(id)
}

/**
 * Execution state of a state machine
 */
enum class ExecutionState {
    RUNNING,      // Currently executing
    SUSPENDED,    // Suspended waiting for async operation
    COMPLETED,    // Finished successfully
    FAILED        // Failed with error
}

/**
 * Represents a suspended computation that can be resumed
 */
data class StateMachine(
    val id: String = UUID.randomUUID().toString(),
    val currentState: Int,
    val localVariables: MutableMap<String, MezzeValue>,
    val continuations: List<Continuation>,
    val effectContext: StateMachineEffectContext,
    val executionState: ExecutionState = ExecutionState.RUNNING
) {
    companion object {
        fun create(
            localVariables: MutableMap<String, MezzeValue> = mutableMapOf(),
            effectContext: StateMachineEffectContext = StateMachineEffectContext()
        ): StateMachine {
            return StateMachine(
                currentState = 0,
                localVariables = localVariables,
                continuations = emptyList(),
                effectContext = effectContext
            )
        }
    }

    /**
     * Create a new state machine with updated state
     */
    fun withState(
        newState: Int = currentState,
        newVariables: MutableMap<String, MezzeValue> = localVariables,
        newContinuations: List<Continuation> = continuations,
        newExecutionState: ExecutionState = executionState
    ): StateMachine {
        return copy(
            currentState = newState,
            localVariables = newVariables,
            continuations = newContinuations,
            executionState = newExecutionState
        )
    }

    /**
     * Add a variable to the local scope
     */
    fun withVariable(name: String, value: MezzeValue): StateMachine {
        val newVariables = localVariables.toMutableMap()
        newVariables[name] = value
        return copy(localVariables = newVariables)
    }

    /**
     * Suspend the state machine at the current point
     */
    fun suspend(continuation: Continuation): StateMachine {
        return copy(
            continuations = continuations + continuation,
            executionState = ExecutionState.SUSPENDED
        )
    }

    /**
     * Resume execution with a value
     */
    fun resume(value: MezzeValue): StateMachine {
        return if (continuations.isNotEmpty()) {
            val continuation = continuations.last()
            val remainingContinuations = continuations.dropLast(1)
            continuation.resume(value, copy(continuations = remainingContinuations))
        } else {
            copy(executionState = ExecutionState.COMPLETED)
        }
    }

    /**
     * Handle an error during execution
     */
    fun handleError(error: Throwable): StateMachine {
        return if (continuations.isNotEmpty()) {
            val continuation = continuations.last()
            val remainingContinuations = continuations.dropLast(1)
            continuation.handleError(error, copy(continuations = remainingContinuations))
        } else {
            copy(executionState = ExecutionState.FAILED)
        }
    }
}

/**
 * Context for effect handling within a state machine (legacy)
 * Note: This is the old state machine effect context. New code should use
 * com.mezze.language.interpreter.effects.EffectContext
 */
data class StateMachineEffectContext(
    val handlers: Map<String, MezzeRecord> = emptyMap(),
    val currentHandler: MezzeRecord? = null
) {
    fun withHandlers(newHandlers: Map<String, MezzeRecord>): StateMachineEffectContext {
        return copy(handlers = handlers + newHandlers)
    }

    fun withCurrentHandler(handler: MezzeRecord): StateMachineEffectContext {
        return copy(currentHandler = handler)
    }
}

/**
 * Represents a continuation point where execution can be resumed
 */
interface Continuation {
    /**
     * Resume execution with the given value
     */
    fun resume(value: MezzeValue, stateMachine: StateMachine): StateMachine

    /**
     * Handle an error that occurred during async execution
     */
    fun handleError(error: Throwable, stateMachine: StateMachine): StateMachine
}

/**
 * Continuation for let bindings in do blocks
 */
data class LetContinuation(
    val variableName: String,
    val nextState: Int
) : Continuation {
    override fun resume(value: MezzeValue, stateMachine: StateMachine): StateMachine {
        return stateMachine
            .withVariable(variableName, value)
            .withState(nextState, newExecutionState = ExecutionState.RUNNING)
    }

    override fun handleError(error: Throwable, stateMachine: StateMachine): StateMachine {
        return stateMachine.copy(executionState = ExecutionState.FAILED)
    }
}

/**
 * Continuation for expression statements in do blocks
 */
data class ExpressionContinuation(
    val nextState: Int
) : Continuation {
    override fun resume(value: MezzeValue, stateMachine: StateMachine): StateMachine {
        return stateMachine.withState(nextState, newExecutionState = ExecutionState.RUNNING)
    }

    override fun handleError(error: Throwable, stateMachine: StateMachine): StateMachine {
        return stateMachine.copy(executionState = ExecutionState.FAILED)
    }
}

/**
 * Continuation for the final result of a do block
 */
data class ResultContinuation(
    val result: MezzeValue
) : Continuation {
    override fun resume(value: MezzeValue, stateMachine: StateMachine): StateMachine {
        return stateMachine.copy(executionState = ExecutionState.COMPLETED)
    }

    override fun handleError(error: Throwable, stateMachine: StateMachine): StateMachine {
        return stateMachine.copy(executionState = ExecutionState.FAILED)
    }
}

/**
 * Represents a suspension point in the execution (legacy)
 * Note: This is the old state machine suspension point. New code should use
 * com.mezze.language.interpreter.effects.SuspensionPoint
 */
data class StateMachineSuspensionPoint(
    val stateMachine: StateMachine,
    val asyncOperation: CompletableFuture<MezzeValue>
) {
    /**
     * Complete the suspension point with a value
     */
    fun complete(value: MezzeValue): StateMachine {
        return stateMachine.resume(value)
    }

    /**
     * Complete the suspension point with an error
     */
    fun completeExceptionally(error: Throwable): StateMachine {
        return stateMachine.handleError(error)
    }
}

/**
 * Manages the execution of state machines
 */
class StateMachineExecutor {
    private val activeMachines = mutableMapOf<String, StateMachine>()
    private val suspendedMachines = mutableMapOf<String, StateMachineSuspensionPoint>()

    /**
     * Start executing a state machine
     */
    fun execute(stateMachine: StateMachine): CompletableFuture<MezzeValue> {
        activeMachines[stateMachine.id] = stateMachine
        return executeStep(stateMachine)
    }

    /**
     * Execute a single step of the state machine
     */
    private fun executeStep(stateMachine: StateMachine): CompletableFuture<MezzeValue> {
        return when (stateMachine.executionState) {
            ExecutionState.COMPLETED -> CompletableFuture.completedFuture(MezzeNull) // TODO: return actual result
            ExecutionState.FAILED -> CompletableFuture.failedFuture(RuntimeException("State machine failed"))
            ExecutionState.SUSPENDED -> {
                // This should not happen in executeStep
                CompletableFuture.failedFuture(RuntimeException("Unexpected suspended state"))
            }
            ExecutionState.RUNNING -> {
                // This would be implemented by the specific state machine execution logic
                // For now, just complete
                CompletableFuture.completedFuture(MezzeNull)
            }
        }
    }

    /**
     * Suspend a state machine for an async operation
     */
    fun suspend(stateMachine: StateMachine, asyncOperation: CompletableFuture<MezzeValue>): CompletableFuture<MezzeValue> {
        val suspensionPoint = StateMachineSuspensionPoint(
            stateMachine.copy(executionState = ExecutionState.SUSPENDED),
            asyncOperation
        )
        suspendedMachines[stateMachine.id] = suspensionPoint
        activeMachines.remove(stateMachine.id)

        return asyncOperation.thenCompose { value ->
            val resumedMachine = suspensionPoint.complete(value)
            suspendedMachines.remove(stateMachine.id)
            executeStep(resumedMachine)
        }.exceptionally { error ->
            val failedMachine = suspensionPoint.completeExceptionally(error)
            suspendedMachines.remove(stateMachine.id)
            throw error
        }
    }

    /**
     * Get the current state of all machines
     */
    fun getStatus(): Map<String, ExecutionState> {
        val result = mutableMapOf<String, ExecutionState>()
        activeMachines.forEach { (id, machine) -> result[id] = machine.executionState }
        suspendedMachines.forEach { (id, suspension) -> result[id] = suspension.stateMachine.executionState }
        return result
    }
}
