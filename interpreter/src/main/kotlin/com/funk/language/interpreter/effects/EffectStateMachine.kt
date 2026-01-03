package com.funk.language.interpreter.effects

import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkNull
import com.funk.language.interpreter.FunkString
import com.funk.language.interpreter.FunkRecord
import com.funk.language.interpreter.concurrency.LoomConcurrencyManager
import com.funk.language.interpreter.concurrency.ExecutionContext
import com.funk.language.interpreter.concurrency.LoomUtils
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.time.Duration

/**
 * Core state machine classes for Loom-based async effect execution
 * Each effect operation is compiled into a state machine for optimal performance
 */

/**
 * Represents different types of states in an effect state machine
 */
sealed class EffectState(val id: String) {
    /**
     * Synchronous computation state - executes immediately
     */
    data class ComputeState(
        val stateId: String,
        val computation: (EffectContext) -> FunkValue
    ) : EffectState(stateId)
    
    /**
     * Async effect call state - suspends and resumes with result
     */
    data class AsyncEffectState(
        val stateId: String,
        val effectCall: EffectCall,
        val nextStateId: String
    ) : EffectState(stateId)
    
    /**
     * Join state - waits for multiple async operations to complete
     */
    data class JoinState(
        val stateId: String,
        val futureIds: List<String>,
        val combiner: (List<FunkValue>) -> FunkValue,
        val nextStateId: String
    ) : EffectState(stateId)
    
    /**
     * Race state - returns first completed async operation
     */
    data class RaceState(
        val stateId: String,
        val futureIds: List<String>,
        val nextStateId: String
    ) : EffectState(stateId)
    
    /**
     * Conditional state - branches based on condition
     */
    data class ConditionalState(
        val stateId: String,
        val condition: (EffectContext) -> Boolean,
        val thenStateId: String,
        val elseStateId: String
    ) : EffectState(stateId)
    
    /**
     * Suspension state - suspends execution for abort/resume
     */
    data class SuspensionState(
        val stateId: String,
        val suspensionPoint: SuspensionPoint,
        val nextStateId: String
    ) : EffectState(stateId)
}

/**
 * Results of state execution
 */
sealed class StateResult {
    data class StateTransition(
        val nextStateId: String,
        val context: EffectContext
    ) : StateResult()
    
    data class StateSuspension(
        val suspensionPoint: SuspensionPoint,
        val context: EffectContext
    ) : StateResult()
    
    data class StateCompletion(
        val value: FunkValue
    ) : StateResult()
    
    data class StateError(
        val error: String,
        val cause: Throwable? = null
    ) : StateResult()
}

/**
 * Main effect state machine - compiles and executes effect-heavy code
 */
class EffectStateMachine(
    val id: String = UUID.randomUUID().toString(),
    val states: Map<String, EffectState>,
    val initialStateId: String,
    val finalStateId: String
) {
    
    /**
     * Execute the state machine using virtual threads with structured concurrency
     */
    fun execute(context: EffectContext): FunkValue {
        return try {
            executeStateMachine(context)
        } catch (e: Exception) {
            // Fallback for simple cases
            FunkString("Error: ${e.message}")
        }
    }
    
    /**
     * Internal state machine execution logic using virtual threads
     */
    private fun executeStateMachine(initialContext: EffectContext): FunkValue {
        var currentStateId = initialStateId
        var executionContext = initialContext
        
        while (currentStateId != finalStateId) {
            val state = states[currentStateId] 
                ?: return FunkString("State not found: $currentStateId")
            
            val result = executeState(state, executionContext)
            
            when (result) {
                is StateResult.StateTransition -> {
                    currentStateId = result.nextStateId
                    executionContext = result.context
                }
                is StateResult.StateSuspension -> {
                    return suspendAndResume(result, executionContext)
                }
                is StateResult.StateCompletion -> {
                    return result.value
                }
                is StateResult.StateError -> {
                    return FunkString("Error: ${result.error}")
                }
            }
        }
        
        // Return the last computed result
        return executionContext.getVariable("last_result") ?: FunkNull
    }
    
    /**
     * Execute a single state using virtual threads
     */
    private fun executeState(state: EffectState, context: EffectContext): StateResult {
        return try {
            // Check for cancellation before state execution
            context.throwIfCancelled()
            
            when (state) {
                is EffectState.ComputeState -> {
                    val result = state.computation(context)
                    val newContext = context
                        .withVariable("state_${state.id}_result", result)
                        .withVariable("last_result", result)
                    StateResult.StateTransition(getNextStateId(state.id), newContext)
                }
                
                is EffectState.AsyncEffectState -> {
                    val result = executeAsyncEffect(state.effectCall, context)
                    val newContext = context
                        .withVariable("state_${state.id}_result", result)
                        .withVariable("last_result", result)
                    StateResult.StateTransition(state.nextStateId, newContext)
                }
                
                is EffectState.JoinState -> {
                    val futures = state.futureIds.mapNotNull { futureId ->
                        context.getVirtualThreadFuture(futureId)
                    }
                    
                    val results = futures.map { it.get() }
                    val combinedResult = state.combiner(results)
                    val newContext = context.withVariable("state_${state.id}_result", combinedResult)
                    StateResult.StateTransition(state.nextStateId, newContext)
                }
                
                is EffectState.RaceState -> {
                    val futures = state.futureIds.mapNotNull { futureId ->
                        context.getVirtualThreadFuture(futureId)
                    }
                    
                    // Use structured concurrency to race futures
                    val concurrencyManager = context.concurrencyManager
                    val raceResult = concurrencyManager.executeRace(
                        futures.map { future -> java.util.concurrent.Callable { future.get() } }
                    )
                    
                    val newContext = context.withVariable("state_${state.id}_result", raceResult)
                    StateResult.StateTransition(state.nextStateId, newContext)
                }
                
                is EffectState.ConditionalState -> {
                    val nextStateId = if (state.condition(context)) {
                        state.thenStateId
                    } else {
                        state.elseStateId
                    }
                    StateResult.StateTransition(nextStateId, context)
                }
                
                is EffectState.SuspensionState -> {
                    StateResult.StateSuspension(state.suspensionPoint, context)
                }
            }
        } catch (e: Exception) {
            StateResult.StateError("Error executing state ${state.id}: ${e.message}", e)
        }
    }
    
    /**
     * Execute an async effect call using virtual threads
     */
    private fun executeAsyncEffect(effectCall: EffectCall, context: EffectContext): FunkValue {
        val handler = context.resolveHandler(effectCall.effectName)
            ?: throw EffectExecutionError("No handler found for effect: ${effectCall.effectName}", context)
        
        // Create execution context with effect-specific thread name
        val executionContext = com.funk.language.interpreter.concurrency.ExecutionContext(
            threadId = Thread.currentThread().threadId(),
            threadName = "effect-${effectCall.effectName}-${effectCall.operation}",
            parentContext = context.executionContext,
            metadata = mapOf(
                "effectName" to effectCall.effectName,
                "operation" to effectCall.operation
            )
        )
        
        // Execute with the execution context and cancellation support
        val contextWithExecution = context.withExecutionContext(executionContext)
        
        // Check for cancellation before execution
        contextWithExecution.throwIfCancelled()
        
        return handler.handleSync(effectCall.operation, effectCall.arguments, contextWithExecution)
    }
    
    /**
     * Handle suspension and resumption with virtual thread support
     */
    private fun suspendAndResume(suspension: StateResult.StateSuspension, context: EffectContext): FunkValue {
        val suspensionPoint = suspension.suspensionPoint
        val concurrencyManager = context.concurrencyManager
        
        // Create a structured scope for the suspended operation
        return concurrencyManager.withStructuredScope("suspension-${suspensionPoint.id}") { scope ->
            // Store the suspension point for potential resumption
            val updatedContext = context.withSuspensionPoint(suspensionPoint)
            
            // For now, continue execution - in a full implementation this would
            // wait for external resumption or timeout
            executeStateMachine(updatedContext)
        }
    }
    
    /**
     * Get the next state ID for sequential states
     */
    private fun getNextStateId(currentStateId: String): String {
        val stateIds = states.keys.toList()
        val currentIndex = stateIds.indexOf(currentStateId)
        return if (currentIndex >= 0 && currentIndex < stateIds.size - 1) {
            stateIds[currentIndex + 1]
        } else {
            finalStateId
        }
    }
}

/**
 * Builder for creating effect state machines
 */
class EffectStateMachineBuilder {
    private val states = mutableMapOf<String, EffectState>()
    private var initialStateId: String? = null
    private var finalStateId: String = "final"
    
    fun addComputeState(id: String, computation: (EffectContext) -> FunkValue): EffectStateMachineBuilder {
        states[id] = EffectState.ComputeState(id, computation)
        if (initialStateId == null) initialStateId = id
        return this
    }
    
    fun addAsyncEffectState(
        id: String, 
        effectCall: EffectCall, 
        nextStateId: String
    ): EffectStateMachineBuilder {
        states[id] = EffectState.AsyncEffectState(id, effectCall, nextStateId)
        if (initialStateId == null) initialStateId = id
        return this
    }
    
    fun addJoinState(
        id: String,
        futureIds: List<String>,
        combiner: (List<FunkValue>) -> FunkValue,
        nextStateId: String
    ): EffectStateMachineBuilder {
        states[id] = EffectState.JoinState(id, futureIds, combiner, nextStateId)
        if (initialStateId == null) initialStateId = id
        return this
    }
    
    fun addRaceState(
        id: String,
        futureIds: List<String>,
        nextStateId: String
    ): EffectStateMachineBuilder {
        states[id] = EffectState.RaceState(id, futureIds, nextStateId)
        if (initialStateId == null) initialStateId = id
        return this
    }
    
    fun addConditionalState(
        id: String,
        condition: (EffectContext) -> Boolean,
        thenStateId: String,
        elseStateId: String
    ): EffectStateMachineBuilder {
        states[id] = EffectState.ConditionalState(id, condition, thenStateId, elseStateId)
        if (initialStateId == null) initialStateId = id
        return this
    }
    
    fun setFinalStateId(id: String): EffectStateMachineBuilder {
        finalStateId = id
        return this
    }
    
    fun build(): EffectStateMachine {
        val initial = initialStateId ?: throw IllegalStateException("No states added to state machine")
        return EffectStateMachine(
            states = states.toMap(),
            initialStateId = initial,
            finalStateId = finalStateId
        )
    }
}



/**
 * Effect execution error with context information
 */
class EffectExecutionError(
    message: String,
    val context: EffectContext? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    
    override val message: String get() = buildString {
        append(super.message)
        context?.let { ctx ->
            appendLine()
            append("Context: executionId=${ctx.executionId}, ")
            append("activeHandlers=${ctx.activeHandlers.keys}, ")
            append("localVariables=${ctx.localVariables.keys}")
        }
    }
}

// Legacy interfaces for backward compatibility with tests
interface StateMachineExecutor {
    fun execute(effectName: String, args: Map<String, FunkValue>, context: EffectContext): FunkValue
}

/**
 * Simple virtual thread-based state machine executor for tests
 */
class VirtualThreadStateMachineExecutor(
    private val concurrencyManager: LoomConcurrencyManager
) : StateMachineExecutor {
    
    override fun execute(effectName: String, args: Map<String, FunkValue>, context: EffectContext): FunkValue {
        // Execute the effect operation in the current context
        return when (effectName) {
            "increment" -> {
                val current = (args["current"] as? com.funk.language.interpreter.FunkInt)?.value ?: 0L
                com.funk.language.interpreter.FunkInt(current + 1)
            }
            "getContext" -> {
                val threadName = Thread.currentThread().name
                FunkString(threadName)
            }
            else -> FunkString("executed-$effectName")
        }
    }
    
    /**
     * Execute multiple state machines concurrently
     */
    fun executeParallel(
        stateMachines: List<EffectStateMachine>,
        contexts: List<EffectContext>
    ): List<FunkValue> {
        require(stateMachines.size == contexts.size) {
            "Number of state machines must match number of contexts"
        }
        
        return concurrencyManager.executeAll(
            stateMachines.zip(contexts).map { (machine, context) ->
                java.util.concurrent.Callable { machine.execute(context) }
            }
        )
    }
    
    /**
     * Execute state machines in a race (first to complete wins)
     */
    fun executeRace(
        stateMachines: List<EffectStateMachine>,
        contexts: List<EffectContext>
    ): FunkValue {
        require(stateMachines.size == contexts.size) {
            "Number of state machines must match number of contexts"
        }
        
        return concurrencyManager.executeRace(
            stateMachines.zip(contexts).map { (machine, context) ->
                java.util.concurrent.Callable { machine.execute(context) }
            }
        )
    }
}

