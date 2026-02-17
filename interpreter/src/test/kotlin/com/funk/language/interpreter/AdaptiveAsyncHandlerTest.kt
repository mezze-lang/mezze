package com.funk.language.interpreter

import com.funk.language.interpreter.async.*
import com.funk.language.interpreter.continuations.ContinuationStateMachine
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Exhaustive tests for Phase 4: AdaptiveAsyncHandler
 *
 * Tests cover:
 * - Hybrid execution (switching between continuations and Loom)
 * - Integration with checkpoint strategies
 * - Performance characteristics
 * - Statistics tracking
 * - Error handling and edge cases
 * - Continuation vs Loom decision logic
 */
class AdaptiveAsyncHandlerTest {

    private lateinit var mockContinuation: ContinuationStateMachine

    @BeforeEach
    fun setup() {
        mockContinuation = ContinuationStateMachine(
            id = "test-adaptive",
            currentState = 0,
            states = emptyList()
        )
    }

    // =============================================================================
    // Basic Hybrid Execution Tests
    // =============================================================================

    @Test
    fun testAlwaysUseLoom() {
        val strategy = AlwaysLoom()
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        val future = CompletableFuture<FunkValue>()
        future.complete(FunkInt(42))
        val promise = FunkPromise(future)

        // Should use Loom (blocking await)
        val result = handler.await(promise)

        assertEquals(FunkInt(42), result)

        // Statistics should show Loom usage
        val stats = handler.getStats()
        assertEquals(1L, stats.totalOperations)
        assertEquals(0L, stats.continuationOperations)
        assertEquals(1L, stats.loomOperations)
    }

    @Test
    fun testAlwaysUseContinuation() {
        val strategy = AlwaysContinuation()
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        val future = CompletableFuture<FunkValue>()
        future.complete(FunkInt(42))
        val promise = FunkPromise(future)

        // Should use continuation (return suspended)
        val result = handler.await(promise)

        assertTrue(result is FunkContinuationSuspended)

        // Statistics should show continuation usage
        val stats = handler.getStats()
        assertEquals(1L, stats.totalOperations)
        assertEquals(1L, stats.continuationOperations)
        assertEquals(0L, stats.loomOperations)
    }

    @Test
    fun testNeverCheckpoint() {
        val strategy = NeverCheckpoint()
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        val future = CompletableFuture<FunkValue>()
        future.complete(FunkString("test"))
        val promise = FunkPromise(future)

        // Should use Loom (never checkpoint)
        val result = handler.await(promise)

        assertEquals(FunkString("test"), result)

        val stats = handler.getStats()
        assertEquals(1L, stats.loomOperations)
        assertEquals(0L, stats.continuationOperations)
    }

    // =============================================================================
    // Periodic Checkpoint Tests
    // =============================================================================

    @Test
    fun testPeriodicCheckpointSwitching() {
        val strategy = PeriodicCheckpoint(intervalMs = 100L)
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        val promises = (1..5).map {
            val future = CompletableFuture<FunkValue>()
            future.complete(FunkInt(it.toLong()))
            FunkPromise(future)
        }

        // First await should checkpoint (periodic starts with checkpoint)
        val r1 = handler.await(promises[0])
        assertTrue(r1 is FunkContinuationSuspended)

        // Immediate next should use Loom
        val r2 = handler.await(promises[1])
        assertEquals(FunkInt(2), r2)

        // Wait for interval
        Thread.sleep(150)

        // Should checkpoint again
        val r3 = handler.await(promises[2])
        assertTrue(r3 is FunkContinuationSuspended)

        // Verify statistics
        val stats = handler.getStats()
        assertEquals(3L, stats.totalOperations)
        assertEquals(2L, stats.continuationOperations)
        assertEquals(1L, stats.loomOperations)
    }

    @Test
    fun testPeriodicCheckpointAccuracy() {
        val strategy = PeriodicCheckpoint(intervalMs = 50L)
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        val results = mutableListOf<String>()

        repeat(10) { i ->
            val future = CompletableFuture<FunkValue>()
            future.complete(FunkInt(i.toLong()))
            val promise = FunkPromise(future)

            val result = handler.await(promise)
            results.add(if (result is FunkContinuationSuspended) "C" else "L")

            Thread.sleep(30)
        }

        // Should see pattern: C L C L C L ...
        // (First is checkpoint, then Loom until interval passes)
        assertTrue(results[0] == "C", "First should checkpoint")
        assertTrue(results.contains("L"), "Should use Loom sometimes")
        assertTrue(results.contains("C"), "Should use continuation sometimes")
    }

    // =============================================================================
    // Operation Count Checkpoint Tests
    // =============================================================================

    @Test
    fun testOperationCountSwitching() {
        val strategy = OperationCountCheckpoint(operationCount = 3)
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        val promises = (1..10).map {
            val future = CompletableFuture<FunkValue>()
            future.complete(FunkInt(it.toLong()))
            FunkPromise(future)
        }

        val results = promises.map { promise ->
            val result = handler.await(promise)
            if (result is FunkContinuationSuspended) "C" else "L"
        }

        // Expected pattern: L L C L L C L L C L
        // (First 2 use Loom, 3rd checkpoints, repeat)
        assertEquals(listOf("L", "L", "C", "L", "L", "C", "L", "L", "C", "L"), results)

        val stats = handler.getStats()
        assertEquals(10L, stats.totalOperations)
        assertEquals(3L, stats.continuationOperations)
        assertEquals(7L, stats.loomOperations)
    }

    @Test
    fun testOperationCountConsistency() {
        val strategy = OperationCountCheckpoint(operationCount = 5)
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        var continuationCount = 0
        var loomCount = 0

        repeat(50) {
            val future = CompletableFuture<FunkValue>()
            future.complete(FunkInt(it.toLong()))
            val promise = FunkPromise(future)

            val result = handler.await(promise)
            if (result is FunkContinuationSuspended) {
                continuationCount++
            } else {
                loomCount++
            }
        }

        // Should checkpoint every 5th operation
        assertEquals(10, continuationCount)
        assertEquals(40, loomCount)

        val stats = handler.getStats()
        assertEquals(50L, stats.totalOperations)
        assertEquals(10L, stats.continuationOperations)
        assertEquals(40L, stats.loomOperations)
    }

    // =============================================================================
    // Explicit Checkpoint Tests
    // =============================================================================

    @Test
    fun testExplicitCheckpointControl() {
        val strategy = ExplicitCheckpoint()
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        val promises = (1..5).map {
            val future = CompletableFuture<FunkValue>()
            future.complete(FunkInt(it.toLong()))
            FunkPromise(future)
        }

        // Without explicit request, should use Loom
        val r1 = handler.await(promises[0])
        assertEquals(FunkInt(1), r1)

        // Request checkpoint
        strategy.requestCheckpoint()

        // Next should checkpoint
        val r2 = handler.await(promises[1])
        assertTrue(r2 is FunkContinuationSuspended)

        // Back to Loom
        val r3 = handler.await(promises[2])
        assertEquals(FunkInt(3), r3)

        // Another request
        strategy.requestCheckpoint()

        // Checkpoint again
        val r4 = handler.await(promises[3])
        assertTrue(r4 is FunkContinuationSuspended)

        val stats = handler.getStats()
        assertEquals(4L, stats.totalOperations)
        assertEquals(2L, stats.continuationOperations)
        assertEquals(2L, stats.loomOperations)
    }

    @Test
    fun testExplicitCheckpointMultipleRequests() {
        val strategy = ExplicitCheckpoint()
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        // Multiple requests before checking
        strategy.requestCheckpoint()
        strategy.requestCheckpoint()
        strategy.requestCheckpoint()

        val future = CompletableFuture<FunkValue>()
        future.complete(FunkInt(42))
        val promise = FunkPromise(future)

        // Should checkpoint once
        val r1 = handler.await(promise)
        assertTrue(r1 is FunkContinuationSuspended)

        // Next should not checkpoint (flag cleared)
        val r2 = handler.await(promise)
        assertEquals(FunkInt(42), r2)
    }

    // =============================================================================
    // Statistics Tests
    // =============================================================================

    @Test
    fun testStatisticsAccuracy() {
        val strategy = OperationCountCheckpoint(operationCount = 4)
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        repeat(20) {
            val future = CompletableFuture<FunkValue>()
            future.complete(FunkInt(it.toLong()))
            val promise = FunkPromise(future)
            handler.await(promise)
        }

        val stats = handler.getStats()

        assertEquals(20L, stats.totalOperations)
        assertEquals(5L, stats.continuationOperations) // Every 4th
        assertEquals(15L, stats.loomOperations)
        assertEquals(25.0, stats.continuationPercentage, 0.1)
        assertEquals(75.0, stats.loomPercentage, 0.1)
    }

    @Test
    fun testStatisticsPercentages() {
        val strategy = AlwaysContinuation()
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        repeat(100) {
            val future = CompletableFuture<FunkValue>()
            future.complete(FunkInt(it.toLong()))
            val promise = FunkPromise(future)
            handler.await(promise)
        }

        val stats = handler.getStats()

        assertEquals(100L, stats.totalOperations)
        assertEquals(100L, stats.continuationOperations)
        assertEquals(0L, stats.loomOperations)
        assertEquals(100.0, stats.continuationPercentage, 0.01)
        assertEquals(0.0, stats.loomPercentage, 0.01)
    }

    @Test
    fun testStatisticsToString() {
        val strategy = OperationCountCheckpoint(operationCount = 2)
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        repeat(10) {
            val future = CompletableFuture<FunkValue>()
            future.complete(FunkInt(it.toLong()))
            val promise = FunkPromise(future)
            handler.await(promise)
        }

        val stats = handler.getStats()
        val str = stats.toString()

        assertTrue(str.contains("Total operations: 10"))
        assertTrue(str.contains("Continuation: 5"))
        assertTrue(str.contains("Loom: 5"))
        assertTrue(str.contains("50.0%"))
    }

    // =============================================================================
    // Error Handling Tests
    // =============================================================================

    @Test
    fun testContinuationRequiredError() {
        val strategy = AlwaysContinuation()

        // Should throw during construction if no continuation provided
        assertThrows(AdaptiveAsyncException::class.java) {
            AdaptiveAsyncHandler(null, strategy)
        }
    }

    @Test
    fun testAlwaysContinuationValidation() {
        val strategy = AlwaysContinuation()

        // Should throw during construction if no continuation
        assertThrows(AdaptiveAsyncException::class.java) {
            AdaptiveAsyncHandler(null, strategy)
        }
    }

    @Test
    fun testLoomWithoutContinuationOk() {
        val strategy = AlwaysLoom()
        val handler = AdaptiveAsyncHandler(null, strategy) // No continuation needed

        val future = CompletableFuture<FunkValue>()
        future.complete(FunkInt(42))
        val promise = FunkPromise(future)

        // Should work fine with Loom
        val result = handler.await(promise)
        assertEquals(FunkInt(42), result)
    }

    @Test
    fun testPromiseWithException() {
        val strategy = AlwaysLoom()
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        val future = CompletableFuture<FunkValue>()
        future.completeExceptionally(RuntimeException("Test error"))
        val promise = FunkPromise(future)

        // Should propagate exception
        assertThrows(RuntimeException::class.java) {
            handler.await(promise)
        }
    }

    // =============================================================================
    // Performance Tests
    // =============================================================================

    @Test
    fun testLoomPathPerformance() {
        val strategy = AlwaysLoom()
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        val promises = (1..1000).map {
            val future = CompletableFuture<FunkValue>()
            future.complete(FunkInt(it.toLong()))
            FunkPromise(future)
        }

        val start = System.nanoTime()
        promises.forEach { handler.await(it) }
        val duration = System.nanoTime() - start

        // 1000 Loom awaits should be fast (< 50ms)
        assertTrue(duration < 50_000_000, "Loom path too slow: ${duration}ns")

        val stats = handler.getStats()
        assertEquals(1000L, stats.loomOperations)
        assertEquals(0L, stats.continuationOperations)
    }

    @Test
    fun testContinuationPathPerformance() {
        val strategy = AlwaysContinuation()
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        val promises = (1..1000).map {
            val future = CompletableFuture<FunkValue>()
            future.complete(FunkInt(it.toLong()))
            FunkPromise(future)
        }

        val start = System.nanoTime()
        promises.forEach { handler.await(it) }
        val duration = System.nanoTime() - start

        // 1000 continuation awaits should be reasonably fast (< 100ms)
        assertTrue(duration < 100_000_000, "Continuation path too slow: ${duration}ns")

        val stats = handler.getStats()
        assertEquals(1000L, stats.continuationOperations)
        assertEquals(0L, stats.loomOperations)
    }

    @Test
    fun testHybridPathPerformance() {
        val strategy = OperationCountCheckpoint(operationCount = 10)
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        val promises = (1..1000).map {
            val future = CompletableFuture<FunkValue>()
            future.complete(FunkInt(it.toLong()))
            FunkPromise(future)
        }

        val start = System.nanoTime()
        promises.forEach { handler.await(it) }
        val duration = System.nanoTime() - start

        // Hybrid should be fast (< 100ms)
        assertTrue(duration < 100_000_000, "Hybrid path too slow: ${duration}ns")

        val stats = handler.getStats()
        assertEquals(1000L, stats.totalOperations)
        // 100 checkpoints (every 10th), 900 Loom
        assertEquals(100L, stats.continuationOperations)
        assertEquals(900L, stats.loomOperations)
    }

    @Test
    fun testStrategyOverheadPerformance() {
        val strategies = listOf(
            AlwaysLoom(),
            AlwaysContinuation(),
            PeriodicCheckpoint(intervalMs = 1000L),
            OperationCountCheckpoint(operationCount = 100),
            ExplicitCheckpoint(),
            NeverCheckpoint()
        )

        strategies.forEach { strategy ->
            val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

            val start = System.nanoTime()
            repeat(1000) {
                strategy.shouldCheckpoint()
            }
            val duration = System.nanoTime() - start

            // Strategy check should be fast (< 5ms for 1000 checks)
            assertTrue(
                duration < 5_000_000,
                "${strategy::class.simpleName} too slow: ${duration}ns"
            )
        }
    }

    // =============================================================================
    // Concurrency Tests
    // =============================================================================

    @Test
    fun testConcurrentLoomAwaits() {
        val strategy = AlwaysLoom()
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        val threads = (1..10).map { threadId ->
            Thread {
                repeat(10) {
                    val future = CompletableFuture<FunkValue>()
                    future.complete(FunkInt((threadId * 10 + it).toLong()))
                    val promise = FunkPromise(future)
                    handler.await(promise)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val stats = handler.getStats()
        assertEquals(100L, stats.totalOperations)
        assertEquals(100L, stats.loomOperations)
    }

    @Test
    fun testConcurrentStatisticsUpdate() {
        val strategy = OperationCountCheckpoint(operationCount = 5)
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        val threads = (1..5).map {
            Thread {
                repeat(20) { i ->
                    val future = CompletableFuture<FunkValue>()
                    future.complete(FunkInt(i.toLong()))
                    val promise = FunkPromise(future)
                    handler.await(promise)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val stats = handler.getStats()
        assertEquals(100L, stats.totalOperations)
        // Statistics should be consistent
        assertEquals(stats.totalOperations, stats.continuationOperations + stats.loomOperations)
    }

    // =============================================================================
    // Integration Tests
    // =============================================================================

    @Test
    fun testHybridExecutionPattern() {
        // Simulate realistic usage: mostly Loom, periodic checkpoints
        val strategy = PeriodicCheckpoint(intervalMs = 100L)
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        val results = mutableListOf<FunkValue>()

        // Simulate 1 second of operations
        val endTime = System.currentTimeMillis() + 1000
        var opCount = 0

        while (System.currentTimeMillis() < endTime) {
            val future = CompletableFuture<FunkValue>()
            future.complete(FunkInt(opCount.toLong()))
            val promise = FunkPromise(future)

            val result = handler.await(promise)
            if (result !is FunkContinuationSuspended) {
                results.add(result)
            }

            opCount++
            Thread.sleep(10) // ~100 ops/sec
        }

        val stats = handler.getStats()

        // Should have mostly Loom operations
        assertTrue(stats.loomOperations > stats.continuationOperations)

        // Should have some checkpoints (~10 in 1 second with 100ms interval)
        assertTrue(stats.continuationOperations >= 5)
        assertTrue(stats.continuationOperations <= 15)

        // Percentages should make sense
        assertTrue(stats.loomPercentage > 80.0)
        assertTrue(stats.continuationPercentage < 20.0)
    }

    @Test
    fun testStrategyChangeSimulation() {
        // Simulate changing strategy behavior over time
        val strategy = ExplicitCheckpoint()
        val handler = AdaptiveAsyncHandler(mockContinuation, strategy)

        // Phase 1: All Loom
        repeat(10) {
            val future = CompletableFuture<FunkValue>()
            future.complete(FunkInt(it.toLong()))
            val promise = FunkPromise(future)
            handler.await(promise)
        }

        // Phase 2: Request checkpoint every few operations
        repeat(10) {
            if (it % 3 == 0) strategy.requestCheckpoint()

            val future = CompletableFuture<FunkValue>()
            future.complete(FunkInt((it + 10).toLong()))
            val promise = FunkPromise(future)
            handler.await(promise)
        }

        // Phase 3: Back to all Loom
        repeat(10) {
            val future = CompletableFuture<FunkValue>()
            future.complete(FunkInt((it + 20).toLong()))
            val promise = FunkPromise(future)
            handler.await(promise)
        }

        val stats = handler.getStats()
        assertEquals(30L, stats.totalOperations)

        // Should have some checkpoints from phase 2
        assertTrue(stats.continuationOperations >= 3)
        assertTrue(stats.continuationOperations <= 5)

        // Most should be Loom
        assertTrue(stats.loomOperations >= 25)
    }
}
