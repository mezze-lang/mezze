package com.funk.language.interpreter.concurrency

import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.effects.EffectContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility functions and extensions for concurrency testing
 */

/**
 * Extension to create EffectContext with variables
 */
fun EffectContext.withVariables(variables: Map<String, FunkValue>): EffectContext {
    return this.withVariables(variables)
}

/**
 * Extension to get variable from EffectContext
 */
fun EffectContext.getVariable(name: String): FunkValue? {
    return this.getVariable(name)
}

/**
 * Utility class for managing test execution contexts
 */
class TestExecutionContext {
    private val variables = ConcurrentHashMap<String, FunkValue>()
    private val executionOrder = mutableListOf<String>()
    
    fun setVariable(name: String, value: FunkValue) {
        variables[name] = value
    }
    
    fun getVariable(name: String): FunkValue? {
        return variables[name]
    }
    
    fun recordExecution(operationId: String) {
        synchronized(executionOrder) {
            executionOrder.add(operationId)
        }
    }
    
    fun getExecutionOrder(): List<String> {
        return synchronized(executionOrder) {
            executionOrder.toList()
        }
    }
    
    fun clear() {
        variables.clear()
        synchronized(executionOrder) {
            executionOrder.clear()
        }
    }
}

/**
 * Utility for creating test effect operations
 */
object EffectOperationTestFactory {
    
    fun createIOOperation(
        id: String,
        readVars: Set<String> = emptySet(),
        writeVars: Set<String> = emptySet(),
        durationMs: Long = 100,
        result: FunkValue
    ): EffectOperation {
        return EffectOperation(
            id = id,
            effectType = "IO",
            readVariables = readVars,
            writeVariables = writeVars,
            operation = {
                Thread.sleep(durationMs)
                result
            },
            estimatedDuration = java.time.Duration.ofMillis(durationMs)
        )
    }
    
    fun createComputeOperation(
        id: String,
        readVars: Set<String> = emptySet(),
        writeVars: Set<String> = emptySet(),
        durationMs: Long = 50,
        result: FunkValue
    ): EffectOperation {
        return EffectOperation(
            id = id,
            effectType = "Compute",
            readVariables = readVars,
            writeVariables = writeVars,
            operation = {
                Thread.sleep(durationMs)
                result
            },
            estimatedDuration = java.time.Duration.ofMillis(durationMs)
        )
    }
    
    fun createNetworkOperation(
        id: String,
        readVars: Set<String> = emptySet(),
        writeVars: Set<String> = emptySet(),
        durationMs: Long = 200,
        result: FunkValue
    ): EffectOperation {
        return EffectOperation(
            id = id,
            effectType = "Network",
            readVariables = readVars,
            writeVariables = writeVars,
            operation = {
                Thread.sleep(durationMs)
                result
            },
            estimatedDuration = java.time.Duration.ofMillis(durationMs)
        )
    }
}

/**
 * Performance measurement utilities
 */
object PerformanceTestUtils {
    
    data class BenchmarkResult(
        val executionTimeMs: Long,
        val throughput: Double,
        val speedup: Double,
        val efficiency: Double
    )
    
    fun measureExecution(operation: () -> Unit): Long {
        val startTime = System.currentTimeMillis()
        operation()
        return System.currentTimeMillis() - startTime
    }
    
    fun benchmarkParallelVsSequential(
        operations: List<EffectOperation>,
        concurrencyManager: LoomConcurrencyManager,
        scheduler: SmartEffectScheduler
    ): BenchmarkResult {
        // Measure sequential execution
        val sequentialTime = measureExecution {
            operations.forEach { it.operation() }
        }
        
        // Measure parallel execution
        val parallelTime = measureExecution {
            val block = EffectBlock(operations, EffectContext.empty())
            val plan = scheduler.analyzeEffectBlock(block)
            scheduler.executeWithParallelism(plan)
        }
        
        val speedup = sequentialTime.toDouble() / parallelTime
        val efficiency = speedup / operations.size
        val throughput = operations.size.toDouble() / (parallelTime / 1000.0)
        
        return BenchmarkResult(
            executionTimeMs = parallelTime,
            throughput = throughput,
            speedup = speedup,
            efficiency = efficiency
        )
    }
    
    fun warmupJVM(iterations: Int = 1000) {
        // Warm up the JVM for more accurate benchmarks
        repeat(iterations) {
            val list = (1..100).map { it * 2 }
            list.sum()
        }
    }
}

/**
 * Concurrency validation utilities
 */
object ConcurrencyValidationUtils {
    
    fun validateThreadSafety(
        operation: () -> Unit,
        threadCount: Int = 10,
        iterationsPerThread: Int = 100
    ): Boolean {
        val errors = ConcurrentHashMap.newKeySet<Exception>()
        val threads = mutableListOf<Thread>()
        
        repeat(threadCount) { threadIndex ->
            val thread = Thread {
                repeat(iterationsPerThread) {
                    try {
                        operation()
                    } catch (e: Exception) {
                        errors.add(e)
                    }
                }
            }
            threads.add(thread)
            thread.start()
        }
        
        threads.forEach { it.join() }
        
        return errors.isEmpty()
    }
    
    fun validateNoDeadlocks(
        operations: List<() -> Unit>,
        timeoutMs: Long = 5000
    ): Boolean {
        val threads = operations.map { operation ->
            Thread {
                operation()
            }
        }
        
        threads.forEach { it.start() }
        
        val allCompleted = threads.all { thread ->
            thread.join(timeoutMs)
            !thread.isAlive
        }
        
        // Force cleanup of any remaining threads
        threads.filter { it.isAlive }.forEach { it.interrupt() }
        
        return allCompleted
    }
    
    fun validateResourceCleanup(
        resourceCreator: () -> AutoCloseable,
        resourceUser: (AutoCloseable) -> Unit
    ): Boolean {
        var resourceClosed = false
        
        val resource = object : AutoCloseable {
            private val delegate = resourceCreator()
            
            override fun close() {
                delegate.close()
                resourceClosed = true
            }
        }
        
        try {
            resource.use { resourceUser(it) }
        } catch (e: Exception) {
            // Expected in some test scenarios
        }
        
        return resourceClosed
    }
}

/**
 * Mock implementations for testing
 */
class MockEffectHandler(
    override val effectName: String,
    private val operationResults: Map<String, FunkValue>
) : com.funk.language.interpreter.effects.EffectHandler {
    
    override val operations: Map<String, com.funk.language.interpreter.effects.EffectOperation> = 
        operationResults.keys.associateWith { opName ->
            MockEffectOperation(opName, operationResults[opName]!!)
        }
    
    override fun handleSync(
        operation: String, 
        arguments: FunkValue, 
        context: com.funk.language.interpreter.effects.EffectContext
    ): FunkValue {
        return operationResults[operation] 
            ?: throw RuntimeException("Operation not found: $operation")
    }
    
    override fun supportsOperation(operation: String): Boolean {
        return operation in operationResults
    }
    
    override fun getAvailableOperations(): Set<String> {
        return operationResults.keys
    }
}

class MockEffectOperation(
    override val name: String,
    private val result: FunkValue
) : com.funk.language.interpreter.effects.EffectOperation {
    
    override fun executeSync(
        arguments: FunkValue, 
        context: com.funk.language.interpreter.effects.EffectContext
    ): FunkValue {
        return result
    }
}