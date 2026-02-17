package com.mezze.language.interpreter

import com.mezze.language.ast.*

/**
 * Centralized effect handling system for the Mezze interpreter.
 * This class manages effect resolution, handler propagation, and effect call execution.
 */
class EffectHandler {
    // Global effect registry - effects are program-wide declarations
    // Thread-safe: ConcurrentHashMap for concurrent access from virtual threads
    private val globalEffects = java.util.concurrent.ConcurrentHashMap<String, MezzeRecord>()

    // Handler stack - maintains active handlers for nested perform expressions
    // CRITICAL: Must be thread-local to prevent race conditions between virtual threads
    // Each thread gets its own handler stack for proper effect scoping
    private val handlerStack = ThreadLocal.withInitial { mutableListOf<MezzeRecord>() }

    // Performer to effect mapping - tracks which performers implement which effects
    // e.g., "Console" -> "Log", "ToResult" -> "Exception"
    // Thread-safe: ConcurrentHashMap for concurrent access
    private val performerToEffect = java.util.concurrent.ConcurrentHashMap<String, String>()
    
    /**
     * Register a global effect declaration
     */
    fun registerEffect(name: String, effectRecord: MezzeRecord) {
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
    fun getGlobalEffects(): Map<String, MezzeRecord> {
        return globalEffects.toMap()
    }
    
    /**
     * Push handlers onto the stack when entering a perform expression
     */
    fun pushHandlers(handlers: MezzeRecord) {
        handlerStack.get().add(handlers)
    }

    /**
     * Pop handlers from the stack when exiting a perform expression
     */
    fun popHandlers() {
        val stack = handlerStack.get()
        if (stack.isNotEmpty()) {
            stack.removeAt(stack.size - 1)
        }
    }

    /**
     * Get the current active handlers
     */
    fun getCurrentHandlers(): MezzeRecord? {
        return handlerStack.get().lastOrNull()
    }
    
    /**
     * Execute an effect call by finding the appropriate handler.
     *
     * LAZY EFFECTS MODEL:
     * - If handler available → Execute immediately, return result
     * - If no handler → Return MezzeSuspended with deferred computation (thunk)
     *
     * This implements the algebraic effects semantics where:
     * - Inside perform: effects execute immediately (hot path, fast!)
     * - Outside perform: effects return suspended computations (cold path, lazy!)
     *
     * Now uses MezzeTruffleFunction exclusively (100% Truffle execution)
     */
    fun executeEffectCall(
        effectName: String,
        operationName: String,
        arguments: MezzeValue,
        context: MutableMap<String, MezzeValue>,
        interpreter: MezzeInterpreter  // Kept for backward compatibility, but not used
    ): MezzeValue {
        // COMPREHENSIVE HANDLER RESOLUTION
        // CRITICAL FIX: Prioritize handler stack over context
        // The handler stack is the source of truth for currently active handlers
        // Context["__handlers"] can be stale when executing inside state machines
        // 1. Check handler stack first (for nested perform expressions)
        // 2. Fall back to context (for captured handlers in closures)
        // 3. If no handlers available, return MezzeSuspended with deferred computation
        val handlersRecord = handlerStack.get().lastOrNull()
            ?: (context["__handlers"] as? MezzeRecord)

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

        if (handler !is MezzeRecord) {
            throw RuntimeException("Handler for $effectName is not a record: $handler")
        }

        val operation = handler.fields[operationName]
            ?: throw RuntimeException("Operation $operationName not found in handler for $effectName")

        // Handler available - EXECUTE IMMEDIATELY (HOT PATH)
        val result = when (operation) {
            is NativeMezzeFunction -> {
                // Native functions expect the MezzeRecord as args[0]
                if (arguments !is MezzeRecord) {
                    throw RuntimeException("Native function expects record arguments, got: ${arguments::class.simpleName}")
                }
                // Pass the record as a single argument
                operation.call(listOf(arguments))
            }
            is MezzeTruffleFunction -> {
                // Truffle functions are called directly via their CallTarget
                // CRITICAL: MezzeFunctionRootNode expects 4 arguments:
                // [0] = MezzeRecord of parameters
                // [1] = Map<String, MezzeValue> of closure variables (legacy, for backward compat)
                // [2] = MaterializedFrame (lexical scope)
                // [3] = Map<String, Int> lexical scope slot mapping
                operation.callTarget.call(
                    arguments,
                    operation.closureVariables,
                    operation.lexicalScope,
                    operation.lexicalScopeSlotMap
                ) as MezzeValue
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
     * Creates a deferred effect computation wrapped in MezzeSuspended.
     * The computation will execute later when handlers become available via perform.
     */
    private fun createSuspendedEffectCall(
        effectName: String,
        operationName: String,
        arguments: MezzeValue,
        context: MutableMap<String, MezzeValue>
    ): MezzeSuspended {
        // Verify the effect and operation exist
        val effect = globalEffects[effectName]
            ?: throw RuntimeException("Unknown effect: $effectName")

        // Verify the operation exists in the effect declaration
        if (!effect.fields.containsKey(operationName)) {
            throw RuntimeException("Operation $operationName not found in effect $effectName")
        }

        // Create a deferred computation function that will resolve the handler when executed
        // This function will be called later when handlers become available via MezzeSuspended.executeWith
        val deferredFunction = NativeMezzeFunction("deferred-$effectName-$operationName") { args ->
            // At execution time, handlers will be available on the handler stack (pushed by MezzeSuspended.executeWith)
            // Retrieve the handler from the stack and execute the operation
            val handlersRecord = getCurrentHandlers()
                ?: throw RuntimeException("No handlers available for deferred effect call $effectName.$operationName")

            val handlers = handlersRecord.fields

            // Smart handler resolution (same logic as executeEffectCall)
            var handler = handlers[effectName]

            // If not found, look for performers that implement this effect
            if (handler == null) {
                val performers = getPerformersForEffect(effectName)
                for (performerName in performers) {
                    handler = handlers[performerName]
                    if (handler != null) {
                        break
                    }
                }
            }

            if (handler == null) {
                throw RuntimeException("No handler found for effect $effectName in deferred call")
            }

            if (handler !is MezzeRecord) {
                throw RuntimeException("Handler for $effectName is not a record: $handler")
            }

            val operation = handler.fields[operationName]
                ?: throw RuntimeException("Operation $operationName not found in handler for $effectName")

            // Execute the operation with the captured arguments
            val operationArgs = args.firstOrNull() ?: arguments
            when (operation) {
                is NativeMezzeFunction -> {
                    if (operationArgs !is MezzeRecord) {
                        throw RuntimeException("Native function expects record arguments, got: ${operationArgs::class.simpleName}")
                    }
                    operation.call(listOf(operationArgs))
                }
                is MezzeTruffleFunction -> {
                    operation.callTarget.call(
                        operationArgs,
                        operation.closureVariables,
                        operation.lexicalScope,
                        operation.lexicalScopeSlotMap
                    ) as MezzeValue
                }
                else -> {
                    throw RuntimeException(
                        "Operation $operationName in handler for $effectName must be a Truffle function or native function. " +
                        "Got: ${operation::class.simpleName}"
                    )
                }
            }
        }

        // Create a thunk with the deferred function
        val thunk = MezzeThunk(
            function = deferredFunction,
            arguments = arguments
        )

        // Wrap in MezzeSuspended with unhandled effects
        return MezzeSuspended(
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
        functionContext: MutableMap<String, MezzeValue>,
        executionContext: MutableMap<String, MezzeValue>
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
        context: MutableMap<String, MezzeValue>
    ): Boolean {
        // PRIORITY 1: Check if we have active handlers for this effect
        val handlersRecord = (context["__handlers"] as? MezzeRecord) ?: getCurrentHandlers()
        if (handlersRecord?.fields?.containsKey(effectName) == true) {
            return true // Handler available - definitely an effect call
        }
        
        // PRIORITY 2: Check if it's a declared effect (even without handler)
        return isEffectDeclared(effectName) || 
               (context.containsKey(effectName) && context[effectName] is MezzeRecord)
    }
    
    /**
     * Find the effect that a performer implements
     */
    fun findEffectForPerformer(performerName: String, context: MutableMap<String, MezzeValue>): String {
        val implMappings = context["__impl_mappings"] as? MezzeRecord
        val effectNameValue = implMappings?.fields?.get(performerName) as? MezzeString
        
        
        return effectNameValue?.value ?: performerName // Fallback to performer name
    }
    
    /**
     * Clear all state (for testing)
     */
    fun clear() {
        globalEffects.clear()
        handlerStack.get().clear()
        performerToEffect.clear()
    }
}
