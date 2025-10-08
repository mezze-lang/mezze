# Effect System Analysis and Improvement Plan

## Overview

This document provides a comprehensive analysis of Funk's current effect system implementation and outlines specific improvements needed to make it more robust, performant, and developer-friendly.

## Current Implementation Analysis

### Architecture Components

#### ✅ **What Works Well**

1. **Solid Foundation**
   - Clear separation between effect declarations, implementations, and handlers
   - Type-safe effect annotations in function signatures
   - Handler stack management for nested `perform` expressions
   - Integration with Project Loom virtual threads for async effects

2. **Type System Integration**
   - Effects properly typed as `FunkType.EffectRow`
   - Function types include effect annotations (`<Console, FileIO>`)
   - Type checker validates effect usage and handler availability
   - Effect inference propagates through function calls

3. **Runtime Execution**
   - Central `EffectHandler` class manages effect resolution
   - Handler stack maintains active handlers for nested contexts
   - Context management propagates handlers through function calls

4. **Async Support**
   - Virtual thread integration for scalable concurrency
   - State machine compilation for complex effect sequences
   - Built-in effects for IO, Network, Timer, Database operations

### Core Classes

```kotlin
// Current architecture
class EffectHandler {
    private val globalEffects = mutableMapOf<String, FunkRecord>()
    private val handlerStack = mutableListOf<FunkRecord>()
    
    fun executeEffectCall(effectName: String, operationName: String, ...)
    fun setupFunctionContext(functionContext: MutableMap<String, FunkValue>, ...)
}

class AsyncEffectHandler {
    fun executeAsyncEffectCall(effectName: String, operationName: String, ...)
    fun compileAsyncDoExpression(doExpr: DoExpression, ...)
}
```

## Major Issues Identified

### ❌ **1. Complex Handler Resolution**

**Problem**: Handler resolution logic is overly complex with multiple fallback paths:

```kotlin
// Current: Too many fallback mechanisms
val handlersRecord = (context["__handlers"] as? FunkRecord)
    ?: handlerStack.lastOrNull()
    ?: throw RuntimeException("No handlers available...")
```

**Issues**:
- Multiple code paths make debugging difficult
- Unclear precedence rules
- Error-prone context management
- Hard to reason about handler availability

### ❌ **2. Inconsistent Effect Call Detection**

**Problem**: Logic for detecting effect calls vs regular member access is fragile:

```kotlin
// Current: Multiple checks with unclear precedence
fun shouldConvertToEffectCall(effectName: String, context: Map<String, FunkValue>): Boolean {
    val handlersRecord = (context["__handlers"] as? FunkRecord) ?: getCurrentHandlers()
    if (handlersRecord?.fields?.containsKey(effectName) == true) return true
    return isEffectDeclared(effectName) || (context.containsKey(effectName) && ...)
}
```

**Issues**:
- Inconsistent behavior between contexts
- Hard to predict when something becomes an effect call
- Poor error messages when detection fails

### ❌ **3. State Machine Compilation Issues**

**Problem**: Async state machine compilation has several critical issues:

```kotlin
// Current: Resource leaks and poor error handling
private fun executeAsync(frame: VirtualFrame, arguments: FunkValue): FunkValue {
    val virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()
    return try {
        val future = CompletableFuture.supplyAsync({...}, virtualThreadExecutor)
        future.get() // Blocking - defeats the purpose of async
    } finally {
        virtualThreadExecutor.shutdown() // May not execute if exception thrown
    }
}
```

**Issues**:
- **Memory Leaks**: CompletableFutures and executors not properly cleaned up
- **Resource Management**: New executor created for each effect call
- **Error Handling**: Poor error propagation across state transitions
- **Debugging**: No visibility into state machine execution
- **Performance**: Blocking calls defeat async benefits

### ❌ **4. Missing Effect Composition Patterns**

**Problem**: No support for common effect patterns:

- **Effect Transformation**: Converting sync effects to async
- **Effect Middleware**: Cross-cutting concerns like logging, metrics
- **Effect Batching**: Combining multiple operations for efficiency
- **Effect Caching**: Memoizing pure effect operations
- **Effect Retry**: Automatic retry with backoff strategies

### ❌ **5. Poor Error Messages**

**Problem**: Error messages are not user-friendly:

```kotlin
// Current: Technical error messages
throw RuntimeException("No handler found for effect: $effectName")
```

**Issues**:
- No context about available alternatives
- No suggestions for fixing the problem
- Technical jargon instead of user-friendly explanations

### ❌ **6. Testing Difficulties**

**Problem**: No built-in support for testing effectful code:

- No mock effect handlers
- No way to verify effect calls
- No isolation between tests
- Difficult to test error scenarios

## Improvement Plan

### 🚀 **1. Unified Effect Context**

**Goal**: Create a single, immutable effect context that's passed through all operations.

```kotlin
data class EffectContext(
    val declaredEffects: Set<String>,
    val activeHandlers: Map<String, EffectHandler>,
    val performStack: List<Map<String, EffectHandler>>,
    val globalHandlers: Map<String, EffectHandler>
) {
    fun withHandlers(handlers: Map<String, EffectHandler>): EffectContext {
        return copy(performStack = performStack + handlers)
    }
    
    fun resolveHandler(effectName: String): EffectHandler? {
        return performStack.lastOrNull()?.get(effectName)
            ?: activeHandlers[effectName]
            ?: globalHandlers[effectName]
    }
    
    fun isEffectDeclared(effectName: String): Boolean {
        return effectName in declaredEffects
    }
    
    fun hasHandler(effectName: String): Boolean {
        return resolveHandler(effectName) != null
    }
}
```

**Benefits**:
- Single source of truth for effect resolution
- Immutable and thread-safe
- Clear precedence rules
- Easy to test and reason about

### 🚀 **2. Simplified Effect Registry**

**Goal**: Replace complex detection logic with a dedicated registry.

```kotlin
class EffectRegistry {
    private val declaredEffects = mutableSetOf<String>()
    private val availableHandlers = mutableMapOf<String, EffectHandler>()
    private val handlerFactories = mutableMapOf<String, () -> EffectHandler>()
    
    fun declareEffect(name: String) {
        declaredEffects.add(name)
    }
    
    fun registerHandler(name: String, handler: EffectHandler) {
        availableHandlers[name] = handler
    }
    
    fun registerHandlerFactory(name: String, factory: () -> EffectHandler) {
        handlerFactories[name] = factory
    }
    
    fun isEffectCall(name: String): Boolean = name in declaredEffects
    
    fun hasHandler(name: String): Boolean = 
        name in availableHandlers || name in handlerFactories
    
    fun getHandler(name: String): EffectHandler? {
        return availableHandlers[name] 
            ?: handlerFactories[name]?.invoke()?.also { 
                availableHandlers[name] = it 
            }
    }
}
```

### 🚀 **3. Structured Async Effect Execution**

**Goal**: Fix resource management and improve async effect execution.

```kotlin
class StructuredEffectExecutor {
    private val virtualThreadFactory = Thread.ofVirtual()
        .name("funk-effect-", 0)
        .factory()
    
    fun <T> executeAsync(task: () -> T): CompletableFuture<T> {
        return StructuredTaskScope.ShutdownOnFailure().use { scope ->
            val future = scope.fork(task)
            scope.join()
            scope.throwIfFailed()
            CompletableFuture.completedFuture(future.get())
        }
    }
    
    fun <T> executeParallel(tasks: List<() -> T>): CompletableFuture<List<T>> {
        return StructuredTaskScope.ShutdownOnFailure().use { scope ->
            val futures = tasks.map { scope.fork(it) }
            scope.join()
            scope.throwIfFailed()
            CompletableFuture.completedFuture(futures.map { it.get() })
        }
    }
    
    fun <T> executeRace(tasks: List<() -> T>): CompletableFuture<T> {
        return StructuredTaskScope.ShutdownOnSuccess<T>().use { scope ->
            tasks.forEach { scope.fork(it) }
            scope.join()
            CompletableFuture.completedFuture(scope.result())
        }
    }
}
```

### 🚀 **4. Effect DSL for Better Ergonomics**

**Goal**: Make effect handler creation more intuitive and less verbose.

```kotlin
// Current: Verbose handler creation
val consoleHandler = FunkRecord(mapOf(
    "print" to SimpleFunkFunction(...)
))

// Improved: DSL for handler creation
fun effectHandler(effectName: String, block: EffectHandlerBuilder.() -> Unit): EffectHandler {
    val builder = EffectHandlerBuilder(effectName)
    builder.block()
    return builder.build()
}

class EffectHandlerBuilder(private val effectName: String) {
    private val operations = mutableMapOf<String, EffectOperation>()
    
    fun operation(name: String, handler: (Map<String, FunkValue>) -> FunkValue) {
        operations[name] = EffectOperation(name, handler)
    }
    
    fun asyncOperation(name: String, handler: suspend (Map<String, FunkValue>) -> FunkValue) {
        operations[name] = AsyncEffectOperation(name, handler)
    }
    
    fun build(): EffectHandler = SimpleEffectHandler(effectName, operations)
}

// Usage:
val consoleHandler = effectHandler("Console") {
    operation("print") { args ->
        println(args["message"])
        unit()
    }
    
    operation("debug") { args ->
        System.err.println("[DEBUG] ${args["message"]}")
        unit()
    }
    
    asyncOperation("readLine") { args ->
        withContext(Dispatchers.IO) {
            FunkString(readLine() ?: "")
        }
    }
}
```

### 🚀 **5. Effect Middleware System**

**Goal**: Support cross-cutting concerns like logging, metrics, caching, and retry logic.

```kotlin
interface EffectMiddleware {
    fun intercept(call: EffectCall, next: (EffectCall) -> FunkValue): FunkValue
}

class MetricsMiddleware : EffectMiddleware {
    private val callCounts = ConcurrentHashMap<String, AtomicLong>()
    private val executionTimes = ConcurrentHashMap<String, AtomicLong>()
    
    override fun intercept(call: EffectCall, next: (EffectCall) -> FunkValue): FunkValue {
        val key = "${call.effectName}.${call.operation}"
        val start = System.nanoTime()
        
        return try {
            val result = next(call)
            recordSuccess(key, System.nanoTime() - start)
            result
        } catch (e: Exception) {
            recordError(key, e)
            throw e
        }
    }
    
    private fun recordSuccess(key: String, durationNs: Long) {
        callCounts.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        executionTimes.computeIfAbsent(key) { AtomicLong(0) }.addAndGet(durationNs)
    }
}

class RetryMiddleware(
    private val maxRetries: Int = 3,
    private val backoffMs: Long = 1000
) : EffectMiddleware {
    override fun intercept(call: EffectCall, next: (EffectCall) -> FunkValue): FunkValue {
        var lastException: Exception? = null
        
        repeat(maxRetries + 1) { attempt ->
            try {
                return next(call)
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries && isRetryable(e)) {
                    Thread.sleep(backoffMs * (attempt + 1))
                } else {
                    throw e
                }
            }
        }
        
        throw lastException ?: RuntimeException("All retry attempts failed")
    }
    
    private fun isRetryable(e: Exception): Boolean {
        return e is IOException || e is TimeoutException
    }
}

class CachingMiddleware : EffectMiddleware {
    private val cache = ConcurrentHashMap<EffectCallKey, FunkValue>()
    private val pureOperations = setOf(
        "Math.sqrt", "Math.sin", "Math.cos", 
        "String.length", "String.toUpperCase"
    )
    
    override fun intercept(call: EffectCall, next: (EffectCall) -> FunkValue): FunkValue {
        val operationKey = "${call.effectName}.${call.operation}"
        
        if (operationKey in pureOperations) {
            val cacheKey = EffectCallKey(call.effectName, call.operation, call.arguments.hashCode())
            return cache.computeIfAbsent(cacheKey) { next(call) }
        }
        
        return next(call)
    }
}

// Composable middleware
class MiddlewareChain(private val middlewares: List<EffectMiddleware>) {
    fun execute(call: EffectCall, finalHandler: (EffectCall) -> FunkValue): FunkValue {
        return executeRecursive(0, call, finalHandler)
    }
    
    private fun executeRecursive(
        index: Int, 
        call: EffectCall, 
        finalHandler: (EffectCall) -> FunkValue
    ): FunkValue {
        return if (index >= middlewares.size) {
            finalHandler(call)
        } else {
            middlewares[index].intercept(call) { nextCall ->
                executeRecursive(index + 1, nextCall, finalHandler)
            }
        }
    }
}
```

### 🚀 **6. Improved State Machine Compilation**

**Goal**: Create efficient, debuggable state machines for complex effect sequences.

```kotlin
class EffectStateMachine {
    private val states = mutableListOf<EffectState>()
    private var currentState = 0
    private val context = mutableMapOf<String, FunkValue>()
    
    sealed class EffectState {
        data class Compute(
            val id: String,
            val computation: (Map<String, FunkValue>) -> FunkValue
        ) : EffectState()
        
        data class Effect(
            val id: String,
            val call: EffectCall,
            val continuation: String // Next state ID
        ) : EffectState()
        
        data class Parallel(
            val id: String,
            val effects: List<EffectCall>,
            val combiner: (List<FunkValue>) -> FunkValue,
            val continuation: String
        ) : EffectState()
        
        data class Race(
            val id: String,
            val effects: List<EffectCall>,
            val continuation: String
        ) : EffectState()
        
        data class Conditional(
            val id: String,
            val condition: (Map<String, FunkValue>) -> Boolean,
            val thenState: String,
            val elseState: String
        ) : EffectState()
    }
    
    suspend fun execute(): FunkValue {
        while (currentState < states.size) {
            val state = states[currentState]
            
            when (state) {
                is EffectState.Compute -> {
                    val result = state.computation(context)
                    context["result_${state.id}"] = result
                    currentState++
                }
                
                is EffectState.Effect -> {
                    val result = executeEffectAsync(state.call)
                    context["result_${state.id}"] = result
                    currentState = findStateIndex(state.continuation)
                }
                
                is EffectState.Parallel -> {
                    val results = coroutineScope {
                        state.effects.map { async { executeEffectAsync(it) } }
                    }.awaitAll()
                    
                    val combined = state.combiner(results)
                    context["result_${state.id}"] = combined
                    currentState = findStateIndex(state.continuation)
                }
                
                is EffectState.Race -> {
                    val result = coroutineScope {
                        select {
                            state.effects.forEach { effect ->
                                async { executeEffectAsync(effect) }.onAwait { it }
                            }
                        }
                    }
                    context["result_${state.id}"] = result
                    currentState = findStateIndex(state.continuation)
                }
                
                is EffectState.Conditional -> {
                    currentState = if (state.condition(context)) {
                        findStateIndex(state.thenState)
                    } else {
                        findStateIndex(state.elseState)
                    }
                }
            }
        }
        
        return context["final_result"] ?: FunkNull
    }
    
    private fun findStateIndex(stateId: String): Int {
        return states.indexOfFirst { 
            when (it) {
                is EffectState.Compute -> it.id == stateId
                is EffectState.Effect -> it.id == stateId
                is EffectState.Parallel -> it.id == stateId
                is EffectState.Race -> it.id == stateId
                is EffectState.Conditional -> it.id == stateId
            }
        }
    }
    
    // Builder methods
    fun addCompute(id: String, computation: (Map<String, FunkValue>) -> FunkValue) {
        states.add(EffectState.Compute(id, computation))
    }
    
    fun addEffect(id: String, call: EffectCall, continuation: String) {
        states.add(EffectState.Effect(id, call, continuation))
    }
    
    fun addParallel(
        id: String, 
        effects: List<EffectCall>, 
        combiner: (List<FunkValue>) -> FunkValue,
        continuation: String
    ) {
        states.add(EffectState.Parallel(id, effects, combiner, continuation))
    }
}
```

### 🚀 **7. Better Error Messages**

**Goal**: Provide helpful, actionable error messages with suggestions.

```kotlin
sealed class EffectError : RuntimeException() {
    abstract val effectName: String
    abstract val operation: String?
    abstract val suggestions: List<String>
    
    data class HandlerNotFound(
        override val effectName: String,
        override val operation: String,
        val availableHandlers: Set<String>,
        val declaredEffects: Set<String>
    ) : EffectError() {
        
        override val suggestions: List<String> get() = buildList {
            if (effectName in declaredEffects) {
                add("Add a handler for '$effectName' using: perform ${effectName}Handler in ...")
                
                if (availableHandlers.isNotEmpty()) {
                    val similar = availableHandlers.filter { 
                        it.lowercase().contains(effectName.lowercase()) ||
                        effectName.lowercase().contains(it.lowercase())
                    }
                    if (similar.isNotEmpty()) {
                        add("Did you mean one of: ${similar.joinToString()}")
                    }
                }
            } else {
                add("Effect '$effectName' is not declared. Add: effect $effectName { ... }")
                
                val similar = declaredEffects.filter {
                    levenshteinDistance(it.lowercase(), effectName.lowercase()) <= 2
                }
                if (similar.isNotEmpty()) {
                    add("Did you mean: ${similar.joinToString()}")
                }
            }
        }
        
        override val message: String get() = buildString {
            appendLine("Effect '$effectName' has no handler for operation '$operation'")
            
            if (availableHandlers.isNotEmpty()) {
                appendLine("Available handlers: ${availableHandlers.joinToString()}")
            }
            
            if (suggestions.isNotEmpty()) {
                appendLine("\nSuggestions:")
                suggestions.forEach { appendLine("  • $it") }
            }
        }
    }
    
    data class OperationNotFound(
        override val effectName: String,
        override val operation: String,
        val availableOperations: Set<String>
    ) : EffectError() {
        
        override val suggestions: List<String> get() = buildList {
            if (availableOperations.isNotEmpty()) {
                add("Available operations for '$effectName': ${availableOperations.joinToString()}")
                
                val similar = availableOperations.filter {
                    levenshteinDistance(it.lowercase(), operation.lowercase()) <= 2
                }
                if (similar.isNotEmpty()) {
                    add("Did you mean: ${similar.joinToString()}")
                }
            }
        }
        
        override val message: String get() = buildString {
            appendLine("Operation '$operation' not found in effect '$effectName'")
            
            if (suggestions.isNotEmpty()) {
                appendLine("\nSuggestions:")
                suggestions.forEach { appendLine("  • $it") }
            }
        }
    }
}

fun levenshteinDistance(s1: String, s2: String): Int {
    val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
    
    for (i in 0..s1.length) dp[i][0] = i
    for (j in 0..s2.length) dp[0][j] = j
    
    for (i in 1..s1.length) {
        for (j in 1..s2.length) {
            dp[i][j] = if (s1[i-1] == s2[j-1]) {
                dp[i-1][j-1]
            } else {
                1 + minOf(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
            }
        }
    }
    
    return dp[s1.length][s2.length]
}
```

### 🚀 **8. Effect Testing Framework**

**Goal**: Make testing effectful code easy and reliable.

```kotlin
class EffectTestFramework {
    private val mockHandlers = mutableMapOf<String, MockEffectHandler>()
    
    fun mockEffect(effectName: String): MockEffectHandler {
        val handler = MockEffectHandler(effectName)
        mockHandlers[effectName] = handler
        return handler
    }
    
    fun createTestContext(vararg handlers: MockEffectHandler): EffectContext {
        val handlerMap = handlers.associate { it.effectName to it as EffectHandler }
        return EffectContext(
            declaredEffects = handlers.map { it.effectName }.toSet(),
            activeHandlers = handlerMap,
            performStack = emptyList(),
            globalHandlers = emptyMap()
        )
    }
    
    fun verifyEffectCalls(handler: MockEffectHandler): EffectCallVerifier {
        return EffectCallVerifier(handler.getCalls())
    }
    
    fun resetAllMocks() {
        mockHandlers.values.forEach { it.reset() }
    }
}

class MockEffectHandler(val effectName: String) : EffectHandler {
    private val calls = mutableListOf<EffectCall>()
    private val responses = mutableMapOf<String, FunkValue>()
    private val exceptions = mutableMapOf<String, Exception>()
    
    fun whenCalled(operation: String): MockOperationBuilder {
        return MockOperationBuilder(operation, this)
    }
    
    internal fun setResponse(operation: String, response: FunkValue) {
        responses[operation] = response
    }
    
    internal fun setException(operation: String, exception: Exception) {
        exceptions[operation] = exception
    }
    
    override fun handle(operation: String, args: FunkValue): FunkValue {
        calls.add(EffectCall(effectName, operation, args, System.currentTimeMillis()))
        
        exceptions[operation]?.let { throw it }
        return responses[operation] ?: FunkNull
    }
    
    fun getCalls(): List<EffectCall> = calls.toList()
    
    fun getCallsFor(operation: String): List<EffectCall> {
        return calls.filter { it.operation == operation }
    }
    
    fun reset() {
        calls.clear()
        responses.clear()
        exceptions.clear()
    }
}

class MockOperationBuilder(
    private val operation: String,
    private val handler: MockEffectHandler
) {
    fun thenReturn(value: FunkValue) {
        handler.setResponse(operation, value)
    }
    
    fun thenThrow(exception: Exception) {
        handler.setException(operation, exception)
    }
}

class EffectCallVerifier(private val calls: List<EffectCall>) {
    fun wasCalled(operation: String): Boolean {
        return calls.any { it.operation == operation }
    }
    
    fun wasCalledTimes(operation: String, times: Int): Boolean {
        return calls.count { it.operation == operation } == times
    }
    
    fun wasCalledWith(operation: String, args: FunkValue): Boolean {
        return calls.any { it.operation == operation && it.arguments == args }
    }
    
    fun wasCalledInOrder(vararg operations: String): Boolean {
        val operationCalls = calls.map { it.operation }
        var lastIndex = -1
        
        for (operation in operations) {
            val index = operationCalls.indexOf(operation, lastIndex + 1)
            if (index == -1) return false
            lastIndex = index
        }
        
        return true
    }
    
    fun getAllCalls(): List<EffectCall> = calls
}

data class EffectCall(
    val effectName: String,
    val operation: String,
    val arguments: FunkValue,
    val timestamp: Long
)

// Usage example:
class EffectTest {
    private val testFramework = EffectTestFramework()
    
    @Test
    fun testFileProcessing() {
        // Arrange
        val fileIO = testFramework.mockEffect("FileIO")
        val console = testFramework.mockEffect("Console")
        
        fileIO.whenCalled("readFile").thenReturn(FunkString("test content"))
        fileIO.whenCalled("writeFile").thenReturn(unit())
        console.whenCalled("print").thenReturn(unit())
        
        val context = testFramework.createTestContext(fileIO, console)
        
        // Act
        val result = executeWithContext(context) {
            processFile("input.txt")
        }
        
        // Assert
        val fileVerifier = testFramework.verifyEffectCalls(fileIO)
        val consoleVerifier = testFramework.verifyEffectCalls(console)
        
        assertTrue(fileVerifier.wasCalled("readFile"))
        assertTrue(fileVerifier.wasCalled("writeFile"))
        assertTrue(consoleVerifier.wasCalled("print"))
        assertTrue(fileVerifier.wasCalledInOrder("readFile", "writeFile"))
        
        assertEquals(FunkString("processed: test content"), result)
    }
}
```

## Performance Optimizations

### 🔥 **1. Effect Call Inlining**

For simple, pure effects, inline the handler call directly:

```kotlin
class EffectInliner {
    private val inlinableEffects = setOf("Math", "String", "Array")
    
    fun shouldInline(effectName: String, operation: String): Boolean {
        return effectName in inlinableEffects && isPureOperation(effectName, operation)
    }
    
    fun inlineEffectCall(effectName: String, operation: String, args: FunkValue): FunkValue? {
        return when ("$effectName.$operation") {
            "Math.sqrt" -> {
                val x = (args as FunkRecord).fields["x"] as FunkFloat
                FunkFloat(sqrt(x.value))
            }
            "String.length" -> {
                val str = (args as FunkRecord).fields["str"] as FunkString
                FunkInt(str.value.length)
            }
            "Array.length" -> {
                val arr = (args as FunkRecord).fields["array"] as FunkArrayList
                FunkInt(arr.elements.size)
            }
            else -> null
        }
    }
}
```

### 🔥 **2. Effect Batching**

Combine multiple operations of the same effect:

```kotlin
class EffectBatcher {
    private val batchableEffects = setOf("Database", "HTTP", "FileIO")
    private val pendingBatches = mutableMapOf<String, MutableList<EffectCall>>()
    
    fun shouldBatch(effectName: String): Boolean {
        return effectName in batchableEffects
    }
    
    fun addToBatch(call: EffectCall): CompletableFuture<FunkValue> {
        val batch = pendingBatches.getOrPut(call.effectName) { mutableListOf() }
        batch.add(call)
        
        // Execute batch when it reaches threshold or after timeout
        if (batch.size >= getBatchThreshold(call.effectName)) {
            return executeBatch(call.effectName)
        }
        
        return call.future
    }
    
    private fun executeBatch(effectName: String): CompletableFuture<FunkValue> {
        val batch = pendingBatches.remove(effectName) ?: return CompletableFuture.completedFuture(FunkNull)
        
        return when (effectName) {
            "Database" -> executeDatabaseBatch(batch)
            "HTTP" -> executeHTTPBatch(batch)
            "FileIO" -> executeFileIOBatch(batch)
            else -> CompletableFuture.completedFuture(FunkNull)
        }
    }
}
```

### 🔥 **3. Effect Caching**

Cache results of pure effect operations:

```kotlin
class EffectCache {
    private val cache = ConcurrentHashMap<EffectCallKey, CacheEntry>()
    private val pureOperations = setOf(
        "Math.sqrt", "Math.sin", "Math.cos", "Math.abs",
        "String.length", "String.toUpperCase", "String.toLowerCase",
        "Array.length", "Array.isEmpty"
    )
    
    data class CacheEntry(
        val value: FunkValue,
        val timestamp: Long,
        val ttl: Long = Long.MAX_VALUE
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() > timestamp + ttl
    }
    
    fun getCachedResult(call: EffectCall): FunkValue? {
        val operationKey = "${call.effectName}.${call.operation}"
        if (operationKey !in pureOperations) return null
        
        val key = EffectCallKey(call.effectName, call.operation, call.arguments.hashCode())
        val entry = cache[key]
        
        return if (entry != null && !entry.isExpired()) {
            entry.value
        } else {
            cache.remove(key)
            null
        }
    }
    
    fun cacheResult(call: EffectCall, result: FunkValue, ttl: Long = Long.MAX_VALUE) {
        val operationKey = "${call.effectName}.${call.operation}"
        if (operationKey in pureOperations) {
            val key = EffectCallKey(call.effectName, call.operation, call.arguments.hashCode())
            cache[key] = CacheEntry(result, System.currentTimeMillis(), ttl)
        }
    }
}

data class EffectCallKey(
    val effectName: String,
    val operation: String,
    val argumentsHash: Int
)
```

## Implementation Priority

### Phase 1: Foundation (High Priority)
1. **Unified Effect Context** - Replace complex handler resolution
2. **Effect Registry** - Simplify effect call detection
3. **Better Error Messages** - Improve developer experience
4. **Effect Testing Framework** - Enable reliable testing

### Phase 2: Performance (Medium Priority)
1. **Structured Async Execution** - Fix resource leaks and improve performance
2. **Effect Middleware System** - Add cross-cutting concerns
3. **Effect Caching** - Optimize pure operations
4. **Effect Inlining** - Optimize simple operations

### Phase 3: Advanced Features (Lower Priority)
1. **Improved State Machine Compilation** - Better async composition
2. **Effect DSL** - Improve handler creation ergonomics
3. **Effect Batching** - Optimize multiple operations
4. **Advanced Debugging Tools** - State machine visualization

## Migration Strategy

### Step 1: Backward Compatibility
- Keep existing APIs working
- Add new APIs alongside old ones
- Provide migration utilities

### Step 2: Gradual Migration
- Migrate built-in effects first
- Update documentation and examples
- Provide clear migration guide

### Step 3: Deprecation
- Mark old APIs as deprecated
- Provide clear timeline for removal
- Ensure all functionality is available in new APIs

### Step 4: Cleanup
- Remove deprecated APIs
- Optimize for new architecture
- Update all documentation

## Success Metrics

### Developer Experience
- Reduced time to understand effect errors
- Fewer questions about effect system behavior
- Improved test coverage of effectful code

### Performance
- Reduced memory usage in async effects
- Improved throughput for effect-heavy applications
- Better resource utilization

### Reliability
- Fewer effect-related bugs
- Better error recovery
- More predictable behavior

## Conclusion

The current effect system has a solid foundation but needs significant improvements in handler resolution, async execution, error handling, and developer experience. The proposed improvements will make the system more robust, performant, and easier to use while maintaining the functional programming principles that make algebraic effects powerful.

The implementation should be done in phases, starting with the foundation improvements that will have the biggest impact on developer experience and reliability, then moving to performance optimizations and advanced features.