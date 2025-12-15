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
    
    /**
     * Register a global effect declaration
     */
    fun registerEffect(name: String, effectRecord: FunkRecord) {
        globalEffects[name] = effectRecord
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
     * Execute an effect call by finding the appropriate handler
     */
    fun executeEffectCall(
        effectName: String, 
        operationName: String, 
        arguments: FunkValue,
        context: MutableMap<String, FunkValue>,
        interpreter: FunkInterpreter
    ): FunkValue {
        // COMPREHENSIVE HANDLER RESOLUTION
        // 1. Check context first (for nested handlers)
        // 2. Check handler stack (for perform expressions)
        // 3. Fail with detailed error message
        val handlersRecord = (context["__handlers"] as? FunkRecord)
            ?: handlerStack.lastOrNull()
            ?: throw RuntimeException(
                "No handlers available for effect call: $effectName.$operationName. " +
                "Available context keys: ${context.keys}, Handler stack size: ${handlerStack.size}"
            )
        
        val handlers = handlersRecord.fields
        val handler = handlers[effectName]
            ?: throw RuntimeException("No handler found for effect: $effectName")
        
        if (handler !is FunkRecord) {
            throw RuntimeException("Handler for $effectName is not a record: $handler")
        }
        
        val operation = handler.fields[operationName]
            ?: throw RuntimeException("Operation $operationName not found in handler for $effectName")
        
        if (operation !is SimpleFunkFunction && operation !is NativeFunkFunction) {
            throw RuntimeException("Operation $operationName in handler for $effectName is not a function: $operation")
        }
        
        // Execute the handler operation
        return when (operation) {
            is NativeFunkFunction -> {
                // Native functions handle their own argument processing
                val argsMap = when (arguments) {
                    is FunkRecord -> arguments.fields
                    else -> throw RuntimeException("Native function expects record arguments, got: ${arguments::class.simpleName}")
                }
                operation.call(argsMap.values.toList())
            }
            is SimpleFunkFunction -> {
                // Regular Funk functions use the interpreter's call mechanism
                // CRITICAL: Ensure handler operations have access to current handlers AND Delimited
                val handlerContext = context.toMutableMap()

                // Make sure handler operations can access the same handlers that are currently active
                val activeHandlers = (context["__handlers"] as? FunkRecord) ?: getCurrentHandlers()
                if (activeHandlers != null) {
                    // Inject Delimited from the function's closure if available
                    val handlersWithDelimited = activeHandlers.fields.toMutableMap()
                    if (!handlersWithDelimited.containsKey("Continuation") && operation.closure.containsKey("Delimited")) {
                        // Handler's closure has Delimited - make it available as Continuation handler
                        handlersWithDelimited["Continuation"] = operation.closure["Delimited"]!!
                    }
                    handlerContext["__handlers"] = FunkRecord(handlersWithDelimited)
                }

                // CRITICAL: Also make sure Delimited is directly available in context
                // Check BOTH closure and execution context for Delimited
                val delimited = operation.closure["Delimited"] ?: context["Delimited"]
                if (delimited != null) {
                    handlerContext["Delimited"] = delimited
                }

                if (arguments is FunkRecord) {
                    interpreter.callFunctionWithRecord(operation, arguments, handlerContext)
                } else {
                    interpreter.callFunction(operation, listOf(arguments), handlerContext)
                }
            }
            else -> throw RuntimeException("Unknown function type: ${operation::class.simpleName}")
        }
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
    }
}
