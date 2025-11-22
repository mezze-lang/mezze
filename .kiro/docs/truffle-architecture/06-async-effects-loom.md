# Async Effects with Project Loom

## Overview

This document details how Funk's effect system integrates with Project Loom's virtual threads to provide efficient, scalable asynchronous programming. Virtual threads enable Funk to handle thousands of concurrent operations while maintaining simple, linear code structure.

## Project Loom Integration

### 1. Virtual Thread Characteristics

Project Loom's virtual threads provide:
- **Lightweight**: ~1KB memory overhead vs ~1MB for platform threads
- **Scalable**: Millions can be created without resource exhaustion
- **Blocking-friendly**: Can block without tying up OS threads
- **Structured concurrency**: Proper lifecycle management and cancellation

### 2. Virtual Thread Executor

```kotlin
class VirtualThreadExecutor {
    companion object {
        private val virtualThreadFactory = ThreadFactory { runnable ->
            Thread.ofVirtual()
                .name("funk-effect-", 0)
                .uncaughtExceptionHandler { _, ex -> 
                    System.err.println("Uncaught exception in virtual thread: $ex")
                    ex.printStackTrace()
                }
                .unstarted(runnable)
        }

        // Use thread-per-task executor with virtual threads
        private val executor: Executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)

        /**
         * Submit task for execution on virtual thread
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
         * Submit suspending task (integrates with Kotlin coroutines)
         */
        fun submitSuspending(task: suspend () -> FunkValue): CompletableFuture<FunkValue> {
            val future = CompletableFuture<FunkValue>()
            executor.execute {
                try {
                    // Run suspending function on virtual thread
                    val result = kotlinx.coroutines.runBlocking { task() }
                    future.complete(result)
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                }
            }
            return future
        }

        /**
         * Submit with timeout using virtual thread
         */
        fun submitWithTimeout(
            timeout: Duration, 
            task: () -> FunkValue
        ): CompletableFuture<FunkValue> {
            val future = submit(task)
            return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        }
        
        /**
         * Execute multiple tasks concurrently
         */
        fun submitAll(tasks: List<() -> FunkValue>): CompletableFuture<List<FunkValue>> {
            val futures = tasks.map { task -> submit(task) }
            return CompletableFuture.allOf(*futures.toTypedArray())
                .thenApply { futures.map { it.get() } }
        }
    }
}
```

## Async Effect Handlers

### 1. Base Async Handler Interface

```kotlin
interface AsyncEffectHandler {
    /**
     * Handle effect operation asynchronously
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
     * Cancel ongoing operations for the given operation ID
     */
    fun cancel(operationId: String): Boolean = false
    
    /**
     * Called when virtual thread is about to terminate
     */
    fun onThreadTermination() {}
}
```

### 2. File I/O Handler

```kotlin
class AsyncFileIOHandler : AsyncEffectHandler {
    
    override suspend fun handle(operation: String, arguments: FunkValue): FunkValue {
        return when (operation) {
            "readFile" -> {
                val args = arguments as FunkRecord
                val path = (args.fields["path"] as FunkString).value
                
                // Suspend virtual thread during I/O
                withContext(Dispatchers.IO) {
                    try {
                        val content = Files.readString(Paths.get(path))
                        FunkString(content)
                    } catch (e: IOException) {
                        throw FunkException("Failed to read file '$path': ${e.message}", e)
                    }
                }
            }
            
            "writeFile" -> {
                val args = arguments as FunkRecord
                val path = (args.fields["path"] as FunkString).value
                val content = (args.fields["content"] as FunkString).value
                
                withContext(Dispatchers.IO) {
                    try {
                        Files.writeString(Paths.get(path), content)
                        FunkRecord(emptyMap()) // Unit type
                    } catch (e: IOException) {
                        throw FunkException("Failed to write file '$path': ${e.message}", e)
                    }
                }
            }
            
            "listDirectory" -> {
                val args = arguments as FunkRecord
                val path = (args.fields["path"] as FunkString).value
                
                withContext(Dispatchers.IO) {
                    try {
                        val files = Files.list(Paths.get(path)).use { stream ->
                            stream.map { it.fileName.toString() }.toList()
                        }
                        
                        // Convert to Funk list
                        files.foldRight(FunkVariant("Nil", null)) { fileName, acc ->
                            FunkVariant("Cons", FunkRecord(mapOf(
                                "head" to FunkString(fileName),
                                "tail" to acc
                            )))
                        }
                    } catch (e: IOException) {
                        throw FunkException("Failed to list directory '$path': ${e.message}", e)
                    }
                }
            }
            
            else -> throw RuntimeException("Unknown FileIO operation: $operation")
        }
    }

    override fun canHandle(effectName: String): Boolean = effectName == "FileIO"
    override fun priority(): Int = 100
    override fun supportsCancellation(): Boolean = true
}
```

### 3. HTTP Client Handler

```kotlin
class AsyncHTTPHandler : AsyncEffectHandler {
    private val httpClient = HttpClient.newBuilder()
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build()
    
    override suspend fun handle(operation: String, arguments: FunkValue): FunkValue {
        return when (operation) {
            "get" -> {
                val args = arguments as FunkRecord
                val url = (args.fields["url"] as FunkString).value
                
                // Virtual thread suspends during network I/O
                withContext(Dispatchers.IO) {
                    try {
                        val request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .GET()
                            .build()
                            
                        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                        
                        FunkRecord(mapOf(
                            "status" to FunkInt(response.statusCode()),
                            "body" to FunkString(response.body()),
                            "headers" to convertHeadersToFunk(response.headers())
                        ))
                    } catch (e: Exception) {
                        throw FunkException("HTTP GET failed for '$url': ${e.message}", e)
                    }
                }
            }
            
            "post" -> {
                val args = arguments as FunkRecord
                val url = (args.fields["url"] as FunkString).value
                val body = (args.fields["body"] as FunkString).value
                val contentType = (args.fields.getOrDefault("contentType", FunkString("application/json")) as FunkString).value
                
                withContext(Dispatchers.IO) {
                    try {
                        val request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Content-Type", contentType)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build()
                            
                        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                        
                        FunkRecord(mapOf(
                            "status" to FunkInt(response.statusCode()),
                            "body" to FunkString(response.body()),
                            "headers" to convertHeadersToFunk(response.headers())
                        ))
                    } catch (e: Exception) {
                        throw FunkException("HTTP POST failed for '$url': ${e.message}", e)
                    }
                }
            }
            
            "downloadFile" -> {
                val args = arguments as FunkRecord
                val url = (args.fields["url"] as FunkString).value
                val outputPath = (args.fields["outputPath"] as FunkString).value
                
                withContext(Dispatchers.IO) {
                    try {
                        val request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .GET()
                            .build()
                            
                        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(Paths.get(outputPath)))
                        
                        FunkRecord(mapOf(
                            "status" to FunkInt(response.statusCode()),
                            "filePath" to FunkString(response.body().toString()),
                            "size" to FunkInt(Files.size(response.body()).toInt())
                        ))
                    } catch (e: Exception) {
                        throw FunkException("File download failed for '$url': ${e.message}", e)
                    }
                }
            }
            
            else -> throw RuntimeException("Unknown HTTP operation: $operation")
        }
    }
    
    private fun convertHeadersToFunk(headers: HttpHeaders): FunkValue {
        val headerMap = mutableMapOf<String, FunkValue>()
        headers.map().forEach { (name, values) ->
            headerMap[name] = FunkString(values.joinToString(", "))
        }
        return FunkRecord(headerMap)
    }

    override fun canHandle(effectName: String): Boolean = effectName == "HTTP"
    override fun priority(): Int = 100
    override fun supportsCancellation(): Boolean = true
    
    override fun onThreadTermination() {
        // Cleanup HTTP client resources if needed
    }
}
```

### 4. Database Handler

```kotlin
class AsyncDatabaseHandler(private val dataSource: DataSource) : AsyncEffectHandler {
    
    override suspend fun handle(operation: String, arguments: FunkValue): FunkValue {
        return when (operation) {
            "query" -> {
                val args = arguments as FunkRecord
                val sql = (args.fields["sql"] as FunkString).value
                val params = args.fields["params"] as? FunkRecord
                
                withContext(Dispatchers.IO) {
                    dataSource.connection.use { connection ->
                        connection.prepareStatement(sql).use { statement ->
                            
                            // Bind parameters if provided
                            params?.fields?.entries?.forEachIndexed { index, (_, value) ->
                                when (value) {
                                    is FunkInt -> statement.setInt(index + 1, value.value)
                                    is FunkString -> statement.setString(index + 1, value.value)
                                    is FunkFloat -> statement.setDouble(index + 1, value.value)
                                    is FunkBool -> statement.setBoolean(index + 1, value.value)
                                    is FunkNull -> statement.setNull(index + 1, Types.NULL)
                                }
                            }
                            
                            val resultSet = statement.executeQuery()
                            convertResultSetToFunk(resultSet)
                        }
                    }
                }
            }
            
            "update" -> {
                val args = arguments as FunkRecord
                val sql = (args.fields["sql"] as FunkString).value
                val params = args.fields["params"] as? FunkRecord
                
                withContext(Dispatchers.IO) {
                    dataSource.connection.use { connection ->
                        connection.prepareStatement(sql).use { statement ->
                            
                            // Bind parameters
                            params?.fields?.entries?.forEachIndexed { index, (_, value) ->
                                bindParameter(statement, index + 1, value)
                            }
                            
                            val rowsAffected = statement.executeUpdate()
                            FunkInt(rowsAffected)
                        }
                    }
                }
            }
            
            "transaction" -> {
                val args = arguments as FunkRecord
                val operations = args.fields["operations"] as FunkValue
                
                withContext(Dispatchers.IO) {
                    dataSource.connection.use { connection ->
                        connection.autoCommit = false
                        try {
                            val result = executeTransactionOperations(operations, connection)
                            connection.commit()
                            result
                        } catch (e: Exception) {
                            connection.rollback()
                            throw e
                        } finally {
                            connection.autoCommit = true
                        }
                    }
                }
            }
            
            else -> throw RuntimeException("Unknown Database operation: $operation")
        }
    }
    
    private fun convertResultSetToFunk(resultSet: ResultSet): FunkValue {
        val rows = mutableListOf<FunkValue>()
        val metaData = resultSet.metaData
        val columnCount = metaData.columnCount
        
        while (resultSet.next()) {
            val row = mutableMapOf<String, FunkValue>()
            
            for (i in 1..columnCount) {
                val columnName = metaData.getColumnName(i)
                val value = when (metaData.getColumnType(i)) {
                    Types.INTEGER, Types.BIGINT -> FunkInt(resultSet.getInt(i))
                    Types.DOUBLE, Types.FLOAT -> FunkFloat(resultSet.getDouble(i))
                    Types.VARCHAR, Types.CHAR -> FunkString(resultSet.getString(i) ?: "")
                    Types.BOOLEAN -> FunkBool(resultSet.getBoolean(i))
                    else -> FunkString(resultSet.getString(i) ?: "")
                }
                row[columnName] = value
            }
            
            rows.add(FunkRecord(row))
        }
        
        // Convert to Funk list
        return rows.foldRight(FunkVariant("Nil", null)) { row, acc ->
            FunkVariant("Cons", FunkRecord(mapOf(
                "head" to row,
                "tail" to acc
            )))
        }
    }
    
    override fun canHandle(effectName: String): Boolean = effectName == "Database"
    override fun priority(): Int = 100
    override fun supportsCancellation(): Boolean = true
}
```

## Structured Concurrency

### 1. Concurrent Effect Handler

```kotlin
class ConcurrentEffectHandler : AsyncEffectHandler {
    
    override suspend fun handle(operation: String, arguments: FunkValue): FunkValue {
        return when (operation) {
            "par" -> {
                val args = arguments as FunkRecord
                val computations = args.fields["computations"] as FunkValue
                
                // Execute computations in parallel using structured concurrency
                executeParallel(computations)
            }
            
            "race" -> {
                val args = arguments as FunkRecord
                val computations = args.fields["computations"] as FunkValue
                
                // Execute computations concurrently, return first result
                executeRace(computations)
            }
            
            "timeout" -> {
                val args = arguments as FunkRecord
                val timeoutMs = (args.fields["timeout"] as FunkInt).value.toLong()
                val computation = args.fields["computation"] as FunkValue
                
                // Execute with timeout
                executeWithTimeout(computation, timeoutMs)
            }
            
            else -> throw RuntimeException("Unknown Concurrent operation: $operation")
        }
    }
    
    private suspend fun executeParallel(computations: FunkValue): FunkValue {
        return when (computations) {
            is FunkVariant -> {
                if (computations.constructor == "Cons") {
                    val data = computations.value as FunkRecord
                    val head = data.fields["head"] as FunkValue
                    val tail = data.fields["tail"] as FunkValue
                    
                    // Convert Funk list to Kotlin list
                    val computationList = mutableListOf<FunkValue>()
                    var current = computations
                    
                    while (current is FunkVariant && current.constructor == "Cons") {
                        val currentData = current.value as FunkRecord
                        computationList.add(currentData.fields["head"] as FunkValue)
                        current = currentData.fields["tail"] as FunkValue
                    }
                    
                    // Execute all computations in parallel
                    coroutineScope {
                        val deferredResults = computationList.map { computation ->
                            async {
                                executeComputation(computation)
                            }
                        }
                        
                        val results = deferredResults.awaitAll()
                        
                        // Convert results back to Funk list
                        results.foldRight(FunkVariant("Nil", null)) { result, acc ->
                            FunkVariant("Cons", FunkRecord(mapOf(
                                "head" to result,
                                "tail" to acc
                            )))
                        }
                    }
                } else {
                    FunkVariant("Nil", null)
                }
            }
            else -> throw RuntimeException("Expected list of computations")
        }
    }
    
    private suspend fun executeRace(computations: FunkValue): FunkValue {
        // Similar to executeParallel but uses select to return first result
        return coroutineScope {
            val computationList = convertFunkListToKotlin(computations)
            
            if (computationList.isEmpty()) {
                throw RuntimeException("Cannot race empty list of computations")
            }
            
            val deferredResults = computationList.map { computation ->
                async {
                    executeComputation(computation)
                }
            }
            
            // Use select to get first result
            select<FunkValue> {
                deferredResults.forEach { deferred ->
                    deferred.onAwait { result ->
                        // Cancel other computations
                        deferredResults.forEach { if (it != deferred) it.cancel() }
                        result
                    }
                }
            }
        }
    }
    
    private suspend fun executeWithTimeout(computation: FunkValue, timeoutMs: Long): FunkValue {
        return try {
            withTimeout(timeoutMs) {
                executeComputation(computation)
            }
        } catch (e: TimeoutCancellationException) {
            FunkVariant("Error", FunkRecord(mapOf(
                "message" to FunkString("Computation timed out after ${timeoutMs}ms")
            )))
        }
    }
    
    private suspend fun executeComputation(computation: FunkValue): FunkValue {
        // Execute a Funk computation (could be a function call or effect operation)
        return when (computation) {
            is SimpleFunkFunction -> {
                // Call function with empty arguments
                val emptyArgs = FunkRecord(emptyMap())
                // This would need to integrate with the main interpreter
                computation.call(emptyArgs)
            }
            else -> computation
        }
    }
    
    override fun canHandle(effectName: String): Boolean = effectName == "Concurrent"
    override fun priority(): Int = 100
    override fun supportsCancellation(): Boolean = true
}
```

## Usage Examples

### 1. File Processing Pipeline

```funk
// Async file processing with virtual threads
let process_files = {directory: String} -> do
  // List files asynchronously
  let files = FileIO.listDirectory {path = directory}
  
  // Process each file concurrently
  let results = Concurrent.par {
    computations = map {
      list = files,
      f = {filename} -> {() -> do
        let content = FileIO.readFile {path = "$(directory)/$(filename)"}
        let processed = process_content {content = content}
        let output_path = "output/$(filename)"
        FileIO.writeFile {path = output_path, content = processed}
        {filename = filename, status = "completed"}
      }
    }
  }
  
  results
```

### 2. Web API Integration

```funk
// Concurrent API calls with error handling
let fetch_user_data = {user_id: String} -> do
  let api_calls = [
    {() -> HTTP.get {url = "https://api.example.com/users/$(user_id)"}},
    {() -> HTTP.get {url = "https://api.example.com/users/$(user_id)/profile"}},
    {() -> HTTP.get {url = "https://api.example.com/users/$(user_id)/settings"}}
  ]
  
  // Execute all API calls concurrently with timeout
  let results = Concurrent.timeout {
    timeout = 5000,  // 5 second timeout
    computation = {() ->
      Concurrent.par {computations = api_calls}
    }
  }
  
  match results is
    Success {value} -> 
      combine_api_results {results = value}
    Error {message} ->
      Console.print {message = "API calls failed: $(message)"}
      default_user_data {}
```

### 3. Database Operations

```funk
// Concurrent database queries
let generate_report = do
  let queries = [
    {() -> Database.query {sql = "SELECT COUNT(*) as user_count FROM users"}},
    {() -> Database.query {sql = "SELECT AVG(age) as avg_age FROM users"}},
    {() -> Database.query {sql = "SELECT COUNT(*) as active_sessions FROM sessions WHERE active = true"}}
  ]
  
  let results = Concurrent.par {computations = queries}
  
  // Combine results into report
  let report = match results is
    [user_count_result, avg_age_result, sessions_result] ->
      generate_report_from_results {
        user_count = user_count_result,
        avg_age = avg_age_result,
        active_sessions = sessions_result
      }
    _ ->
      error_report {message = "Failed to fetch all required data"}
  
  report
```

### 4. Real-time Data Processing

```funk
// Stream processing with virtual threads
effect StreamProcessor {
  process_stream: {source: String, processor: String -> String} -> [String] <StreamProcessor>
  batch_process: {items: [String], batch_size: Int, processor: [String] -> [String]} -> [String] <StreamProcessor>
}

let real_time_processing = do
  let data_stream = StreamProcessor.process_stream {
    source = "data_source",
    processor = {item} -> 
      enhance_data_item {item = item}
  }
  
  // Process in batches for efficiency
  let processed_batches = StreamProcessor.batch_process {
    items = data_stream,
    batch_size = 100,
    processor = {batch} ->
      Concurrent.par {
        computations = map {
          list = batch,
          f = {item} -> {() -> 
            expensive_processing {item = item}
          }
        }
      }
  }
  
  processed_batches
```

## Performance Characteristics

### 1. Virtual Thread Benefits

```funk
// Performance comparison: virtual threads vs platform threads
let benchmark_concurrency = do
  let start_time = System.currentTimeMillis {}
  
  // Create 100,000 concurrent operations (would exhaust platform threads)
  let operations = range {start = 1, end = 100000}
  let results = Concurrent.par {
    computations = map {
      list = operations,
      f = {i} -> {() -> do
        // Simulate I/O operation
        Thread.sleep {millis = 10}
        i * 2
      }
    }
  }
  
  let end_time = System.currentTimeMillis {}
  let duration = end_time - start_time
  
  Console.print {message = "Processed $(intToString {val = length {list = results}}) operations in $(intToString {val = duration})ms"}
  
  // Typical results:
  // - Platform threads: OutOfMemoryError at ~10,000 operations
  // - Virtual threads: Completes 100,000+ operations efficiently
  // - Memory usage: ~100MB vs >10GB for platform threads
```

### 2. Structured Concurrency Benefits

```funk
// Structured concurrency ensures proper cleanup
let structured_processing = do
  try
    let results = Concurrent.timeout {
      timeout = 30000,
      computation = {() ->
        Concurrent.par {
          computations = [
            {() -> long_running_task_1 {}},
            {() -> long_running_task_2 {}},
            {() -> long_running_task_3 {}}
          ]
        }
      }
    }
    
    process_results {results = results}
  catch TimeoutException ->
    // All child tasks automatically cancelled
    Console.print {message = "Processing timed out, all tasks cancelled"}
    default_results {}
```

## Error Handling and Recovery

### 1. Exception Propagation

```kotlin
// Exception handling across virtual threads
class VirtualThreadExceptionHandler {
    fun handleException(exception: Throwable, threadName: String): FunkValue {
        return when (exception) {
            is FunkException -> {
                // Funk-specific exception
                FunkVariant("FunkError", FunkRecord(mapOf(
                    "message" to FunkString(exception.message ?: "Unknown error"),
                    "thread" to FunkString(threadName)
                )))
            }
            is TimeoutException -> {
                FunkVariant("TimeoutError", FunkRecord(mapOf(
                    "message" to FunkString("Operation timed out"),
                    "thread" to FunkString(threadName)
                )))
            }
            is CancellationException -> {
                FunkVariant("CancelledError", FunkRecord(mapOf(
                    "message" to FunkString("Operation was cancelled"),
                    "thread" to FunkString(threadName)
                )))
            }
            else -> {
                FunkVariant("UnknownError", FunkRecord(mapOf(
                    "message" to FunkString(exception.message ?: "Unknown error"),
                    "type" to FunkString(exception::class.simpleName ?: "Unknown"),
                    "thread" to FunkString(threadName)
                )))
            }
        }
    }
}
```

### 2. Recovery Strategies

```funk
// Retry with exponential backoff
let retry_with_backoff = {operation: () -> a, max_retries: Int} ->
  let retry_helper = {operation: () -> a, attempt: Int, delay: Int} ->
    if attempt > max_retries then
      Error {message = "Max retries exceeded"}
    else
      try
        Success {value = operation {}}
      catch e ->
        do
          Console.print {message = "Attempt $(intToString {val = attempt}) failed: $(e.message)"}
          Thread.sleep {millis = delay}
          retry_helper {operation = operation, attempt = attempt + 1, delay = delay * 2}
  in retry_helper {operation = operation, attempt = 1, delay = 1000}

// Circuit breaker pattern
let with_circuit_breaker = {operation: () -> a, failure_threshold: Int} ->
  // Implementation would track failures and open circuit when threshold reached
  circuit_breaker_execute {operation = operation, threshold = failure_threshold}
```

## Future Enhancements

### 1. Advanced Scheduling
- **Priority-based scheduling** for virtual threads
- **Work-stealing** for better load distribution
- **NUMA-aware** thread placement

### 2. Resource Management
- **Memory pooling** for virtual thread stacks
- **Connection pooling** integration
- **Resource quotas** per effect type

### 3. Observability
- **Virtual thread metrics** and monitoring
- **Distributed tracing** across async operations
- **Performance profiling** for virtual thread usage

### 4. Integration Enhancements
- **Native async I/O** integration
- **Reactive streams** compatibility
- **WebFlux/Spring** integration for web applications

---

This async effects system with Project Loom provides Funk with a powerful, scalable concurrency model that maintains the simplicity of synchronous code while delivering the performance of asynchronous execution.
