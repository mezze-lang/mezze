package com.funk.language.interpreter

import com.funk.language.ast.*

/**
 * Centralized effect handling system for the Funk interpreter.
 * This class manages effect resolution, handler propagation, and effect call execution.
 */
class EffectHandler {
    // Global effect registry - effects are program-wide declarations
    private val globalEffects = mutableMapOf<String, FunkRecord>()

    // Handler stack - maintains active handlers for nested perform expressions
    private val handlerStack = mutableListOf<FunkRecord>()

    // Performer to effect mapping - tracks which performers implement which effects
    // e.g., "Console" -> "Log", "ToResult" -> "Exception"
    private val performerToEffect = mutableMapOf<String, String>()
    
    /**
     * Register a global effect declaration
     */
    fun registerEffect(name: String, effectRecord: FunkRecord) {
        globalEffects[name] = effectRecord
    }

    /**
     * Register that a performer implements an effect
     * e.g., registerPerformer("Console", "Log") means Console implements Log
     */
    fun registerPerformer(performerName: String, effectName: String) {
        performerToEffect[performerName] = effectName
    }

    /**
     * Get the effect that a performer implements
     */
    fun getEffectForPerformer(performerName: String): String? {
        return performerToEffect[performerName]
    }

    /**
     * Get all performers that implement a given effect
     * e.g., getPerformersForEffect("Log") returns ["Console"]
     */
    fun getPerformersForEffect(effectName: String): List<String> {
        return performerToEffect.entries
            .filter { it.value == effectName }
            .map { it.key }
    }
    
    /**
     * Check if an effect is declared globally
     */
    fun isEffectDeclared(name: String): Boolean {
        return globalEffects.containsKey(name)
    }
    
    /**
     * Get all global effects (for function context setup)
     */
    fun getGlobalEffects(): Map<String, FunkRecord> {
        return globalEffects.toMap()
    }
    
    /**
     * Push handlers onto the stack when entering a perform expression
     */
    fun pushHandlers(handlers: FunkRecord) {
        handlerStack.add(handlers)
    }
    
    /**
     * Pop handlers from the stack when exiting a perform expression
     */
    fun popHandlers() {
        if (handlerStack.isNotEmpty()) {
            handlerStack.removeAt(handlerStack.size - 1)
        }
    }
    
    /**
     * Get the current active handlers
     */
    fun getCurrentHandlers(): FunkRecord? {
        return handlerStack.lastOrNull()
    }
    
    /**
     * Execute an effect call by finding the appropriate handler.
     *
     * LAZY EFFECTS MODEL:
     * - If handler available → Execute immediately, return result
     * - If no handler → Return FunkSuspended with deferred computation (thunk)
     *
     * This implements the algebraic effects semantics where:
     * - Inside perform: effects execute immediately (hot path, fast!)
     * - Outside perform: effects return suspended computations (cold path, lazy!)
     *
     * Now uses FunkTruffleFunction exclusively (100% Truffle execution)
     */
    fun executeEffectCall(
        effectName: String,
        operationName: String,
        arguments: FunkValue,
        context: MutableMap<String, FunkValue>
    ): FunkValue {
        // COMPREHENSIVE HANDLER RESOLUTION
        // CRITICAL FIX: Prioritize handler stack over context
        // The handler stack is the source of truth for currently active handlers
        // Context["__handlers"] can be stale when executing inside state machines
        // 1. Check handler stack first (for nested perform expressions)
        // 2. Fall back to context (for captured handlers in closures)
        // 3. If no handlers available, return FunkSuspended with deferred computation
        val handlersRecord = handlerStack.lastOrNull()
            ?: (context["__handlers"] as? FunkRecord)

        // No handlers available - create deferred computation (LAZY EFFECTS)
        if (handlersRecord == null) {
            return createSuspendedEffectCall(effectName, operationName, arguments, context)
        }

        val handlers = handlersRecord.fields

        // SMART HANDLER RESOLUTION:
        // 1. Try direct lookup (e.g., looking for "Log" when "Log" is in handlers)
        var handler = handlers[effectName]

        // 2. If not found, look for performers that implement this effect
        //    (e.g., looking for "Log", find "Console" which implements Log)
        if (handler == null) {
            val performers = getPerformersForEffect(effectName)
            for (performerName in performers) {
                handler = handlers[performerName]
                if (handler != null) {
                    break
                }
            }
        }

        // Effect declared but no handler - create deferred computation
        if (handler == null) {
            return createSuspendedEffectCall(effectName, operationName, arguments, context)
        }

        if (handler !is FunkRecord) {
            throw RuntimeException("Handler for $effectName is not a record: $handler")
        }

        val operation = handler.fields[operationName]
            ?: throw RuntimeException("Operation $operationName not found in handler for $effectName")

        // Handler available - EXECUTE IMMEDIATELY (HOT PATH)
        val result = when (operation) {
            is NativeFunkFunction -> {
                // Native functions expect the FunkRecord as args[0]
                if (arguments !is FunkRecord) {
                    throw RuntimeException("Native function expects record arguments, got: ${arguments::class.simpleName}")
                }
                // Pass the record as a single argument
                operation.call(listOf(arguments))
            }
            is FunkTruffleFunction -> {
                // Truffle functions are called directly via their CallTarget
                // CRITICAL: FunkFunctionRootNode expects 4 arguments:
                // [0] = FunkRecord of parameters
                // [1] = Map<String, FunkValue> of closure variables (legacy, for backward compat)
                // [2] = MaterializedFrame (lexical scope)
                // [3] = Map<String, Int> lexical scope slot mapping
                operation.callTarget.call(
                    arguments,
                    operation.closureVariables,
                    operation.lexicalScope,
                    operation.lexicalScopeSlotMap
                ) as FunkValue
            }
            else -> {
                throw RuntimeException(
                    "Operation $operationName in handler for $effectName must be a Truffle function or native function. " +
                    "Got: ${operation::class.simpleName}"
                )
            }
        }

        return result
    }

    /**
     * Create a suspended effect call when no handler is available.
     * This implements the lazy effects model.
     *
     * Creates a deferred effect computation wrapped in FunkSuspended.
     * The computation will execute later when handlers become available via perform.
     */
    private fun createSuspendedEffectCall(
        effectName: String,
        operationName: String,
        arguments: FunkValue,
        context: MutableMap<String, FunkValue>
    ): FunkSuspended {
        // Verify the effect and operation exist
        val effect = globalEffects[effectName]
            ?: throw RuntimeException("Unknown effect: $effectName")

        // Verify the operation exists in the effect declaration
        if (!effect.fields.containsKey(operationName)) {
            throw RuntimeException("Operation $operationName not found in effect $effectName")
        }

        // Create a deferred computation function that will resolve the handler when executed
        // This function will be called later when handlers become available
        val deferredFunction = NativeFunkFunction("deferred-$effectName-$operationName") { args ->
            // This will be executed later when handlers are available
            // At that time, we need to look up the handler and execute the operation
            // For now, throw an error - this should be handled by FunkSuspended.executeWith
            throw RuntimeException("Deferred effect call not yet implemented for $effectName.$operationName")
        }

        // Create a thunk with the deferred function
        val thunk = FunkThunk(
            function = deferredFunction,
            arguments = arguments
        )

        // Wrap in FunkSuspended with unhandled effects
        return FunkSuspended(
            computation = thunk,
            unhandledEffects = setOf(effectName),
            capturedHandlers = emptyMap()
        )
    }
    
    /**
     * Setup function context with proper effect infrastructure
     * This is THE CRITICAL method that determines if effects work in functions
     */
    fun setupFunctionContext(
        functionContext: MutableMap<String, FunkValue>,
        executionContext: MutableMap<String, FunkValue>
    ) {
        // STEP 1: Get the current active handlers (from perform expressions)
        val activeHandlers = getCurrentHandlers()
        if (activeHandlers != null) {
            // CRITICAL: Functions MUST inherit active handlers
            functionContext["__handlers"] = activeHandlers
        } else {
            // Fallback: copy handlers from execution context
            executionContext["__handlers"]?.let { 
                functionContext["__handlers"] = it 
            }
        }
        
        // STEP 2: Add global effects for effect detection
        // But DON'T override handlers - effects are just for detection
        for ((effectName, effectRecord) in globalEffects) {
            // Only add if not already a handler (handlers take precedence)
            if (activeHandlers?.fields?.containsKey(effectName) != true) {
                functionContext[effectName] = effectRecord
            }
        }
        
        // STEP 3: Copy implementation mappings
        executionContext["__impl_mappings"]?.let { 
            functionContext["__impl_mappings"] = it 
        }
    }
    
    /**
     * Check if a member access should be converted to an effect call
     * CRITICAL: This determines whether Log.info becomes an effect call vs member access
     */
    fun shouldConvertToEffectCall(
        effectName: String, 
        context: MutableMap<String, FunkValue>
    ): Boolean {
        // PRIORITY 1: Check if we have active handlers for this effect
        val handlersRecord = (context["__handlers"] as? FunkRecord) ?: getCurrentHandlers()
        if (handlersRecord?.fields?.containsKey(effectName) == true) {
            return true // Handler available - definitely an effect call
        }
        
        // PRIORITY 2: Check if it's a declared effect (even without handler)
        return isEffectDeclared(effectName) || 
               (context.containsKey(effectName) && context[effectName] is FunkRecord)
    }
    
    /**
     * Find the effect that a performer implements
     */
    fun findEffectForPerformer(performerName: String, context: MutableMap<String, FunkValue>): String {
        val implMappings = context["__impl_mappings"] as? FunkRecord
        val effectNameValue = implMappings?.fields?.get(performerName) as? FunkString
        
        
        return effectNameValue?.value ?: performerName // Fallback to performer name
    }
    
    /**
     * Clear all state (for testing)
     */
    fun clear() {
        globalEffects.clear()
        handlerStack.clear()
        performerToEffect.clear()
    }
}
