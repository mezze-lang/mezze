package com.mezze.language.interpreter.continuations

import com.mezze.language.interpreter.*
import java.util.UUID

/**
 * Execution context for tracking the currently executing state machine.
 * Used by continuation primitives to access the current state machine.
 */
data class ContinuationExecutionContext(
    val machine: ContinuationStateMachine,
    val currentStateId: Int
)

/**
 * State machine representation of a delimited continuation.
 *
 * This is the core data structure for multishot (reusable) continuations.
 * State machines are IMMUTABLE - they can be executed multiple times with
 * different inputs without mutation (critical for multishot semantics).
 *
 * Design:
 * - Explicit state representation (not closures) for Truffle optimization
 * - Immutable structure for safe multishot execution
 * - No Loom dependencies - just pure state machine execution
 */
data class ContinuationStateMachine(
    val id: String = UUID.randomUUID().toString(),
    val currentState: Int,                          // Current state index
    val states: List<ContinuationState>,            // All states in the machine
    val capturedLocals: Map<String, MezzeValue> = emptyMap()  // Captured variables
) {
    /**
     * Execute this state machine starting from currentState with the given input value.
     * Returns the result of execution (either a value or a suspended continuation).
     *
     * This method does NOT mutate the state machine - it's safe to call multiple times
     * for multishot continuations.
     */
    fun execute(input: MezzeValue): MezzeValue {
        if (currentState >= states.size) {
            return MezzeNull
        }

        // Set execution context for continuation primitives
        val previousContext = executionContext.get()
        executionContext.set(ContinuationExecutionContext(this, currentState))
        try {
            val state = states[currentState]
            return state.execute(this, input)
        } finally {
            // Restore previous context
            executionContext.set(previousContext)
        }
    }

    companion object {
        /**
         * Thread-local storage for the currently executing state machine.
         * This allows continuation primitives (like capture) to access the
         * current execution context.
         */
        private val executionContext = ThreadLocal<ContinuationExecutionContext?>()

        /**
         * Thread-local storage for updated locals from state execution.
         * State lambdas can write updated variable mappings here,
         * which will be read by State.execute() and passed to transitionTo().
         */
        private val updatedLocals = ThreadLocal<Map<String, MezzeValue>?>()

        /**
         * Get the current execution context.
         * Returns null if not currently executing a state machine.
         */
        fun getCurrentContext(): ContinuationExecutionContext? {
            return executionContext.get()
        }

        /**
         * Set the current execution context.
         * Used internally by state machine execution.
         */
        internal fun setCurrentContext(context: ContinuationExecutionContext?) {
            executionContext.set(context)
        }

        /**
         * Set the updated locals for the current state execution.
         * Called by state lambdas after capturing variables.
         */
        fun setUpdatedLocals(locals: Map<String, MezzeValue>) {
            updatedLocals.set(locals)
        }

        /**
         * Get and clear the updated locals.
         * Called by State.execute() to retrieve captured variables.
         */
        fun getAndClearUpdatedLocals(): Map<String, MezzeValue>? {
            val locals = updatedLocals.get()
            updatedLocals.remove()
            return locals
        }
    }

    /**
     * Clone this continuation for multishot execution.
     * Since state machines are immutable, this is a shallow copy.
     */
    fun clone(): ContinuationStateMachine {
        return this.copy(
            capturedLocals = capturedLocals.toMap()  // Defensive copy
        )
    }

    /**
     * Transition to the next state with updated context.
     */
    fun transitionTo(nextStateIndex: Int, updatedLocals: Map<String, MezzeValue>): ContinuationStateMachine {
        return copy(
            currentState = nextStateIndex,
            capturedLocals = updatedLocals
        )
    }
}

/**
 * Individual state in the continuation state machine.
 *
 * Each state knows how to execute itself and return either:
 * - A transition to the next state
 * - A suspended continuation (yield)
 * - A final result (complete)
 */
sealed class ContinuationState(val stateId: Int) {

    /**
     * Execute this state with the given machine and input value.
     * Returns the result of execution.
     */
    abstract fun execute(machine: ContinuationStateMachine, input: MezzeValue): MezzeValue

    /**
     * Compute state - performs a computation and transitions to next state.
     *
     * The computation function takes the machine and input, and produces a result.
     * This result is then passed to the next state.
     */
    data class ComputeState(
        val id: Int,
        val computation: (ContinuationStateMachine, MezzeValue) -> MezzeValue,
        val nextState: Int
    ) : ContinuationState(id) {
        override fun execute(machine: ContinuationStateMachine, input: MezzeValue): MezzeValue {
            val result = computation(machine, input)

            // CRITICAL: Check if result is a suspended continuation (for early return, exception handling)
            // If so, return it immediately without executing remaining states
            // This implements early return semantics for Return effect and exception handling
            if (result is MezzeContinuationSuspended) {
                return result
            }

            if (nextState == -1 || nextState >= machine.states.size) {
                // Terminal state - return result directly
                return result
            }

            // Check if the computation updated the locals via ThreadLocal
            val updatedLocals = ContinuationStateMachine.getAndClearUpdatedLocals()
                ?: machine.capturedLocals  // Fall back to existing locals if none were updated

            // Continue to next state with updated locals
            val nextMachine = machine.transitionTo(nextState, updatedLocals)
            return nextMachine.execute(result)
        }
    }

    /**
     * Yield state - suspends execution and returns a suspended continuation.
     *
     * This creates a MezzeContinuationSuspended that contains:
     * - The value being yielded
     * - A continuation that can be resumed from the next state
     */
    data class YieldState(
        val id: Int,
        val valueProducer: (ContinuationStateMachine, MezzeValue) -> MezzeValue,
        val nextState: Int
    ) : ContinuationState(id) {
        override fun execute(machine: ContinuationStateMachine, input: MezzeValue): MezzeValue {
            val yieldedValue = valueProducer(machine, input)

            // Create continuation pointing to next state
            val resumableMachine = machine.transitionTo(nextState, machine.capturedLocals)
            val resumeFunction = ContinuationResumeFn(resumableMachine)
            val continuation = MezzeContinuation(resumeFunction, "yield@${machine.id}@$id")

            return MezzeContinuationSuspended(continuation, yieldedValue)
        }
    }

    /**
     * Complete state - computation finished, return final result.
     */
    data class CompleteState(
        val id: Int,
        val resultProducer: (ContinuationStateMachine, MezzeValue) -> MezzeValue
    ) : ContinuationState(id) {
        override fun execute(machine: ContinuationStateMachine, input: MezzeValue): MezzeValue {
            return resultProducer(machine, input)
        }
    }
}

/**
 * Create a resume function that wraps a continuation state machine.
 *
 * This is the actual function stored inside MezzeContinuation.
 * When called, it executes the state machine from its current state.
 */
fun ContinuationResumeFn(machine: ContinuationStateMachine): NativeMezzeFunction {
    return NativeMezzeFunction("resume@${machine.id}") { args ->
        val argRecord = args.firstOrNull() as? MezzeRecord
            ?: throw MezzeException("Continuation expects record argument with 'value' field")

        val resumeValue = argRecord.fields["value"] ?: MezzeNull

        // Clone the machine for multishot support - each resume is independent
        val clonedMachine = machine.clone()

        // Execute from current state with the resume value
        clonedMachine.execute(resumeValue)
    }
}

/**
 * Builder for creating continuation state machines.
 */
class ContinuationStateMachineBuilder {
    private val states = mutableListOf<ContinuationState>()
    private var stateCounter = 0

    fun addComputeState(
        computation: (ContinuationStateMachine, MezzeValue) -> MezzeValue
    ): ContinuationStateMachineBuilder {
        val id = stateCounter++
        states.add(ContinuationState.ComputeState(
            id = id,
            computation = computation,
            nextState = stateCounter  // Points to next state by default
        ))
        return this
    }

    fun addYieldState(
        valueProducer: (ContinuationStateMachine, MezzeValue) -> MezzeValue
    ): ContinuationStateMachineBuilder {
        val id = stateCounter++
        states.add(ContinuationState.YieldState(
            id = id,
            valueProducer = valueProducer,
            nextState = stateCounter  // Resume point is next state
        ))
        return this
    }

    fun addCompleteState(
        resultProducer: (ContinuationStateMachine, MezzeValue) -> MezzeValue
    ): ContinuationStateMachineBuilder {
        val id = stateCounter++
        states.add(ContinuationState.CompleteState(
            id = id,
            resultProducer = resultProducer
        ))
        return this
    }

    fun build(capturedLocals: Map<String, MezzeValue> = emptyMap()): ContinuationStateMachine {
        return ContinuationStateMachine(
            currentState = 0,
            states = states.toList(),
            capturedLocals = capturedLocals
        )
    }
}
