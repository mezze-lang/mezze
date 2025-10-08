# Effect Handler Implementation

## Overview

This document details the implementation of Funk's algebraic effect system, including effect handlers, effect registration, and the integration with Truffle and virtual threads. The effect system provides composable, type-safe side effects while maintaining functional programming principles.

## Core Architecture

### 1. Effect Handler Registry

```kotlin
/**
 * Central registry for all effect handlers in the system
 */
class EffectHandlerRegistry {
    private val syncHandlers = mutableListOf<SyncEffectHandler>()
    private val asyncHandlers = mutableListOf<AsyncEffectHandler>()
    private val handlerStack = mutableListOf<Map<String, FunkRecord>>()
    private val effectDeclarations = mutableMapOf<String, FunkRecord>()

    /**
     * Register a synchronous effect handler
     */
    fun registerSync(handler: SyncEffectHandler) {
        syncHandlers.add(handler)
        syncHandlers.sortByDescending { it.priority() }
    }

    /**
     * Register an asynchronous effect handler
     */
    fun registerAsync(handler: AsyncEffectHandler) {
        asyncHandlers.add(handler)
        asyncHandlers.sortByDescending { it.priority() }
    }

    /**
     * Register an effect declaration (from effect declarations in Funk code)
     */
    fun registerEffect(effectName: String, effectRecord: FunkRecord) {
        effectDeclarations[effectName] = effectRecord
    }

    /**
     * Check if an effect is declared
     */
    fun isEffectDeclared(effectName: String): Boolean {
        return effectDeclarations.containsKey(effectName)
    }

    /**
     * Push handlers onto the stack (for perform expressions)
     */
    fun pushHandlers(handlers: FunkRecord) {
        handlerStack.add(handlers.fields)
    }

    /**
     * Pop handlers from the stack
     */
    fun popHandlers() {
        if (handlerStack.isNotEmpty()) {
            handlerStack.removeAt(handlerStack.size - 1)
        }
    }

    /**
     * Get current active handlers
     */
    fun getCurrentHandlers(): FunkRecord? {
        return if (handlerStack.isNotEmpty()) {
            FunkRecord(handlerStack.last())
        } else {
            null
        }
    }

    /**
     * Execute an effect operation
     */
    fun executeEffect(
        effectName: String,
        operation: String,
        arguments: FunkValue,
        context: MutableMap<String, FunkValue>,
        interpreter: FunkInterpreter
    ): FunkValue {
        // Check for active handlers first (from perform expressions)
        val activeHandlers = getCurrentHandlers()
        if (activeHandlers?.fields?.containsKey(effectName) == true) {
            val handler = activeHandlers.fields[effectName] as FunkRecord
            val operationFunction = handler.fields[operation]
                ?: throw RuntimeException("Operation '$operation' not found in handler for effect '$effectName'")
            
            return when (operationFunction) {
                is SimpleFunkFunction -> {
                    interpreter.callFunctionWithRecord(operationFunction, arguments as FunkRecord, context)
                }
                is NativeFunkFunction -> {
                    operationFunction.call(listOf(arguments))
                }
                else -> throw RuntimeException("Invalid handler operation type")
            }
        }

        // Try async handlers
        val asyncHandler = findAsyncHandler(effectName)
        if (asyncHandler != null) {
            return VirtualThreadExecutor.submitSuspending {
                asyncHandler.handle(operation, arguments)
            }.get() // Block until completion
        }

        // Try sync handlers
        val syncHandler = findSyncHandler(effectName)
        if (syncHandler != null) {
            return syncHandler.handle(operation, arguments)
        }

        throw RuntimeException("No handler found for effect: $effectName")
    }

    private fun findAsyncHandler(effectName: String): AsyncEffectHandler? {
        return asyncHandlers.find { it.canHandle(effectName) }
    }

    private fun findSyncHandler(effectName: String): SyncEffectHandler? {
        return syncHandlers.find { it.canHandle(effectName) }
    }
}
```

### 2. Effect Handler Interfaces

```kotlin
/**
 * Base interface for synchronous effect handlers
 */
interface SyncEffectHandler {
    /**
     * Handle an effect operation synchronously
     */
    fun handle(operation: String, arguments: FunkValue): FunkValue

    /**
     * Check if this handler can handle the given effect
     */
    fun canHandle(effectName: String): Boolean

    /**
     * Priority for handler selection (higher = more priority)
     */
    fun priority(): Int = 0
}

/**
 * Base interface for asynchronous effect handlers
 */
interface AsyncEffectHandler {
    /**
     * Handle an effect operation asynchronously
     */
    suspend fun handle(operation: String, arguments: FunkValue): FunkValue

    /**
     * Check if this handler can handle the given effect
     */
    fun canHandle(effectName: String): Boolean

    /**
     * Priority for handler selection (higher = more priority)
     */
    fun priority(): Int = 0

    /**
     * Whether this handler supports cancellation
     */
    fun supportsCancellation(): Boolean = false

    /**
     * Cancel ongoing operations
     */
    fun cancel(operationId: String): Boolean = false
}
```

### 3. Built-in Effect Handlers

#### Console Effect Handler

```kotlin
class ConsoleEffectHandler : SyncEffectHandler {
    
    override fun handle(operation: String, arguments: FunkValue): FunkValue {
        return when (operation) {
            "print" -> {
                val args = arguments as FunkRecord
                val message = (args.fields["message"] as FunkString).value
                println(message)
                FunkRecord(emptyMap()) // Unit type
            }
            
            "debug" -> {
                val args = arguments as FunkRecord
                val message = (args.fields["message"] as FunkString).value
                System.err.println("[DEBUG] $message")
                FunkRecord(emptyMap())
            }
            
            "error" -> {
                val args = arguments as FunkRecord
                val message = (args.fields["message"] as FunkString).value
                System.err.println("[ERROR] $message")
                FunkRecord(emptyMap())
            }
            
            "readLine" -> {
                val input = readLine() ?: ""
                FunkString(input)
            }
            
            else -> throw RuntimeException("Unknown Console operation: $operation")
        }
    }

    override fun canHandle(effectName: String): Boolean = effectName == "Console"
    override fun priority(): Int = 100
}
```

#### Random Effect Handler

```kotlin
class RandomEffectHandler : SyncEffectHandler {
    private val random = Random()
    
    override fun handle(operation: String, arguments: FunkValue): FunkValue {
        return when (operation) {
            "nextInt" -> {
                val args = arguments as FunkRecord
                val bound = (args.fields["bound"] as FunkInt).value
                FunkInt(random.nextInt(bound))
            }
            
            "nextFloat" -> {
                FunkFloat(random.nextDouble())
            }
            
            "nextBoolean" -> {
                FunkBoolean(random.nextBoolean())
            }
            
            "shuffle" -> {
                val args = arguments as FunkRecord
                val list = args.fields["list"] as FunkValue
                shuffleFunkList(list)
            }
            
            else -> throw RuntimeException("Unknown Random operation: $operation")
        }
    }
    
    private fun shuffleFunkList(funkList: FunkValue): FunkValue {
        // Convert Funk list to Kotlin list, shuffle, convert back
        val kotlinList = convertFunkListToKotlin(funkList).toMutableList()
        kotlinList.shuffle(random)
        return convertKotlinListToFunk(kotlinList)
    }

    override fun canHandle(effectName: String): Boolean = effectName == "Random"
    override fun priority(): Int = 100
}
```

#### Time Effect Handler

```kotlin
class TimeEffectHandler : SyncEffectHandler {
    
    override fun handle(operation: String, arguments: FunkValue): FunkValue {
        return when (operation) {
            "currentTimeMillis" -> {
                FunkInt(System.currentTimeMillis().toInt())
            }
            
            "sleep" -> {
                val args = arguments as FunkRecord
                val millis = (args.fields["millis"] as FunkInt).value
                Thread.sleep(millis.toLong())
                FunkRecord(emptyMap())
            }
            
            "formatTime" -> {
                val args = arguments as FunkRecord
                val timestamp = (args.fields["timestamp"] as FunkInt).value.toLong()
                val pattern = (args.fields["pattern"] as FunkString).value
                
                val formatter = DateTimeFormatter.ofPattern(pattern)
                val dateTime = Instant.ofEpochMilli(timestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()
                
                FunkString(dateTime.format(formatter))
            }
            
            else -> throw RuntimeException("Unknown Time operation: $operation")
        }
    }

    override fun canHandle(effectName: String): Boolean = effectName == "Time"
    override fun priority(): Int = 100
}
```

## Effect System Integration

### 1. Effect Context Management

```kotlin
/**
 * Manages effect context during function execution
 */
class EffectContextManager {
    
    /**
     * Set up function context with proper effect infrastructure
     */
    fun setupFunctionContext(
        functionContext: MutableMap<String, FunkValue>,
        parentContext: MutableMap<String, FunkValue>
    ) {
        // Inherit effect handlers from parent context
        parentContext["__handlers"]?.let { handlers ->
            functionContext["__handlers"] = handlers
        }
        
        // Inherit effect declarations
        parentContext["__effect_declarations"]?.let { declarations ->
            functionContext["__effect_declarations"] = declarations
        }
        
        // Set up effect call infrastructure
        functionContext["__effect_registry"] = parentContext["__effect_registry"] 
            ?: throw RuntimeException("Effect registry not available")
    }
    
    /**
     * Check if function has unhandled effects
     */
    fun checkUnhandledEffects(
        function: SimpleFunkFunction,
        availableHandlers: Set<String>
    ): Set<String> {
        val functionEffects = function.getUnhandledEffects()
        return functionEffects - availableHandlers
    }
}
```

### 2. Effect Call Resolution

```kotlin
/**
 * Resolves and executes effect calls
 */
class EffectCallResolver(private val registry: EffectHandlerRegistry) {
    
    fun resolveEffectCall(
        effectName: String,
        operationName: String,
        arguments: FunkValue,
        context: MutableMap<String, FunkValue>,
        interpreter: FunkInterpreter
    ): FunkValue {
        
        // Priority order:
        // 1. Active handlers (from perform expressions)
        // 2. Registered async handlers
        // 3. Registered sync handlers
        // 4. Native implementations
        
        return registry.executeEffect(effectName, operationName, arguments, context, interpreter)
    }
    
    fun shouldConvertToEffectCall(effectName: String, context: MutableMap<String, FunkValue>): Boolean {
        // Check if this should be converted to an effect call based on:
        // 1. Effect declarations
        // 2. Available handlers
        // 3. Context state
        
        val handlers = (context["__handlers"] as? FunkRecord)?.fields ?: emptyMap()
        return handlers.containsKey(effectName) || registry.isEffectDeclared(effectName)
    }
}
```

### 3. Performer Factory System

```kotlin
/**
 * Factory for creating parameterized effect handlers (performers)
 */
class PerformerFactory(
    private val implDeclaration: ImplDeclaration,
    private val capturedContext: Map<String, FunkValue>
) : FunkValue() {
    
    override fun getDisplayString(): String = "<factory-${implDeclaration.performerName}>"
    
    /**
     * Create a performer instance with given parameters
     */
    fun createPerformer(params: FunkRecord): FunkRecord {
        val operations = implDeclaration.operations.associate { op ->
            // Extract parameter names from the operation type
            val paramNames = extractParameterNames(op.paramType)
            
            // Create function with access to both operation params and handler params
            val combinedContext = capturedContext.toMutableMap()
            params.fields.forEach { (name, value) ->
                combinedContext[name] = value
            }
            
            op.name to SimpleFunkFunction(
                parameters = paramNames,
                body = extractImplementationBody(op.implementation),
                closure = combinedContext
            )
        }
        
        return FunkRecord(operations)
    }
    
    private fun extractParameterNames(paramType: TypeAnnotation?): List<String> {
        return when (paramType) {
            is RecordTypeAnnotation -> {
                // Extract field names from record type: {msg: String, level: Int} -> ["msg", "level"]
                paramType.entries.map { it.name }
            }
            is IdentifierTypeAnnotation -> {
                // Single parameter: String -> ["value"]
                listOf("value")
            }
            else -> {
                // Fallback for complex types
                listOf("args")
            }
        }
    }
    
    private fun extractImplementationBody(implementation: Expression): Expression {
        return when (implementation) {
            is LambdaExpression -> implementation.body
            else -> implementation
        }
    }
}
```

## Advanced Effect Patterns

### 1. Effect Composition

```kotlin
/**
 * Handles composition of multiple effects
 */
class EffectComposer {
    
    /**
     * Compose multiple effect handlers into a single handler
     */
    fun composeHandlers(handlers: List<FunkRecord>): FunkRecord {
        val composedOperations = mutableMapOf<String, FunkValue>()
        
        handlers.forEach { handler ->
            handler.fields.forEach { (effectName, operations) ->
                if (operations is FunkRecord) {
                    operations.fields.forEach { (operationName, operationFunction) ->
                        val key = "$effectName.$operationName"
                        composedOperations[key] = operationFunction
                    }
                }
            }
        }
        
        return FunkRecord(composedOperations)
    }
    
    /**
     * Create a scoped effect handler that delegates to parent handlers
     */
    fun createScopedHandler(
        localHandlers: Map<String, FunkRecord>,
        parentHandlers: Map<String, FunkRecord>
    ): FunkRecord {
        val scopedOperations = mutableMapOf<String, FunkValue>()
        
        // Local handlers take precedence
        localHandlers.forEach { (effectName, handler) ->
            scopedOperations[effectName] = handler
        }
        
        // Parent handlers as fallback
        parentHandlers.forEach { (effectName, handler) ->
            if (!scopedOperations.containsKey(effectName)) {
                scopedOperations[effectName] = handler
            }
        }
        
        return FunkRecord(scopedOperations)
    }
}
```

### 2. Effect Transformation

```kotlin
/**
 * Transforms effects for different execution contexts
 */
class EffectTransformer {
    
    /**
     * Transform synchronous effects to asynchronous
     */
    fun transformToAsync(syncHandler: SyncEffectHandler): AsyncEffectHandler {
        return object : AsyncEffectHandler {
            override suspend fun handle(operation: String, arguments: FunkValue): FunkValue {
                return withContext(Dispatchers.IO) {
                    syncHandler.handle(operation, arguments)
                }
            }
            
            override fun canHandle(effectName: String): Boolean = syncHandler.canHandle(effectName)
            override fun priority(): Int = syncHandler.priority()
        }
    }
    
    /**
     * Add retry logic to an effect handler
     */
    fun addRetry(handler: AsyncEffectHandler, maxRetries: Int): AsyncEffectHandler {
        return object : AsyncEffectHandler {
            override suspend fun handle(operation: String, arguments: FunkValue): FunkValue {
                var lastException: Exception? = null
                
                repeat(maxRetries + 1) { attempt ->
                    try {
                        return handler.handle(operation, arguments)
                    } catch (e: Exception) {
                        lastException = e
                        if (attempt < maxRetries) {
                            delay(1000L * (attempt + 1)) // Exponential backoff
                        }
                    }
                }
                
                throw lastException ?: RuntimeException("All retry attempts failed")
            }
            
            override fun canHandle(effectName: String): Boolean = handler.canHandle(effectName)
            override fun priority(): Int = handler.priority()
            override fun supportsCancellation(): Boolean = handler.supportsCancellation()
        }
    }
    
    /**
     * Add timeout to an effect handler
     */
    fun addTimeout(handler: AsyncEffectHandler, timeoutMs: Long): AsyncEffectHandler {
        return object : AsyncEffectHandler {
            override suspend fun handle(operation: String, arguments: FunkValue): FunkValue {
                return withTimeout(timeoutMs) {
                    handler.handle(operation, arguments)
                }
            }
            
            override fun canHandle(effectName: String): Boolean = handler.canHandle(effectName)
            override fun priority(): Int = handler.priority()
            override fun supportsCancellation(): Boolean = true
        }
    }
}
```

### 3. Effect Monitoring

```kotlin
/**
 * Monitors effect usage and performance
 */
class EffectMonitor {
    private val effectCallCounts = ConcurrentHashMap<String, AtomicLong>()
    private val effectExecutionTimes = ConcurrentHashMap<String, AtomicLong>()
    private val effectErrors = ConcurrentHashMap<String, AtomicLong>()
    
    /**
     * Record an effect call
     */
    fun recordEffectCall(effectName: String, operationName: String, executionTimeMs: Long) {
        val key = "$effectName.$operationName"
        effectCallCounts.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        effectExecutionTimes.computeIfAbsent(key) { AtomicLong(0) }.addAndGet(executionTimeMs)
    }
    
    /**
     * Record an effect error
     */
    fun recordEffectError(effectName: String, operationName: String, error: Throwable) {
        val key = "$effectName.$operationName"
        effectErrors.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
    }
    
    /**
     * Get effect statistics
     */
    fun getEffectStats(): Map<String, EffectStats> {
        val stats = mutableMapOf<String, EffectStats>()
        
        effectCallCounts.forEach { (key, count) ->
            val totalTime = effectExecutionTimes[key]?.get() ?: 0L
            val errors = effectErrors[key]?.get() ?: 0L
            val avgTime = if (count.get() > 0) totalTime.toDouble() / count.get() else 0.0
            
            stats[key] = EffectStats(
                callCount = count.get(),
                totalExecutionTimeMs = totalTime,
                averageExecutionTimeMs = avgTime,
                errorCount = errors
            )
        }
        
        return stats
    }
    
    data class EffectStats(
        val callCount: Long,
        val totalExecutionTimeMs: Long,
        val averageExecutionTimeMs: Double,
        val errorCount: Long
    )
}
```

## Testing Effect Handlers

### 1. Mock Effect Handlers

```kotlin
/**
 * Mock handler for testing
 */
class MockEffectHandler(private val effectName: String) : SyncEffectHandler {
    private val callLog = mutableListOf<EffectCall>()
    private val responses = mutableMapOf<String, FunkValue>()
    
    data class EffectCall(
        val operation: String,
        val arguments: FunkValue,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    /**
     * Set up a mock response for an operation
     */
    fun mockResponse(operation: String, response: FunkValue) {
        responses[operation] = response
    }
    
    override fun handle(operation: String, arguments: FunkValue): FunkValue {
        callLog.add(EffectCall(operation, arguments))
        return responses[operation] ?: FunkNull
    }
    
    /**
     * Verify that an operation was called
     */
    fun verifyCalled(operation: String): Boolean {
        return callLog.any { it.operation == operation }
    }
    
    /**
     * Verify call count for an operation
     */
    fun verifyCallCount(operation: String, expectedCount: Int): Boolean {
        return callLog.count { it.operation == operation } == expectedCount
    }
    
    /**
     * Get all recorded calls
     */
    fun getCalls(): List<EffectCall> = callLog.toList()
    
    override fun canHandle(effectName: String): Boolean = effectName == this.effectName
    override fun priority(): Int = 1000 // High priority for testing
}
```

### 2. Effect Testing Utilities

```funk
// Testing utilities for effects
effect TestUtils {
  mock_effect: {effect_name: String} -> MockHandler <TestUtils>
  verify_called: {handler: MockHandler, operation: String} -> Boolean <TestUtils>
  verify_call_count: {handler: MockHandler, operation: String, count: Int} -> Boolean <TestUtils>
  capture_calls: {handler: MockHandler} -> [EffectCall] <TestUtils>
}

// Example test
let test_file_processing = do
  let mock_file_io = TestUtils.mock_effect {effect_name = "FileIO"}
  
  // Set up mock responses
  TestUtils.set_mock_response {
    handler = mock_file_io,
    operation = "readFile",
    response = "test file content"
  }
  
  // Execute code under test
  let result = perform mock_file_io in do
    process_file {path = "test.txt"}
  
  // Verify interactions
  let read_called = TestUtils.verify_called {
    handler = mock_file_io,
    operation = "readFile"
  }
  
  assert {condition = read_called, message = "readFile should be called"}
  assert {condition = result == "processed: test file content", message = "Processing should work correctly"}
```

## Performance Optimization

### 1. Effect Call Caching

```kotlin
/**
 * Caches effect call results for pure operations
 */
class EffectCallCache {
    private val cache = ConcurrentHashMap<EffectCallKey, FunkValue>()
    private val pureOperations = setOf(
        "Math.sqrt", "Math.sin", "Math.cos", "String.length", "String.toUpperCase"
    )
    
    data class EffectCallKey(
        val effectName: String,
        val operation: String,
        val argumentsHash: Int
    )
    
    fun getCachedResult(
        effectName: String,
        operation: String,
        arguments: FunkValue
    ): FunkValue? {
        val operationKey = "$effectName.$operation"
        if (!pureOperations.contains(operationKey)) {
            return null
        }
        
        val key = EffectCallKey(effectName, operation, arguments.hashCode())
        return cache[key]
    }
    
    fun cacheResult(
        effectName: String,
        operation: String,
        arguments: FunkValue,
        result: FunkValue
    ) {
        val operationKey = "$effectName.$operation"
        if (pureOperations.contains(operationKey)) {
            val key = EffectCallKey(effectName, operation, arguments.hashCode())
            cache[key] = result
        }
    }
    
    fun clearCache() {
        cache.clear()
    }
}
```

### 2. Handler Pool Management

```kotlin
/**
 * Manages pools of effect handlers for better resource utilization
 */
class EffectHandlerPool {
    private val handlerPools = ConcurrentHashMap<String, Pool<AsyncEffectHandler>>()
    
    interface Pool<T> {
        fun acquire(): T
        fun release(item: T)
        fun size(): Int
    }
    
    class SimplePool<T>(
        private val factory: () -> T,
        private val maxSize: Int = 10
    ) : Pool<T> {
        private val available = ConcurrentLinkedQueue<T>()
        private val inUse = ConcurrentHashMap.newKeySet<T>()
        
        override fun acquire(): T {
            val item = available.poll() ?: factory()
            inUse.add(item)
            return item
        }
        
        override fun release(item: T) {
            if (inUse.remove(item) && available.size < maxSize) {
                available.offer(item)
            }
        }
        
        override fun size(): Int = available.size + inUse.size
    }
    
    fun <T : AsyncEffectHandler> createPool(
        effectName: String,
        factory: () -> T,
        maxSize: Int = 10
    ) {
        handlerPools[effectName] = SimplePool(factory, maxSize) as Pool<AsyncEffectHandler>
    }
    
    fun acquireHandler(effectName: String): AsyncEffectHandler? {
        return handlerPools[effectName]?.acquire()
    }
    
    fun releaseHandler(effectName: String, handler: AsyncEffectHandler) {
        handlerPools[effectName]?.release(handler)
    }
}
```

## Future Enhancements

### 1. Compile-Time Effect Analysis
- **Static effect inference** from function bodies
- **Effect dependency analysis** for optimization
- **Dead effect elimination** for unused effects

### 2. Effect System Optimizations
- **Effect call inlining** for simple operations
- **Effect handler fusion** for composed effects
- **Effect batching** for multiple operations

### 3. Advanced Handler Features
- **Handler composition DSL** for complex effect combinations
- **Effect middleware** for cross-cutting concerns
- **Dynamic handler registration** at runtime

### 4. Debugging and Tooling
- **Effect call tracing** with stack traces
- **Visual effect flow debugging** tools
- **Performance profiling** for effect handlers

---

This effect handler implementation provides Funk with a powerful, extensible, and performant effect system that integrates seamlessly with Truffle optimizations and Project Loom's virtual threads.
