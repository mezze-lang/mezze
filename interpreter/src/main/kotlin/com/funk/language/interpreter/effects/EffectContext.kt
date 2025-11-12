package com.funk.language.interpreter.effects

import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkRecord
import com.funk.language.interpreter.concurrency.LoomConcurrencyManager
import com.funk.language.interpreter.concurrency.ExecutionContext
import com.funk.language.interpreter.concurrency.ConcurrencyScope
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Unified, immutable effect context for handler resolution and execution state
 * This replaces the complex handler resolution logic with a clean, predictable system
 */
data class EffectContext(
    val declaredEffects: Set<String>,
    val activeHandlers: Map<String, EffectHandler>,
    val handlerStack: List<Map<String, EffectHandler>>,
    val globalHandlers: Map<String, EffectHandler>,
    val localVariables: Map<String, FunkValue>,
    val suspensionPoints: Map<String, SuspensionPoint>,
    val virtualThreadFutures: Map<String, Future<FunkValue>>,
    val concurrencyManager: LoomConcurrencyManager,
    val concurrencyScope: ConcurrencyScope? = null,
    val executionContext: ExecutionContext? = null,
    val cancellationToken: CancellationToken = CancellationToken(),
    val executionId: String = UUID.randomUUID().toString()
) {
    
    /**
     * Add handlers to the stack (for perform expressions)
     */
    fun withHandlers(handlers: Map<String, EffectHandler>): EffectContext {
        return copy(handlerStack = handlerStack + handlers)
    }
    
    /**
     * Add a local variable
     */
    fun withVariable(name: String, value: FunkValue): EffectContext {
        return copy(localVariables = localVariables + (name to value))
    }
    
    /**
     * Add multiple variables
     */
    fun withVariables(variables: Map<String, FunkValue>): EffectContext {
        return copy(localVariables = localVariables + variables)
    }
    
    /**
     * Add a virtual thread future for async operations
     */
    fun withVirtualThreadFuture(id: String, future: Future<FunkValue>): EffectContext {
        return copy(virtualThreadFutures = virtualThreadFutures + (id to future))
    }
    
    /**
     * Add a concurrency scope for structured concurrency
     */
    fun withConcurrencyScope(scope: ConcurrencyScope): EffectContext {
        return copy(concurrencyScope = scope)
    }
    
    /**
     * Add execution context for virtual thread management
     */
    fun withExecutionContext(context: ExecutionContext): EffectContext {
        return copy(executionContext = context)
    }
    
    /**
     * Create a context with a new cancellation token
     */
    fun withCancellationToken(token: CancellationToken): EffectContext {
        return copy(cancellationToken = token)
    }
    
    /**
     * Cancel all operations in this context
     */
    fun cancel(reason: String = "Context cancelled") {
        cancellationToken.cancel(reason)
        concurrencyScope?.cancel()
        
        // Cancel all virtual thread futures
        virtualThreadFutures.values.forEach { future ->
            future.cancel(true)
        }
    }
    
    /**
     * Check if this context has been cancelled
     */
    fun isCancelled(): Boolean {
        return cancellationToken.isCancelled()
    }
    
    /**
     * Throw if cancelled
     */
    fun throwIfCancelled() {
        cancellationToken.throwIfCancelled()
    }
    
    /**
     * Resolve handler with clear precedence rules
     * 1. Handler stack (perform expressions) - highest priority
     * 2. Active handlers (current scope)
     * 3. Global handlers (fallback)
     */
    fun resolveHandler(effectName: String): EffectHandler? {
        return handlerStack.lastOrNull()?.get(effectName)
            ?: activeHandlers[effectName]
            ?: globalHandlers[effectName]
    }
    
    /**
     * Check if an effect is declared
     */
    fun isEffectDeclared(effectName: String): Boolean {
        return declaredEffects.contains(effectName)
    }
    
    /**
     * Check if a handler is available for an effect
     */
    fun hasHandler(effectName: String): Boolean {
        return resolveHandler(effectName) != null
    }
    
    /**
     * Get a local variable
     */
    fun getVariable(name: String): FunkValue? {
        return localVariables[name]
    }
    
    /**
     * Get a virtual thread future by ID
     */
    fun getVirtualThreadFuture(id: String): Future<FunkValue>? {
        return virtualThreadFutures[id]
    }
    

    
    /**
     * Suspend execution at a point with virtual thread support
     */
    fun withSuspensionPoint(suspensionPoint: SuspensionPoint): EffectContext {
        return copy(suspensionPoints = suspensionPoints + (suspensionPoint.id to suspensionPoint))
    }
    
    /**
     * Create a child context for spawning virtual threads
     */
    fun createChildContext(name: String, metadata: Map<String, Any> = emptyMap()): EffectContext {
        val childExecutionContext = if (executionContext != null) {
            // If parent has execution context, create child from it
            ExecutionContext(
                threadId = Thread.currentThread().threadId(),
                threadName = name,
                parentContext = executionContext,
                metadata = executionContext.metadata + metadata
            )
        } else {
            // If no parent context, create a new root execution context
            ExecutionContext(
                threadId = Thread.currentThread().threadId(),
                threadName = name,
                parentContext = null,
                metadata = metadata
            )
        }

        return copy(
            executionContext = childExecutionContext,
            executionId = UUID.randomUUID().toString()
        )
    }
    
    /**
     * Resume execution from a suspension point
     */
    fun resume(suspensionId: String, value: FunkValue): EffectContext {
        val suspensionPoint = suspensionPoints[suspensionId]
            ?: throw EffectExecutionError("Suspension point not found: $suspensionId", this)
        
        return suspensionPoint.resume(value, this)
    }
    
    /**
     * Get the final result (for state machine completion)
     */
    fun getResult(): FunkValue? {
        return localVariables["final_result"]
    }
    
    /**
     * Set the final result
     */
    fun withResult(result: FunkValue): EffectContext {
        return withVariable("final_result", result)
    }
    
    /**
     * Get available effects for error reporting
     */
    fun getAvailableEffects(): Set<String> {
        return declaredEffects
    }
    
    /**
     * Get available handlers for error reporting
     */
    fun getAvailableHandlers(): Set<String> {
        val allHandlers = mutableSetOf<String>()
        allHandlers.addAll(globalHandlers.keys)
        allHandlers.addAll(activeHandlers.keys)
        handlerStack.forEach { handlers ->
            allHandlers.addAll(handlers.keys)
        }
        return allHandlers
    }
    
    /**
     * Create a debug representation
     */
    fun toDebugString(): String = buildString {
        appendLine("EffectContext(id=$executionId)")
        appendLine("  Declared Effects: $declaredEffects")
        appendLine("  Active Handlers: ${activeHandlers.keys}")
        appendLine("  Handler Stack Depth: ${handlerStack.size}")
        appendLine("  Local Variables: ${localVariables.keys}")
        appendLine("  Suspension Points: ${suspensionPoints.keys}")
        appendLine("  Virtual Thread Futures: ${virtualThreadFutures.keys}")
        appendLine("  Concurrency Scope: ${concurrencyScope?.name}")
        appendLine("  Execution Context: ${executionContext?.threadName}")
        appendLine("  Cancelled: ${cancellationToken.isCancelled()}")
    }
    
    companion object {
        /**
         * Create an empty effect context
         */
        fun empty(): EffectContext {
            return EffectContext(
                declaredEffects = emptySet(),
                activeHandlers = emptyMap(),
                handlerStack = emptyList(),
                globalHandlers = emptyMap(),
                localVariables = emptyMap(),
                suspensionPoints = emptyMap(),
                virtualThreadFutures = emptyMap(),
                concurrencyManager = LoomConcurrencyManager(),
                cancellationToken = CancellationToken()
            )
        }
        
        /**
         * Create a context with basic setup and virtual thread support
         */
        fun create(
            declaredEffects: Set<String> = emptySet(),
            activeHandlers: Map<String, EffectHandler> = emptyMap(),
            globalHandlers: Map<String, EffectHandler> = emptyMap(),
            concurrencyManager: LoomConcurrencyManager = LoomConcurrencyManager()
        ): EffectContext {
            return EffectContext(
                declaredEffects = declaredEffects,
                activeHandlers = activeHandlers,
                handlerStack = emptyList(),
                globalHandlers = globalHandlers,
                localVariables = emptyMap(),
                suspensionPoints = emptyMap(),
                virtualThreadFutures = emptyMap(),
                concurrencyManager = concurrencyManager,
                cancellationToken = CancellationToken()
            )
        }
    }
}

/**
 * Represents an effect call with all necessary information
 */
data class EffectCall(
    val effectName: String,
    val operation: String,
    val arguments: FunkValue,
    val context: EffectContext,
    val callId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Interface for effect handlers - supports both sync and async operations
 */
interface EffectHandler {
    val effectName: String
    val operations: Map<String, EffectOperation>
    
    /**
     * Handle an effect operation asynchronously (legacy support)
     */
    suspend fun handle(operation: String, arguments: FunkValue, context: EffectContext): FunkValue {
        return handleSync(operation, arguments, context)
    }
    
    /**
     * Handle an effect operation synchronously in virtual thread context
     */
    fun handleSync(operation: String, arguments: FunkValue, context: EffectContext): FunkValue
    
    /**
     * Check if this handler supports an operation
     */
    fun supportsOperation(operation: String): Boolean
    
    /**
     * Get all available operations
     */
    fun getAvailableOperations(): Set<String>
}

/**
 * Interface for individual effect operations
 */
interface EffectOperation {
    val name: String
    
    /**
     * Execute the operation asynchronously (legacy support)
     */
    suspend fun execute(arguments: FunkValue, context: EffectContext): FunkValue {
        return executeSync(arguments, context)
    }
    
    /**
     * Execute the operation synchronously in virtual thread context
     */
    fun executeSync(arguments: FunkValue, context: EffectContext): FunkValue
}

/**
 * Simple implementation of an effect handler with virtual thread support
 */
class SimpleEffectHandler(
    override val effectName: String,
    override val operations: Map<String, EffectOperation>
) : EffectHandler {
    
    override fun handleSync(operation: String, arguments: FunkValue, context: EffectContext): FunkValue {
        val effectOperation = operations[operation]
            ?: throw EffectExecutionError("Operation '$operation' not found in effect '$effectName'", context)
        
        return effectOperation.executeSync(arguments, context)
    }
    
    override fun supportsOperation(operation: String): Boolean {
        return operation in operations
    }
    
    override fun getAvailableOperations(): Set<String> {
        return operations.keys
    }
}

/**
 * Virtual thread effect operation implementation
 */
class VirtualThreadEffectOperation(
    override val name: String,
    private val testImplementation: (Map<String, FunkValue>, EffectContext) -> FunkValue
) : EffectOperation {
    
    override fun executeSync(arguments: FunkValue, context: EffectContext): FunkValue {
        // Convert single FunkValue to Map for test compatibility
        val argsMap = when (arguments) {
            is FunkRecord -> arguments.fields
            else -> mapOf("value" to arguments)
        }
        return testImplementation(argsMap, context)
    }
}

/**
 * Async effect operation implementation (legacy support)
 */
class AsyncEffectOperation(
    override val name: String,
    private val implementation: suspend (FunkValue, EffectContext) -> FunkValue
) : EffectOperation {
    
    override suspend fun execute(arguments: FunkValue, context: EffectContext): FunkValue {
        return implementation(arguments, context)
    }
    
    override fun executeSync(arguments: FunkValue, context: EffectContext): FunkValue {
        // For legacy async operations, we need to run them in a blocking manner
        // This is a simplified approach - in practice you might want to use
        // CompletableFuture or similar to bridge async to sync
        throw UnsupportedOperationException(
            "Legacy async operation '$name' cannot be executed synchronously. " +
            "Please migrate to VirtualThreadEffectOperation."
        )
    }
}

/**
 * Suspension point for abort/resume functionality
 */
data class SuspensionPoint(
    val id: String,
    val stateMachineId: String,
    val currentStateId: String,
    val context: EffectContext,
    val continuationCallback: (FunkValue, EffectContext) -> EffectContext,
    val abortCallback: (Throwable, EffectContext) -> EffectContext,
    val timestamp: Long = System.currentTimeMillis()
) {
    
    /**
     * Resume execution with a value
     */
    fun resume(value: FunkValue, context: EffectContext): EffectContext {
        return continuationCallback(value, context)
    }
    
    /**
     * Abort execution with an error
     */
    fun abort(error: Throwable, context: EffectContext): EffectContext {
        return abortCallback(error, context)
    }
}

/**
 * Registry for managing effect declarations and handlers
 */
class EffectRegistry {
    private val declaredEffects = ConcurrentHashMap<String, Set<String>>()
    private val handlers = ConcurrentHashMap<String, EffectHandler>()
    private val handlerFactories = ConcurrentHashMap<String, () -> EffectHandler>()
    
    /**
     * Declare an effect with its operations
     */
    fun declareEffect(name: String, operations: Set<String>) {
        declaredEffects[name] = operations
    }
    
    /**
     * Register a handler for an effect
     */
    fun registerHandler(effectName: String, handler: EffectHandler) {
        handlers[effectName] = handler
    }
    
    /**
     * Register a handler factory for lazy initialization
     */
    fun registerHandlerFactory(effectName: String, factory: () -> EffectHandler) {
        handlerFactories[effectName] = factory
    }
    
    /**
     * Check if an effect is declared
     */
    fun isEffectDeclared(effectName: String): Boolean {
        return declaredEffects.containsKey(effectName)
    }
    
    /**
     * Check if a handler is available
     */
    fun hasHandler(effectName: String): Boolean {
        return handlers.containsKey(effectName) || handlerFactories.containsKey(effectName)
    }
    
    /**
     * Get a handler, creating it if necessary
     */
    fun getHandler(effectName: String): EffectHandler? {
        return handlers[effectName] ?: handlerFactories[effectName]?.invoke()?.also {
            handlers[effectName] = it
        }
    }
    
    /**
     * Get all declared effects
     */
    fun getAvailableEffects(): Set<String> {
        return declaredEffects.keys
    }
    
    /**
     * Get operations for an effect
     */
    fun getAvailableOperations(effectName: String): Set<String> {
        return declaredEffects[effectName] ?: emptySet()
    }
    
    /**
     * Clear all registrations (for testing)
     */
    fun clear() {
        declaredEffects.clear()
        handlers.clear()
        handlerFactories.clear()
    }
}

/**
 * Cancellation token for structured concurrency support
 */
class CancellationToken {
    private val cancelled = AtomicBoolean(false)
    private val cancellationReason = AtomicReference<String>()
    private val cancellationCallbacks = ConcurrentHashMap<String, () -> Unit>()
    
    /**
     * Cancel this token with a reason
     */
    fun cancel(reason: String = "Operation cancelled") {
        if (cancelled.compareAndSet(false, true)) {
            cancellationReason.set(reason)
            
            // Execute all cancellation callbacks
            cancellationCallbacks.values.forEach { callback ->
                try {
                    callback()
                } catch (e: Exception) {
                    // Log but don't propagate callback errors
                    System.err.println("Error in cancellation callback: ${e.message}")
                }
            }
            cancellationCallbacks.clear()
        }
    }
    
    /**
     * Check if this token is cancelled
     */
    fun isCancelled(): Boolean = cancelled.get()
    
    /**
     * Get the cancellation reason
     */
    fun getCancellationReason(): String? = cancellationReason.get()
    
    /**
     * Throw if cancelled
     */
    fun throwIfCancelled() {
        if (isCancelled()) {
            throw EffectCancellationException(getCancellationReason() ?: "Operation cancelled")
        }
    }
    
    /**
     * Register a callback to be executed when cancelled
     */
    fun onCancellation(id: String, callback: () -> Unit) {
        if (isCancelled()) {
            callback()
        } else {
            cancellationCallbacks[id] = callback
        }
    }
    
    /**
     * Remove a cancellation callback
     */
    fun removeCancellationCallback(id: String) {
        cancellationCallbacks.remove(id)
    }
    
    /**
     * Create a child token that is cancelled when this token is cancelled
     */
    fun createChild(): CancellationToken {
        val child = CancellationToken()
        
        if (isCancelled()) {
            child.cancel(getCancellationReason() ?: "Parent cancelled")
        } else {
            onCancellation("child-${child.hashCode()}") {
                child.cancel(getCancellationReason() ?: "Parent cancelled")
            }
        }
        
        return child
    }
}

/**
 * Exception thrown when an operation is cancelled
 */
class EffectCancellationException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)