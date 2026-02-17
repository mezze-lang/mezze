package com.mezze.language.interpreter.concurrency

import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Core concurrency manager built on Project Loom virtual threads.
 * Provides structured concurrency, scoped values, and virtual thread management.
 */
class LoomConcurrencyManager {
    private val threadCounter = AtomicLong(0)
    private val activeScopes = ConcurrentHashMap<String, ConcurrencyScope>()
    
    companion object {
        // ThreadLocal for execution context (will upgrade to ScopedValue when available)
        private val EXECUTION_CONTEXT = ThreadLocal<ExecutionContext>()
        
        @JvmStatic
        fun getCurrentContext(): ExecutionContext? = EXECUTION_CONTEXT.get()
        
        @JvmStatic
        internal fun setCurrentContext(context: ExecutionContext?) {
            if (context != null) {
                EXECUTION_CONTEXT.set(context)
            } else {
                EXECUTION_CONTEXT.remove()
            }
        }
    }
    
    /**
     * Spawn a virtual thread with proper context management
     */
    fun spawnVirtualThread(
        name: String? = null,
        task: Runnable
    ): Thread {
        val threadId = threadCounter.incrementAndGet()
        val threadName = name ?: "mezze-virtual-$threadId"
        
        // Capture parent context before spawning thread
        val parentContext = getCurrentContext()
        
        return Thread.ofVirtual()
            .name(threadName)
            .start {
                try {
                    // Create execution context with captured parent
                    val context = ExecutionContext(
                        threadId = Thread.currentThread().threadId(),
                        threadName = threadName,
                        parentContext = parentContext,
                        startTime = System.nanoTime()
                    )
                    
                    // Run task with thread-local context
                    setCurrentContext(context)
                    try {
                        task.run()
                    } finally {
                        setCurrentContext(null)
                    }
                } catch (e: Exception) {
                    // Log error but don't let it escape
                    System.err.println("Error in virtual thread $threadName: ${e.message}")
                    throw e
                }
            }
    }
    
    /**
     * Create a structured concurrency scope that automatically manages child threads
     */
    fun <T> withStructuredScope(
        name: String = "scope-${System.nanoTime()}",
        timeout: Duration? = null,
        action: (StructuredConcurrencyScope) -> T
    ): T {
        val scope = ConcurrencyScope(name, timeout)
        activeScopes[name] = scope
        
        return try {
            // Use ExecutorService for now (will upgrade to StructuredTaskScope when available)
            val executor = Executors.newVirtualThreadPerTaskExecutor()
            try {
                val structuredScope = StructuredConcurrencyScope(executor, scope)
                
                // Execute the action
                val result = action(structuredScope)
                
                // Handle timeout for waiting on tasks
                if (timeout != null) {
                    val startTime = System.nanoTime()
                    val futures = scope.getAllFutures()
                    
                    for (future in futures) {
                        val elapsed = Duration.ofNanos(System.nanoTime() - startTime)
                        val remaining = timeout.minus(elapsed)
                        
                        if (remaining.isNegative || remaining.isZero) {
                            scope.cancel()
                            executor.shutdownNow()
                            throw TimeoutException("Structured scope timed out after ${timeout.toMillis()}ms")
                        }
                        
                        try {
                            future.get(remaining.toMillis(), TimeUnit.MILLISECONDS)
                        } catch (e: TimeoutException) {
                            scope.cancel()
                            executor.shutdownNow()
                            throw TimeoutException("Structured scope timed out after ${timeout.toMillis()}ms")
                        }
                    }
                } else {
                    // Wait for all tasks to complete, propagating exceptions properly
                    val futures = scope.getAllFutures()
                    for (future in futures) {
                        try {
                            future.get()
                        } catch (e: ExecutionException) {
                            // Unwrap ExecutionException and throw the actual cause
                            throw e.cause ?: e
                        } catch (e: CancellationException) {
                            // Task was cancelled, continue to next
                            continue
                        }
                    }
                }

                result
            } finally {
                executor.shutdown()
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow()
                }
            }
        } finally {
            activeScopes.remove(name)
            scope.cleanup()
        }
    }
    
    /**
     * Execute multiple tasks concurrently and wait for all to complete
     */
    fun <T> executeAll(tasks: List<Callable<T>>): List<T> {
        return withStructuredScope("executeAll") { scope ->
            val futures = tasks.map { task ->
                scope.submit(task)
            }
            futures.map { it.get() }
        }
    }
    
    /**
     * Execute multiple tasks and return the result of the first to complete
     */
    fun <T> executeRace(tasks: List<Callable<T>>): T {
        return withStructuredScope("executeRace") { scope ->
            val futures = tasks.map { task ->
                scope.submit(task)
            }
            
            // Use CompletableFuture.anyOf to get the first completed result
            val completableFutures = futures.map { future ->
                CompletableFuture.supplyAsync { future.get() }
            }
            
            @Suppress("UNCHECKED_CAST")
            return@withStructuredScope CompletableFuture.anyOf(*completableFutures.toTypedArray()).get() as T
        }
    }
    
    /**
     * Get statistics about virtual thread usage
     */
    fun getThreadStatistics(): ThreadStatistics {
        val activeThreads = Thread.getAllStackTraces().keys
            .count { it.isVirtual }
        
        return ThreadStatistics(
            totalVirtualThreadsCreated = threadCounter.get(),
            activeVirtualThreads = activeThreads,
            activeScopes = activeScopes.size,
            scopeNames = activeScopes.keys.toList()
        )
    }
    
    /**
     * Cancel all operations in a specific scope
     */
    fun cancelScope(scopeName: String) {
        activeScopes[scopeName]?.cancel()
    }
    

    
    /**
     * Shutdown the concurrency manager and clean up resources
     */
    fun shutdown() {
        activeScopes.values.forEach { it.cancel() }
        activeScopes.clear()
    }
}

/**
 * Execution context that travels with virtual threads via scoped values
 */
data class ExecutionContext(
    val threadId: Long,
    val threadName: String,
    val parentContext: ExecutionContext? = null,
    val startTime: Long = System.nanoTime(),
    val metadata: Map<String, Any> = emptyMap()
) {
    fun withMetadata(key: String, value: Any): ExecutionContext =
        copy(metadata = metadata + (key to value))
    
    fun getElapsedTime(): Duration =
        Duration.ofNanos(System.nanoTime() - startTime)
}

/**
 * Wrapper around ExecutorService providing a structured concurrency API
 */
class StructuredConcurrencyScope(
    private val executor: ExecutorService,
    private val scope: ConcurrencyScope
) {
    fun <T> submit(task: Callable<T>): Future<T> {
        val future = executor.submit(task)
        scope.addTask(future)
        return future
    }
    
    fun <T> submit(task: () -> T): Future<T> {
        return submit(Callable { task() })
    }
    
    fun submitRunnable(task: Runnable): Future<*> {
        val future = executor.submit(task)
        scope.addTask(future)
        return future
    }
    
    fun <T> submitWithContext(
        context: ExecutionContext,
        task: Callable<T>
    ): Future<T> {
        return submit(Callable {
            LoomConcurrencyManager.setCurrentContext(context)
            try {
                task.call()
            } finally {
                LoomConcurrencyManager.setCurrentContext(null)
            }
        })
    }
}

/**
 * Internal scope management for tracking and cleanup
 */
class ConcurrencyScope(
    val name: String,
    val timeout: Duration? = null
) {
    private val tasks = ConcurrentHashMap<Future<*>, Long>()
    private val cancelled = AtomicReference(false)
    private val startTime = System.nanoTime()
    
    fun <T> submitCallable(task: java.util.concurrent.Callable<T>): java.util.concurrent.Future<T> {
        val executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        val future = executor.submit(task)
        addTask(future)
        return future
    }
    
    fun addTask(future: Future<*>) {
        if (!cancelled.get()) {
            tasks[future] = System.nanoTime()
        }
    }
    
    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            tasks.keys.forEach { future ->
                future.cancel(true)
            }
            tasks.clear()
        }
    }
    
    fun cleanup() {
        // Remove completed tasks
        tasks.keys.removeIf { it.isDone }
    }
    
    fun isExpired(): Boolean {
        return timeout?.let { 
            Duration.ofNanos(System.nanoTime() - startTime) > it 
        } ?: false
    }
    
    fun getActiveTasks(): Int = tasks.size
    
    fun getAllFutures(): List<Future<*>> = tasks.keys.toList()
}

/**
 * Statistics about virtual thread usage
 */
data class ThreadStatistics(
    val totalVirtualThreadsCreated: Long,
    val activeVirtualThreads: Int,
    val activeScopes: Int,
    val scopeNames: List<String>
)