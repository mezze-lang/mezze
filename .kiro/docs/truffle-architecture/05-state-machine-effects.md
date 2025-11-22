# State Machine Effects with Project Loom

## Overview

Funk implements algebraic effects as state machines executed on Project Loom's virtual threads. This approach provides efficient, scalable concurrency while maintaining the purity and composability of algebraic effects, similar to the Roc programming language.

## Core Concepts

### 1. Effects as State Machines

Each effectful computation becomes a state machine where:
- **States** represent computation steps
- **Transitions** represent effect operations
- **Suspension points** occur at effect calls
- **Resumption** continues from suspension points

```funk
// This effectful computation:
let file_processing = do
  let config = FileIO.readFile {path = "config.json"}     // Suspension point 1
  let data = FileIO.readFile {path = "data.txt"}          // Suspension point 2  
  let processed = process_data {config = config, data = data}
  FileIO.writeFile {path = "output.txt", content = processed}  // Suspension point 3
  processed

// Becomes this state machine:
// State 0: Execute FileIO.readFile("config.json") -> suspend -> State 1
// State 1: Bind config, execute FileIO.readFile("data.txt") -> suspend -> State 2  
// State 2: Bind data, execute process_data -> State 3
// State 3: Execute FileIO.writeFile -> suspend -> State 4
// State 4: Return result
```

### 2. Virtual Thread Execution Model

Each state machine runs on a Project Loom virtual thread:

```kotlin
// Virtual thread characteristics:
// - Lightweight: ~1KB memory overhead vs ~1MB for OS threads
// - Scalable: Millions can be created
// - Suspendable: Automatically suspend during I/O
// - Managed: Scheduled by JVM, not OS
```

## Architecture Implementation

### 1. State Machine Structure

```kotlin
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
    val localVariables: MutableMap<String, FunkValue>,
    val continuations: List<Continuation>,
    val effectContext: EffectContext,
    val executionState: ExecutionState = ExecutionState.RUNNING
) {
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
    fun resume(value: FunkValue): StateMachine {
        return if (continuations.isNotEmpty()) {
            val continuation = continuations.last()
            val remainingContinuations = continuations.dropLast(1)
            continuation.resume(value, copy(continuations = remainingContinuations))
        } else {
            copy(executionState = ExecutionState.COMPLETED)
        }
    }
}
```

### 2. Virtual Thread Executor

```kotlin
/**
 * Virtual thread executor for async operations using Project Loom
 */
class VirtualThreadExecutor {
    companion object {
        private val virtualThreadFactory = ThreadFactory { runnable ->
            Thread.ofVirtual()
                .name("funk-effect-", 0)
                .uncaughtExceptionHandler { _, ex -> 
                    System.err.println("Uncaught exception in virtual thread: $ex")
                }
                .unstarted(runnable)
        }

        private val executor: Executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)

        /**
         * Submit a task for async execution on virtual thread
         */
        fun submit(task: () -> FunkValue): CompletableFuture<FunkValue> {
            val future = CompletableFuture<FunkValue>()
            executor.execute {
                try {
                    val result = task()
                    future.complete(result)
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                }
            }
            return future
        }

        /**
         * Submit a suspending task for async execution
         */
        fun submitSuspending(task: suspend () -> FunkValue): CompletableFuture<FunkValue> {
            val future = CompletableFuture<FunkValue>()
            executor.execute {
                try {
                    // Execute suspending function on virtual thread
                    val result = kotlinx.coroutines.runBlocking { task() }
                    future.complete(result)
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                }
            }
            return future
        }

        /**
         * Submit with timeout support
         */
        fun submitWithTimeout(timeout: Duration, task: () -> FunkValue): CompletableFuture<FunkValue> {
            val future = submit(task)
            return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        }
    }
}
```

### 3. Effect Context and Continuations

```kotlin
/**
 * Context for effect handling within a state machine
 */
data class EffectContext(
    val handlers: Map<String, FunkRecord> = emptyMap(),
    val currentHandler: FunkRecord? = null
) {
    fun withHandlers(newHandlers: Map<String, FunkRecord>): EffectContext {
        return copy(handlers = handlers + newHandlers)
    }
}

/**
 * Represents a continuation point where execution can be resumed
 */
interface Continuation {
    fun resume(value: FunkValue, stateMachine: StateMachine): StateMachine
    fun handleError(error: Throwable, stateMachine: StateMachine): StateMachine
}

/**
 * Continuation for let bindings in do blocks
 */
data class LetContinuation(
    val variableName: String,
    val nextState: Int
) : Continuation {
    override fun resume(value: FunkValue, stateMachine: StateMachine): StateMachine {
        return stateMachine
            .withVariable(variableName, value)
            .withState(nextState, newExecutionState = ExecutionState.RUNNING)
    }

    override fun handleError(error: Throwable, stateMachine: StateMachine): StateMachine {
        return stateMachine.copy(executionState = ExecutionState.FAILED)
    }
}
```

## Effect Compilation to State Machines

### 1. Do Block Compilation

```kotlin
/**
 * Compile a do block into state machine states
 */
private fun compileDoBlockToStates(expr: DoExpression): List<StateMachineState> {
    val states = mutableListOf<StateMachineState>()
    
    // Each statement becomes a state
    expr.statements.forEachIndexed { index, statement ->
        when (statement) {
            is LetStatement -> {
                if (containsEffectCallsInExpression(statement.expression)) {
                    // Effect call in let binding - this is a suspension point
                    states.add(StateMachineState.EffectState(index, statement.expression))
                    states.add(StateMachineState.LetState(index + 1, statement.variableName, statement.expression))
                } else {
                    // Pure let binding
                    states.add(StateMachineState.LetState(index, statement.variableName, statement.expression))
                }
            }
            is ExpressionStatement -> {
                if (containsEffectCallsInExpression(statement.expression)) {
                    // Effect call - this is a suspension point
                    states.add(StateMachineState.EffectState(index, statement.expression))
                } else {
                    // Pure expression
                    states.add(StateMachineState.ExpressionState(index, statement.expression))
                }
            }
        }
    }
    
    // Final result expression
    if (containsEffectCallsInExpression(expr.result)) {
        states.add(StateMachineState.EffectState(expr.statements.size, expr.result))
    }
    states.add(StateMachineState.ResultState(expr.statements.size + 1, expr.result))
    
    return states
}

/**
 * Check if an expression contains effect calls
 */
private fun containsEffectCallsInExpression(expr: Expression): Boolean {
    return when (expr) {
        is EffectCallExpression -> true
        is DoExpression -> containsEffectCalls(expr)
        is FunctionCall -> {
            // Check if this is an effect call (Log.print, Console.debug, etc.)
            expr.function is MemberAccess && 
            (expr.function as MemberAccess).receiver is Identifier &&
            effectHandler.isEffectDeclared(((expr.function as MemberAccess).receiver as Identifier).name)
        }
        is BinaryOperation -> containsEffectCallsInExpression(expr.left) || containsEffectCallsInExpression(expr.right)
        is IfExpression -> containsEffectCallsInExpression(expr.condition) || 
                           containsEffectCallsInExpression(expr.thenBranch) || 
                           containsEffectCallsInExpression(expr.elseBranch)
        is MatchExpression -> containsEffectCallsInExpression(expr.expression) || 
                              expr.cases.any { containsEffectCallsInExpression(it.expression) }
        is RecordLiteral -> expr.entries.any { it.value?.let { v -> containsEffectCallsInExpression(v) } ?: false }
        is LambdaExpression -> containsEffectCallsInExpression(expr.body)
        is MemberAccess -> containsEffectCallsInExpression(expr.receiver)
        is PerformExpression -> containsEffectCallsInExpression(expr.expression)
        else -> false
    }
}
```

### 2. State Machine Execution

```kotlin
/**
 * Execute state machine asynchronously using Loom virtual threads
 */
private fun executeStateMachineAsync(
    initialStateMachine: StateMachine, 
    states: List<StateMachineState>, 
    context: MutableMap<String, FunkValue>
): FunkValue {
    var currentMachine = initialStateMachine
    var result: FunkValue = FunkNull
    
    for (state in states) {
        when (state) {
            is StateMachineState.LetState -> {
                // Execute let binding
                val value = evaluateExpression(state.expression, currentMachine.localVariables)
                currentMachine.localVariables[state.variableName] = value
            }
            
            is StateMachineState.ExpressionState -> {
                // Execute pure expression
                evaluateExpression(state.expression, currentMachine.localVariables)
            }
            
            is StateMachineState.EffectState -> {
                // This is a suspension point - execute effect call asynchronously
                result = executeEffectStateAsync(state, currentMachine)
                
                // Update machine state after effect execution
                if (result is SuspensionPoint) {
                    // Machine was suspended - will be resumed later
                    return result.awaitCompletion()
                }
            }
            
            is StateMachineState.ResultState -> {
                // Final result
                result = evaluateExpression(state.expression, currentMachine.localVariables)
            }
        }
    }
    
    return result
}

/**
 * Execute an effect state with suspension support
 */
private fun executeEffectStateAsync(
    state: StateMachineState.EffectState,
    stateMachine: StateMachine
): FunkValue {
    return when (val expr = state.expression) {
        is EffectCallExpression -> {
            // Direct effect call - execute with handler
            executeAsyncEffectCall(expr, stateMachine)
        }
        
        is FunctionCall -> {
            if (expr.function is MemberAccess) {
                val memberAccess = expr.function as MemberAccess
                if (memberAccess.receiver is Identifier) {
                    val effectName = (memberAccess.receiver as Identifier).name
                    val operationName = memberAccess.member
                    
                    // Convert to effect call and execute
                    val effectCall = EffectCallExpression(effectName, operationName, expr.arguments)
                    executeAsyncEffectCall(effectCall, stateMachine)
                } else {
                    evaluateExpression(expr, stateMachine.localVariables)
                }
            } else {
                evaluateExpression(expr, stateMachine.localVariables)
            }
        }
        
        else -> evaluateExpression(expr, stateMachine.localVariables)
    }
}
```

## Async Effect Examples

### 1. File I/O Effect

```funk
// Define file I/O effect
effect FileIO {
  readFile: {path: String} -> String <FileIO>
  writeFile: {path: String, content: String} -> Unit <FileIO>
  exists: {path: String} -> Boolean <FileIO>
}

// Async implementation using virtual threads
impl AsyncFileIO for FileIO {
  readFile = {path} -> do
    // This suspends the virtual thread during I/O
    let content = async_read_file {path = path}
    content
    
  writeFile = {path, content} -> do
    // This also suspends during I/O
    async_write_file {path = path, content = content}
    {}
    
  exists = {path} -> do
    async_file_exists {path = path}
}

// Usage - automatically becomes state machine
let process_config = do
  let config_exists = FileIO.exists {path = "config.json"}     // Suspension point 1
  
  let config = if config_exists then
    FileIO.readFile {path = "config.json"}                     // Suspension point 2
  else
    "{\"default\": true}"
  
  let processed_config = enhance_config {config = config}
  
  FileIO.writeFile {                                           // Suspension point 3
    path = "processed_config.json", 
    content = processed_config
  }
  
  processed_config
```

**State Machine Execution:**
```
Virtual Thread 1:
├─ State 0: FileIO.exists("config.json") → suspend
├─ State 1: Bind config_exists, evaluate if condition → continue  
├─ State 2: FileIO.readFile("config.json") → suspend
├─ State 3: Bind config, call enhance_config → continue
├─ State 4: FileIO.writeFile(...) → suspend  
└─ State 5: Return result → complete
```

### 2. HTTP Client Effect

```funk
// HTTP client effect
effect HTTP {
  get: {url: String} -> String <HTTP>
  post: {url: String, body: String} -> String <HTTP>
  put: {url: String, body: String} -> String <HTTP>
}

impl AsyncHTTP for HTTP {
  get = {url} -> do
    // Virtual thread suspends during network I/O
    let response = async_http_get {url = url}
    response.body
    
  post = {url, body} -> do
    let response = async_http_post {url = url, body = body}
    response.body
    
  put = {url, body} -> do
    let response = async_http_put {url = url, body = body}
    response.body
}

// Concurrent HTTP requests using structured concurrency
let fetch_multiple_apis = do
  // These run concurrently on different virtual threads
  let user_data = HTTP.get {url = "https://api.example.com/user/123"}        // VThread 1
  let settings = HTTP.get {url = "https://api.example.com/settings"}         // VThread 2  
  let profile = HTTP.get {url = "https://api.example.com/profile"}           // VThread 3
  
  // All requests complete before continuing
  let combined = combine_data {
    user = user_data, 
    settings = settings, 
    profile = profile
  }
  
  // Save combined result
  HTTP.post {
    url = "https://api.example.com/combined",
    body = serialize {data = combined}
  }
```

### 3. Database Effect

```kotlin
// Database effect implementation with connection pooling
class AsyncDatabaseHandler : AsyncEffectHandler {
    private val connectionPool = HikariDataSource(config)
    
    override suspend fun handle(operation: String, arguments: FunkValue): FunkValue {
        return when (operation) {
            "query" -> {
                val args = arguments as FunkRecord
                val sql = (args.fields["sql"] as FunkString).value
                
                // Virtual thread suspends during database I/O
                withContext(Dispatchers.IO) {
                    connectionPool.connection.use { conn ->
                        conn.prepareStatement(sql).use { stmt ->
                            val resultSet = stmt.executeQuery()
                            convertResultSetToFunk(resultSet)
                        }
                    }
                }
            }
            
            "transaction" -> {
                val args = arguments as FunkRecord
                val operations = args.fields["operations"] as FunkValue
                
                // Execute transaction with proper isolation
                withContext(Dispatchers.IO) {
                    connectionPool.connection.use { conn ->
                        conn.autoCommit = false
                        try {
                            val result = executeTransactionOperations(operations, conn)
                            conn.commit()
                            result
                        } catch (e: Exception) {
                            conn.rollback()
                            throw e
                        }
                    }
                }
            }
            
            else -> throw RuntimeException("Unknown Database operation: $operation")
        }
    }
    
    override fun canHandle(effectName: String): Boolean = effectName == "Database"
    override fun supportsCancellation(): Boolean = true
}
```

```funk
// Database operations with transactions
let process_orders = do
  let orders = Database.query {sql = "SELECT * FROM orders WHERE status = 'pending'"}
  
  let processed_orders = map {
    list = orders,
    f = {order} -> do
      // Each order processed in parallel virtual thread
      let validated = validate_order {order = order}
      let updated = Database.query {
        sql = "UPDATE orders SET status = 'processed' WHERE id = $(order.id)"
      }
      validated
  }
  
  // Batch commit transaction
  Database.transaction {
    operations = processed_orders
  }
```

## Structured Concurrency

### 1. Parallel Execution

```funk
// Structured concurrency effect
effect Concurrent {
  par: {computations: [() -> a]} -> [a] <Concurrent>
  race: {computations: [() -> a]} -> a <Concurrent>
  timeout: {duration: Int, computation: () -> a} -> Result<a> <Concurrent>
}

impl StructuredConcurrent for Concurrent {
  par = {computations} -> do
    // Execute all computations in parallel on virtual threads
    let futures = map {
      list = computations,
      f = {computation} -> VirtualThreadExecutor.submit {() -> computation {}}
    }
    
    // Wait for all to complete (structured concurrency)
    let results = map {
      list = futures, 
      f = {future} -> future.get {}
    }
    
    results
    
  race = {computations} -> do
    // Execute computations concurrently, return first result
    let futures = map {
      list = computations,
      f = {computation} -> VirtualThreadExecutor.submit {() -> computation {}}
    }
    
    // Use CompletableFuture.anyOf for racing
    let first_result = CompletableFuture.anyOf {futures = futures}.get {}
    
    // Cancel remaining futures
    for_each {list = futures, f = {future} -> future.cancel {mayInterruptIfRunning = true}}
    
    first_result
    
  timeout = {duration, computation} -> do
    let future = VirtualThreadExecutor.submitWithTimeout {
      timeout = Duration.ofMillis {millis = duration},
      task = computation
    }
    
    try
      Success {value = future.get {}}
    catch TimeoutException ->
      Error {message = "Computation timed out after $(intToString {val = duration})ms"}
}

// Usage example
let parallel_processing = do
  let results = Concurrent.par {computations = [
    {() -> heavy_computation_1 {}},
    {() -> heavy_computation_2 {}}, 
    {() -> heavy_computation_3 {}}
  ]}
  
  let combined = combine_results {results = results}
  
  // Race between saving to file vs uploading to cloud
  let storage_result = Concurrent.race {computations = [
    {() -> FileIO.writeFile {path = "result.json", content = combined}},
    {() -> HTTP.post {url = "https://api.example.com/upload", body = combined}}
  ]}
  
  storage_result
```

### 2. Error Handling and Cancellation

```funk
// Error handling in concurrent effects
let robust_processing = do
  let result = Concurrent.timeout {
    duration = 30000,  // 30 second timeout
    computation = {() -> do
      let data1 = HTTP.get {url = "https://slow-api.example.com/data1"}
      let data2 = HTTP.get {url = "https://slow-api.example.com/data2"}
      combine {data1 = data1, data2 = data2}
    }
  }
  
  match result is
    Success {value} -> 
      FileIO.writeFile {path = "success.json", content = serialize {data = value}}
    Error {message} ->
      Console.print {message = "Processing failed: $(message)"}
```

### 3. Resource Management

```kotlin
// Automatic resource cleanup for virtual threads
class ResourceManagedEffectHandler : AsyncEffectHandler {
    private val resources = ConcurrentHashMap<String, AutoCloseable>()
    
    override suspend fun handle(operation: String, arguments: FunkValue): FunkValue {
        return when (operation) {
            "acquire_resource" -> {
                val args = arguments as FunkRecord
                val resourceId = (args.fields["id"] as FunkString).value
                val resourceType = (args.fields["type"] as FunkString).value
                
                val resource = createResource(resourceType)
                resources[resourceId] = resource
                
                FunkString(resourceId)
            }
            
            "release_resource" -> {
                val args = arguments as FunkRecord  
                val resourceId = (args.fields["id"] as FunkString).value
                
                resources.remove(resourceId)?.close()
                FunkRecord(emptyMap())
            }
            
            "cleanup_all" -> {
                resources.values.forEach { it.close() }
                resources.clear()
                FunkRecord(emptyMap())
            }
            
            else -> throw RuntimeException("Unknown ResourceManager operation: $operation")
        }
    }
    
    // Automatic cleanup when virtual thread terminates
    override fun onThreadTermination() {
        resources.values.forEach { resource ->
            try {
                resource.close()
            } catch (e: Exception) {
                logger.warn("Failed to close resource: ${e.message}")
            }
        }
        resources.clear()
    }
}
```

## Performance Benefits

### 1. Scalability
- **Millions of virtual threads** can be created with minimal overhead
- **Automatic suspension** during I/O operations frees up OS threads
- **No thread pool exhaustion** issues with traditional thread pools
- **Efficient scheduling** by JVM rather than OS

### 2. Memory Efficiency
- Virtual threads use **~1KB of memory** each vs ~1MB for OS threads
- **Stack-on-demand** allocation - grows as needed
- **Shared carrier threads** for actual execution
- **Garbage collection friendly** stack management

### 3. Simplified Programming Model
- **No callback hell** - linear code with async execution
- **Structured concurrency** with proper cancellation and error propagation
- **Exception propagation** across async boundaries
- **Deterministic resource cleanup**

### 4. Performance Comparison

```funk
// Performance benchmark
let benchmark_concurrency = do
  let start_time = System.currentTimeMillis {}
  
  // Process 10,000 concurrent HTTP requests
  let requests = range {start = 1, end = 10000}
  let results = Concurrent.par {
    computations = map {
      list = requests,
      f = {i} -> {() -> HTTP.get {url = "https://httpbin.org/delay/1?id=$(intToString {val = i})"}}
    }
  }
  
  let end_time = System.currentTimeMillis {}
  let duration = end_time - start_time
  
  Console.print {message = "Processed $(intToString {val = length {list = results}}) requests in $(intToString {val = duration})ms"}
  
  // Typical results:
  // - OS Threads: Would exhaust thread pool at ~1000 requests
  // - Virtual Threads: Handles 10,000+ requests efficiently
  // - Memory usage: 10MB vs 10GB for OS threads
  // - CPU usage: Optimal utilization vs context switching overhead
```

## Integration with Roc-like Design

### 1. Pure Functions with Effect Annotations

```funk
// Pure function - no effects, no state machine
let calculate: {x: Int, y: Int} -> Int = {x, y} -> x * y + 1

// Function with effects - becomes state machine when called
let process_file: {path: String} -> String <FileIO, Console> = {path} -> do
  Console.print {message = "Processing $(path)"}               // Suspension point 1
  let content = FileIO.readFile {path = path}                   // Suspension point 2
  let processed = process_content {content = content}           // Pure computation
  Console.print {message = "Completed processing"}             // Suspension point 3
  processed

// Effect composition - multiple effects in one function
let full_pipeline: {input: String} -> String <FileIO, HTTP, Database, Console> = {input} -> do
  Console.print {message = "Starting pipeline"}                // Suspension point 1
  
  let config = FileIO.readFile {path = "config.json"}          // Suspension point 2
  let api_data = HTTP.get {url = "https://api.example.com/data"}  // Suspension point 3
  let db_result = Database.query {sql = "SELECT * FROM users"}  // Suspension point 4
  
  let combined = combine {
    config = config, 
    api = api_data, 
    db = db_result, 
    input = input
  }
  
  FileIO.writeFile {path = "output.json", content = combined}   // Suspension point 5
  Console.print {message = "Pipeline completed"}               // Suspension point 6
  
  combined
```

### 2. Effect Handlers as Dependency Injection

```funk
// Different implementations for different environments
impl ProductionFileIO for FileIO {
  readFile = {path} -> real_file_read {path = path}
  writeFile = {path, content} -> real_file_write {path = path, content = content}
}

impl TestFileIO for FileIO {
  readFile = {path} -> mock_file_read {path = path}
  writeFile = {path, content} -> mock_file_write {path = path, content = content}
}

impl InMemoryFileIO for FileIO {
  readFile = {path} -> in_memory_read {path = path}
  writeFile = {path, content} -> in_memory_write {path = path, content = content}
}

// Usage with different handlers
let test_environment = perform TestFileIO in process_files {}
let production_environment = perform ProductionFileIO in process_files {}
let benchmark_environment = perform InMemoryFileIO in process_files {}
```

### 3. Composable Effect Systems

```funk
// Compose multiple effect handlers
let complex_application = 
  perform ProductionFileIO, AsyncHTTP, PostgresDatabase, ConsoleLogger in do
    let config = load_config {}                    // Uses FileIO
    let user_data = fetch_user_data {config}       // Uses HTTP
    let stored_data = save_to_database {user_data} // Uses Database  
    log_completion {data = stored_data}            // Uses Console
    stored_data
```

## Future Enhancements

### 1. Automatic Parallelization
- **Detect independent effect calls** and parallelize automatically
- **Dependency analysis** to determine safe parallelization
- **Maintain deterministic execution** order when needed

### 2. Effect Fusion and Optimization
- **Combine multiple effects** into single operations (e.g., batch database queries)
- **Merge HTTP requests** to the same host
- **Pipeline I/O operations** for better throughput

### 3. Advanced State Machine Optimizations
- **State machine compilation** to bytecode for hot paths
- **State compression** for memory efficiency
- **Continuation optimization** to reduce allocation overhead

### 4. Debugging and Observability
- **State machine visualization** for debugging complex effect flows
- **Effect call tracing** with distributed tracing integration
- **Performance profiling** for async operations
- **Resource usage monitoring** for virtual threads

### 5. Native Image Support
- **Ahead-of-time compilation** of state machines
- **Static analysis** of effect usage patterns
- **Optimized virtual thread implementation** for native images

---

This state machine approach with Project Loom provides Funk with a powerful, scalable, and maintainable concurrency model that preserves the benefits of functional programming while achieving excellent performance characteristics.
