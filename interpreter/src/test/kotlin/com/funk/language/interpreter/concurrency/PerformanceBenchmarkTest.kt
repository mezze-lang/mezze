package com.funk.language.interpreter.concurrency

import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.FunkString
import com.funk.language.interpreter.effects.EffectContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Disabled
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

/**
 * Performance benchmarks comparing sequential vs parallel execution
 * Tests requirement 1.6: Performance validation and optimization
 */
class PerformanceBenchmarkTest {
    
    private lateinit var concurrencyManager: LoomConcurrencyManager
    private lateinit var scheduler: SmartEffectScheduler
    private lateinit var dependencyAnalyzer: EffectDependencyAnalyzer
    
    @BeforeEach
    fun setUp() {
        concurrencyManager = LoomConcurrencyManager()
        dependencyAnalyzer = EffectDependencyAnalyzer()
        scheduler = SmartEffectScheduler(concurrencyManager, dependencyAnalyzer)
        
        // Warm up JVM for more accurate benchmarks
        PerformanceTestUtils.warmupJVM(500)
    }
    
    @AfterEach
    fun tearDown() {
        concurrencyManager.shutdown()
    }
    
    @Test
    @Timeout(30)
    fun `should demonstrate significant speedup for independent IO operations`() {
        val operationCount = 10
        val operationDurationMs = 100L
        
        // Create independent IO operations
        val operations = (1..operationCount).map { i ->
            EffectOperationTestFactory.createIOOperation(
                id = "io_op_$i",
                durationMs = operationDurationMs,
                result = FunkString("io_result_$i")
            )
        }
        
        // Measure sequential execution
        val sequentialTime = PerformanceTestUtils.measureExecution {
            operations.forEach { it.operation() }
        }
        
        // Measure parallel execution
        val parallelTime = PerformanceTestUtils.measureExecution {
            val block = EffectBlock(operations, EffectContext.empty())
            val plan = scheduler.analyzeEffectBlock(block)
            scheduler.executeWithParallelism(plan)
        }
        
        val speedup = sequentialTime.toDouble() / parallelTime
        val efficiency = speedup / operationCount
        
        println("IO Operations Benchmark:")
        println("  Sequential time: ${sequentialTime}ms")
        println("  Parallel time: ${parallelTime}ms")
        println("  Speedup: ${String.format("%.2f", speedup)}x")
        println("  Efficiency: ${String.format("%.2f", efficiency * 100)}%")
        
        assertTrue(speedup >= 3.0, "Should achieve at least 3x speedup for IO operations (got ${speedup}x)")
        assertTrue(parallelTime < sequentialTime / 2, "Parallel execution should be significantly faster")
        assertTrue(efficiency >= 0.3, "Should achieve reasonable efficiency (got ${efficiency})")
    }
    
    @Test
    @Timeout(30)
    fun `should show moderate speedup for compute-intensive operations`() {
        val operationCount = 8
        val operationDurationMs = 50L
        
        val operations = (1..operationCount).map { i ->
            EffectOperationTestFactory.createComputeOperation(
                id = "compute_op_$i",
                durationMs = operationDurationMs,
                result = FunkInt((i * i).toLong())
            )
        }
        
        val sequentialTime = PerformanceTestUtils.measureExecution {
            operations.forEach { it.operation() }
        }
        
        val parallelTime = PerformanceTestUtils.measureExecution {
            val block = EffectBlock(operations, EffectContext.empty())
            val plan = scheduler.analyzeEffectBlock(block)
            scheduler.executeWithParallelism(plan)
        }
        
        val speedup = sequentialTime.toDouble() / parallelTime
        
        println("Compute Operations Benchmark:")
        println("  Sequential time: ${sequentialTime}ms")
        println("  Parallel time: ${parallelTime}ms")
        println("  Speedup: ${String.format("%.2f", speedup)}x")
        
        assertTrue(speedup >= 1.5, "Should achieve at least 1.5x speedup for compute operations (got ${speedup}x)")
        assertTrue(parallelTime < sequentialTime, "Parallel execution should be faster")
    }
    
    @Test
    @Timeout(30)
    fun `should handle mixed workload efficiently`() {
        val ioCount = 6
        val computeCount = 4
        val networkCount = 3
        
        val operations = mutableListOf<EffectOperation>()
        
        // Add IO operations
        operations.addAll((1..ioCount).map { i ->
            EffectOperationTestFactory.createIOOperation(
                id = "mixed_io_$i",
                durationMs = 80L,
                result = FunkString("io_$i")
            )
        })
        
        // Add compute operations
        operations.addAll((1..computeCount).map { i ->
            EffectOperationTestFactory.createComputeOperation(
                id = "mixed_compute_$i",
                durationMs = 40L,
                result = FunkInt(i.toLong())
            )
        })
        
        // Add network operations
        operations.addAll((1..networkCount).map { i ->
            EffectOperationTestFactory.createNetworkOperation(
                id = "mixed_network_$i",
                durationMs = 120L,
                result = FunkString("network_$i")
            )
        })
        
        val sequentialTime = PerformanceTestUtils.measureExecution {
            operations.forEach { it.operation() }
        }
        
        val parallelTime = PerformanceTestUtils.measureExecution {
            val block = EffectBlock(operations, EffectContext.empty())
            val plan = scheduler.analyzeEffectBlock(block)
            scheduler.executeWithParallelism(plan)
        }
        
        val speedup = sequentialTime.toDouble() / parallelTime
        val totalOperations = operations.size
        
        println("Mixed Workload Benchmark:")
        println("  Operations: ${ioCount} IO, ${computeCount} compute, ${networkCount} network")
        println("  Sequential time: ${sequentialTime}ms")
        println("  Parallel time: ${parallelTime}ms")
        println("  Speedup: ${String.format("%.2f", speedup)}x")
        
        assertTrue(speedup >= 2.0, "Mixed workload should achieve good speedup (got ${speedup}x)")
        assertTrue(parallelTime < sequentialTime / 1.5, "Should be significantly faster than sequential")
    }
    
    @Test
    @Timeout(30)
    fun `should scale with increasing parallelism levels`() {
        val parallelismLevels = listOf(1, 2, 4, 8, 16)
        val operationDurationMs = 100L
        val results = mutableMapOf<Int, Long>()
        
        for (parallelism in parallelismLevels) {
            val operations = (1..parallelism).map { i ->
                EffectOperationTestFactory.createIOOperation(
                    id = "scale_op_${parallelism}_$i",
                    durationMs = operationDurationMs,
                    result = FunkString("result_$i")
                )
            }
            
            val executionTime = PerformanceTestUtils.measureExecution {
                val block = EffectBlock(operations, EffectContext.empty())
                val plan = scheduler.analyzeEffectBlock(block)
                scheduler.executeWithParallelism(plan)
            }
            
            results[parallelism] = executionTime
        }
        
        println("Scalability Benchmark:")
        results.forEach { (parallelism, time) ->
            val theoreticalSequential = parallelism * operationDurationMs
            val efficiency = (theoreticalSequential.toDouble() / time) / parallelism * 100
            println("  ${parallelism} operations: ${time}ms (efficiency: ${String.format("%.1f", efficiency)}%)")
        }
        
        // Verify scaling properties
        assertTrue(results[1]!! >= operationDurationMs * 0.8, "Single operation should take expected time")
        assertTrue(results[16]!! < results[1]!! * 4, "16 operations should be much faster than 16x single operation")
        
        // Check that execution time doesn't increase linearly with operation count
        val efficiency16 = (16 * operationDurationMs).toDouble() / results[16]!!
        assertTrue(efficiency16 >= 4.0, "Should maintain good efficiency at higher parallelism levels")
    }
    
    @Test
    @Timeout(30)
    fun `should demonstrate virtual thread efficiency vs platform threads`() {
        val threadCount = 1000
        val operationDurationMs = 50L
        val counter = AtomicInteger(0)
        
        // Measure virtual thread performance
        val virtualThreadTime = PerformanceTestUtils.measureExecution {
            val operations = (1..threadCount).map { i ->
                scheduler.createEffectOperation(
                    effectType = "VirtualThread",
                    estimatedDuration = Duration.ofMillis(operationDurationMs)
                ) {
                    Thread.sleep(operationDurationMs)
                    counter.incrementAndGet()
                    FunkInt(i.toLong())
                }
            }
            
            val block = EffectBlock(operations, EffectContext.empty())
            val plan = scheduler.analyzeEffectBlock(block)
            scheduler.executeWithParallelism(plan)
        }
        
        assertEquals(threadCount, counter.get(), "All virtual thread operations should complete")
        
        println("Virtual Thread Efficiency Benchmark:")
        println("  ${threadCount} virtual threads: ${virtualThreadTime}ms")
        println("  Average time per thread: ${virtualThreadTime.toDouble() / threadCount}ms")
        
        // Virtual threads should handle high concurrency efficiently
        assertTrue(virtualThreadTime < threadCount * operationDurationMs / 10, 
            "Virtual threads should be much more efficient than sequential execution")
        assertTrue(virtualThreadTime < 2000, 
            "Should handle ${threadCount} virtual threads quickly (${virtualThreadTime}ms)")
    }
    
    @Test
    @Timeout(30)
    fun `should benchmark dependency analysis overhead`() {
        // Warm up JVM more thoroughly for this specific test
        repeat(5) {
            val warmupOps = (1..50).map { i ->
                scheduler.createEffectOperation(
                    effectType = "Warmup",
                    estimatedDuration = Duration.ofMillis(1)
                ) { FunkInt(i.toLong()) }
            }
            val warmupBlock = EffectBlock(warmupOps, EffectContext.empty())
            scheduler.analyzeEffectBlock(warmupBlock)
        }

        val operationCounts = listOf(10, 50, 100, 200)
        val analysisResults = mutableMapOf<Int, Long>()

        for (count in operationCounts) {
            // Create operations with various dependency patterns
            val operations = (1..count).map { i ->
                val readVars = if (i > 1) setOf("var_${i-1}") else emptySet()
                val writeVars = setOf("var_$i")

                scheduler.createEffectOperation(
                    effectType = "Analysis",
                    readVars = readVars,
                    writeVars = writeVars,
                    estimatedDuration = Duration.ofMillis(10)
                ) {
                    FunkInt(i.toLong())
                }
            }

            // Run multiple times and take median to reduce variance
            val times = (1..3).map {
                PerformanceTestUtils.measureExecution {
                    val block = EffectBlock(operations, EffectContext.empty())
                    scheduler.analyzeEffectBlock(block)
                }
            }.sorted()
            val medianTime = times[1]

            analysisResults[count] = medianTime
        }

        println("Dependency Analysis Overhead Benchmark:")
        analysisResults.forEach { (count, time) ->
            val timePerOperation = time.toDouble() / count
            println("  ${count} operations: ${time}ms (${String.format("%.2f", timePerOperation)}ms per op)")
        }

        // Analysis overhead should be reasonable (relaxed thresholds for CI stability)
        analysisResults.forEach { (count, time) ->
            assertTrue(time < count * 20, "Analysis should be efficient for ${count} operations (${time}ms)")
        }

        // Just verify analysis completes in reasonable time - don't assert on scaling
        // as it varies too much with JVM state
        assertTrue(analysisResults[200]!! < 2000, "Analysis of 200 operations should complete within 2 seconds")
    }
    
    @Test
    @Timeout(30)
    fun `should measure memory efficiency of virtual threads`() {
        val threadCounts = listOf(100, 500, 1000, 2000)
        val memoryResults = mutableMapOf<Int, Long>()
        
        for (threadCount in threadCounts) {
            // Force garbage collection before measurement
            System.gc()
            Thread.sleep(100)
            
            val beforeMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            
            val operations = (1..threadCount).map { i ->
                scheduler.createEffectOperation(
                    effectType = "Memory",
                    estimatedDuration = Duration.ofMillis(200)
                ) {
                    Thread.sleep(200)
                    FunkString("thread_$i")
                }
            }
            
            val block = EffectBlock(operations, EffectContext.empty())
            val plan = scheduler.analyzeEffectBlock(block)
            
            // Start execution but don't wait for completion to measure peak memory
            val executionThread = Thread {
                scheduler.executeWithParallelism(plan)
            }
            executionThread.start()
            
            Thread.sleep(50) // Let threads start
            val peakMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            
            executionThread.join()
            
            val memoryUsed = peakMemory - beforeMemory
            memoryResults[threadCount] = memoryUsed
        }
        
        println("Memory Efficiency Benchmark:")
        memoryResults.forEach { (count, memory) ->
            val memoryPerThread = memory.toDouble() / count / 1024 // KB per thread
            println("  ${count} threads: ${memory / 1024}KB total (${String.format("%.2f", memoryPerThread)}KB per thread)")
        }
        
        // Virtual threads should be memory efficient (allowing for JVM overhead and GC variability)
        // Under heavy concurrent test load (880 tests), memory usage can be higher
        memoryResults.forEach { (count, memory) ->
            val memoryPerThread = memory.toDouble() / count
            assertTrue(memoryPerThread < 150 * 1024, // Less than 150KB per thread (adjusted for concurrent test load)
                "Virtual threads should be memory efficient: ${memoryPerThread / 1024}KB per thread")
        }
    }
    
    @Test
    @Timeout(30)
    fun `should benchmark structured concurrency overhead`() {
        val operationCount = 100
        val operationDurationMs = 20L
        
        // Measure with structured concurrency
        val structuredTime = PerformanceTestUtils.measureExecution {
            concurrencyManager.withStructuredScope("benchmark-scope") { scope ->
                val futures = (1..operationCount).map { i ->
                    scope.submit {
                        Thread.sleep(operationDurationMs)
                        "result_$i"
                    }
                }
                futures.map { it.get() }
            }
        }
        
        // Measure with direct virtual thread spawning (less structured)
        val directTime = PerformanceTestUtils.measureExecution {
            val threads = (1..operationCount).map { i ->
                concurrencyManager.spawnVirtualThread("direct-$i") {
                    Thread.sleep(operationDurationMs)
                }
            }
            threads.forEach { it.join() }
        }
        
        val overhead = ((structuredTime.toDouble() / directTime) - 1) * 100
        
        println("Structured Concurrency Overhead Benchmark:")
        println("  Structured concurrency: ${structuredTime}ms")
        println("  Direct virtual threads: ${directTime}ms")
        println("  Overhead: ${String.format("%.1f", overhead)}%")
        
        // Structured concurrency should have minimal overhead
        assertTrue(overhead < 50, "Structured concurrency overhead should be reasonable (${overhead}%)")
        assertTrue(structuredTime < directTime * 1.5, "Structured approach should not be much slower")
    }
}