package com.funk.language.interpreter

import com.funk.language.interpreter.async.*
import com.funk.language.interpreter.continuations.ContinuationStateMachine
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Exhaustive tests for Phase 4: Checkpoint Strategies
 *
 * Tests cover:
 * - AlwaysContinuation strategy
 * - AlwaysLoom strategy
 * - PeriodicCheckpoint strategy
 * - OperationCountCheckpoint strategy
 * - ExplicitCheckpoint strategy
 * - NeverCheckpoint strategy
 * - Strategy hooks (onCheckpoint, onResume)
 * - Edge cases and performance
 */
class CheckpointStrategyTest {

    private lateinit var mockContinuation: ContinuationStateMachine

    @BeforeEach
    fun setup() {
        // Create a mock continuation for testing
        mockContinuation = ContinuationStateMachine(
            id = "test-continuation",
            currentState = 0,
            states = emptyList()
        )
    }

    // =============================================================================
    // AlwaysContinuation Strategy Tests
    // =============================================================================

    @Test
    fun testAlwaysContinuationAlwaysReturnsTrue() {
        val strategy = AlwaysContinuation()

        // Should always return true
        assertTrue(strategy.shouldCheckpoint())
        assertTrue(strategy.shouldCheckpoint())
        assertTrue(strategy.shouldCheckpoint())
    }

    @Test
    fun testAlwaysContinuationConsistency() {
        val strategy = AlwaysContinuation()

        // Should be consistent across many calls
        repeat(1000) {
            assertTrue(strategy.shouldCheckpoint())
        }
    }

    @Test
    fun testAlwaysContinuationHooks() {
        val checkpoints = mutableListOf<String>()
        val resumes = mutableListOf<String>()

        val strategy = object : AlwaysContinuation() {
            override fun onCheckpoint(state: ContinuationStateMachine) {
                checkpoints.add(state.id)
            }

            override fun onResume(state: ContinuationStateMachine) {
                resumes.add(state.id)
            }
        }

        strategy.onCheckpoint(mockContinuation)
        strategy.onResume(mockContinuation)

        assertEquals(listOf("test-continuation"), checkpoints)
        assertEquals(listOf("test-continuation"), resumes)
    }

    // =============================================================================
    // AlwaysLoom Strategy Tests
    // =============================================================================

    @Test
    fun testAlwaysLoomAlwaysReturnsFalse() {
        val strategy = AlwaysLoom()

        // Should always return false
        assertFalse(strategy.shouldCheckpoint())
        assertFalse(strategy.shouldCheckpoint())
        assertFalse(strategy.shouldCheckpoint())
    }

    @Test
    fun testAlwaysLoomConsistency() {
        val strategy = AlwaysLoom()

        // Should be consistent across many calls
        repeat(1000) {
            assertFalse(strategy.shouldCheckpoint())
        }
    }

    @Test
    fun testAlwaysLoomHooks() {
        val checkpoints = mutableListOf<String>()
        val resumes = mutableListOf<String>()

        val strategy = object : AlwaysLoom() {
            override fun onCheckpoint(state: ContinuationStateMachine) {
                checkpoints.add(state.id)
            }

            override fun onResume(state: ContinuationStateMachine) {
                resumes.add(state.id)
            }
        }

        strategy.onCheckpoint(mockContinuation)
        strategy.onResume(mockContinuation)

        assertEquals(listOf("test-continuation"), checkpoints)
        assertEquals(listOf("test-continuation"), resumes)
    }

    // =============================================================================
    // PeriodicCheckpoint Strategy Tests
    // =============================================================================

    @Test
    fun testPeriodicCheckpointBasic() {
        val strategy = PeriodicCheckpoint(intervalMs = 100L)

        // First call should checkpoint
        assertTrue(strategy.shouldCheckpoint())

        // Immediate next call should not
        assertFalse(strategy.shouldCheckpoint())

        // Wait for interval
        Thread.sleep(150)

        // Should checkpoint again
        assertTrue(strategy.shouldCheckpoint())
    }

    @Test
    fun testPeriodicCheckpointTiming() {
        val strategy = PeriodicCheckpoint(intervalMs = 50L)

        // First checkpoint
        assertTrue(strategy.shouldCheckpoint())

        // Within interval - should not checkpoint
        Thread.sleep(25)
        assertFalse(strategy.shouldCheckpoint())

        // Still within interval
        Thread.sleep(10)
        assertFalse(strategy.shouldCheckpoint())

        // After interval
        Thread.sleep(20)
        assertTrue(strategy.shouldCheckpoint())
    }

    @Test
    fun testPeriodicCheckpointMultipleCycles() {
        val strategy = PeriodicCheckpoint(intervalMs = 50L)
        val checkpoints = mutableListOf<Boolean>()

        // Cycle 1
        checkpoints.add(strategy.shouldCheckpoint()) // true
        Thread.sleep(25)
        checkpoints.add(strategy.shouldCheckpoint()) // false

        // Cycle 2
        Thread.sleep(30)
        checkpoints.add(strategy.shouldCheckpoint()) // true
        Thread.sleep(25)
        checkpoints.add(strategy.shouldCheckpoint()) // false

        // Cycle 3
        Thread.sleep(30)
        checkpoints.add(strategy.shouldCheckpoint()) // true

        assertEquals(listOf(true, false, true, false, true), checkpoints)
    }

    @Test
    fun testPeriodicCheckpointZeroInterval() {
        val strategy = PeriodicCheckpoint(intervalMs = 0L)

        // Zero interval means always checkpoint
        assertTrue(strategy.shouldCheckpoint())
        assertTrue(strategy.shouldCheckpoint())
        assertTrue(strategy.shouldCheckpoint())
    }

    @Test
    fun testPeriodicCheckpointLongInterval() {
        val strategy = PeriodicCheckpoint(intervalMs = 10000L) // 10 seconds

        // First checkpoint
        assertTrue(strategy.shouldCheckpoint())

        // Many calls within interval should all be false
        repeat(100) {
            assertFalse(strategy.shouldCheckpoint())
            Thread.sleep(10)
        }
    }

    @Test
    fun testPeriodicCheckpointReset() {
        val strategy = PeriodicCheckpoint(intervalMs = 100L)

        assertTrue(strategy.shouldCheckpoint()) // Resets timer

        Thread.sleep(50)
        assertFalse(strategy.shouldCheckpoint())

        Thread.sleep(60)
        assertTrue(strategy.shouldCheckpoint()) // Resets timer again

        Thread.sleep(50)
        assertFalse(strategy.shouldCheckpoint())
    }

    // =============================================================================
    // OperationCountCheckpoint Strategy Tests
    // =============================================================================

    @Test
    fun testOperationCountBasic() {
        val strategy = OperationCountCheckpoint(operationCount = 3)

        // First 2 operations should not checkpoint
        assertFalse(strategy.shouldCheckpoint())
        assertFalse(strategy.shouldCheckpoint())

        // 3rd operation should checkpoint
        assertTrue(strategy.shouldCheckpoint())

        // Counter resets
        assertFalse(strategy.shouldCheckpoint())
        assertFalse(strategy.shouldCheckpoint())
        assertTrue(strategy.shouldCheckpoint())
    }

    @Test
    fun testOperationCountOne() {
        val strategy = OperationCountCheckpoint(operationCount = 1)

        // Every operation should checkpoint
        assertTrue(strategy.shouldCheckpoint())
        assertTrue(strategy.shouldCheckpoint())
        assertTrue(strategy.shouldCheckpoint())
    }

    @Test
    fun testOperationCountLarge() {
        val strategy = OperationCountCheckpoint(operationCount = 1000)

        // First 999 should be false
        repeat(999) {
            assertFalse(strategy.shouldCheckpoint())
        }

        // 1000th should be true
        assertTrue(strategy.shouldCheckpoint())

        // Counter resets
        repeat(999) {
            assertFalse(strategy.shouldCheckpoint())
        }
        assertTrue(strategy.shouldCheckpoint())
    }

    @Test
    fun testOperationCountMultipleCycles() {
        val strategy = OperationCountCheckpoint(operationCount = 5)
        val results = mutableListOf<Boolean>()

        repeat(20) {
            results.add(strategy.shouldCheckpoint())
        }

        val expected = listOf(
            false, false, false, false, true, // Cycle 1
            false, false, false, false, true, // Cycle 2
            false, false, false, false, true, // Cycle 3
            false, false, false, false, true  // Cycle 4
        )

        assertEquals(expected, results)
    }

    @Test
    fun testOperationCountZero() {
        val strategy = OperationCountCheckpoint(operationCount = 0)

        // Zero count means always checkpoint
        assertTrue(strategy.shouldCheckpoint())
        assertTrue(strategy.shouldCheckpoint())
        assertTrue(strategy.shouldCheckpoint())
    }

    @Test
    fun testOperationCountNegative() {
        val strategy = OperationCountCheckpoint(operationCount = -1)

        // Negative count should be treated as always checkpoint
        assertTrue(strategy.shouldCheckpoint())
        assertTrue(strategy.shouldCheckpoint())
        assertTrue(strategy.shouldCheckpoint())
    }

    // =============================================================================
    // ExplicitCheckpoint Strategy Tests
    // =============================================================================

    @Test
    fun testExplicitCheckpointDefault() {
        val strategy = ExplicitCheckpoint()

        // Default should be false
        assertFalse(strategy.shouldCheckpoint())
        assertFalse(strategy.shouldCheckpoint())
        assertFalse(strategy.shouldCheckpoint())
    }

    @Test
    fun testExplicitCheckpointSetCheckpoint() {
        val strategy = ExplicitCheckpoint()

        assertFalse(strategy.shouldCheckpoint())

        // Request checkpoint
        strategy.requestCheckpoint()

        // Next call should checkpoint
        assertTrue(strategy.shouldCheckpoint())

        // Flag is cleared
        assertFalse(strategy.shouldCheckpoint())
    }

    @Test
    fun testExplicitCheckpointMultipleRequests() {
        val strategy = ExplicitCheckpoint()

        // Multiple requests
        strategy.requestCheckpoint()
        strategy.requestCheckpoint()
        strategy.requestCheckpoint()

        // Only next call checkpoints (not cumulative)
        assertTrue(strategy.shouldCheckpoint())
        assertFalse(strategy.shouldCheckpoint())
    }

    @Test
    fun testExplicitCheckpointRepeatedCycle() {
        val strategy = ExplicitCheckpoint()
        val results = mutableListOf<Boolean>()

        // Cycle 1
        strategy.requestCheckpoint()
        results.add(strategy.shouldCheckpoint()) // true
        results.add(strategy.shouldCheckpoint()) // false

        // Cycle 2
        strategy.requestCheckpoint()
        results.add(strategy.shouldCheckpoint()) // true
        results.add(strategy.shouldCheckpoint()) // false

        // Cycle 3
        strategy.requestCheckpoint()
        results.add(strategy.shouldCheckpoint()) // true

        assertEquals(listOf(true, false, true, false, true), results)
    }

    @Test
    fun testExplicitCheckpointNoRequestNoCheckpoint() {
        val strategy = ExplicitCheckpoint()

        // Without requesting, should not checkpoint
        assertFalse(strategy.shouldCheckpoint())
        assertFalse(strategy.shouldCheckpoint())
        assertFalse(strategy.shouldCheckpoint())
    }

    // =============================================================================
    // NeverCheckpoint Strategy Tests
    // =============================================================================

    @Test
    fun testNeverCheckpointAlwaysReturnsFalse() {
        val strategy = NeverCheckpoint()

        // Should never checkpoint
        assertFalse(strategy.shouldCheckpoint())
        assertFalse(strategy.shouldCheckpoint())
        assertFalse(strategy.shouldCheckpoint())
    }

    @Test
    fun testNeverCheckpointConsistency() {
        val strategy = NeverCheckpoint()

        // Should be consistent across many calls
        repeat(1000) {
            assertFalse(strategy.shouldCheckpoint())
        }
    }

    // =============================================================================
    // Strategy Comparison Tests
    // =============================================================================

    @Test
    fun testStrategyComparison() {
        val always = AlwaysContinuation()
        val never = NeverCheckpoint()
        val alwaysLoom = AlwaysLoom()

        // AlwaysContinuation vs NeverCheckpoint
        assertTrue(always.shouldCheckpoint())
        assertFalse(never.shouldCheckpoint())

        // AlwaysLoom is same as NeverCheckpoint for checkpointing
        assertEquals(never.shouldCheckpoint(), alwaysLoom.shouldCheckpoint())
    }

    @Test
    fun testStrategyBehaviorDifferences() {
        val periodic = PeriodicCheckpoint(intervalMs = 50L)
        val count = OperationCountCheckpoint(operationCount = 3)
        val explicit = ExplicitCheckpoint()

        // Initial state differences
        assertTrue(periodic.shouldCheckpoint()) // Periodic checkpoints immediately
        assertFalse(count.shouldCheckpoint())   // Count needs operations
        assertFalse(explicit.shouldCheckpoint()) // Explicit needs request

        // After waiting
        Thread.sleep(60)
        assertTrue(periodic.shouldCheckpoint()) // Time passed
        assertFalse(count.shouldCheckpoint())   // Still counting
        assertFalse(explicit.shouldCheckpoint()) // No request

        // After operations
        assertFalse(periodic.shouldCheckpoint()) // Just checkpointed
        assertTrue(count.shouldCheckpoint())     // Reached count
        assertFalse(explicit.shouldCheckpoint()) // Still no request

        // After explicit request
        explicit.requestCheckpoint()
        assertTrue(explicit.shouldCheckpoint()) // Now checkpoints
    }

    // =============================================================================
    // Hook Tests
    // =============================================================================

    @Test
    fun testOnCheckpointHook() {
        val checkpointIds = mutableListOf<String>()

        val strategy = object : PeriodicCheckpoint(100) {
            override fun onCheckpoint(state: ContinuationStateMachine) {
                checkpointIds.add(state.id)
            }
        }

        strategy.onCheckpoint(mockContinuation)
        strategy.onCheckpoint(mockContinuation)

        assertEquals(listOf("test-continuation", "test-continuation"), checkpointIds)
    }

    @Test
    fun testOnResumeHook() {
        val resumeIds = mutableListOf<String>()

        val strategy = object : OperationCountCheckpoint(5) {
            override fun onResume(state: ContinuationStateMachine) {
                resumeIds.add(state.id)
            }
        }

        strategy.onResume(mockContinuation)
        strategy.onResume(mockContinuation)

        assertEquals(listOf("test-continuation", "test-continuation"), resumeIds)
    }

    @Test
    fun testBothHooksTogether() {
        val events = mutableListOf<String>()

        val strategy = object : ExplicitCheckpoint() {
            override fun onCheckpoint(state: ContinuationStateMachine) {
                events.add("checkpoint:${state.id}")
            }

            override fun onResume(state: ContinuationStateMachine) {
                events.add("resume:${state.id}")
            }
        }

        strategy.onCheckpoint(mockContinuation)
        strategy.onResume(mockContinuation)
        strategy.onCheckpoint(mockContinuation)

        assertEquals(
            listOf(
                "checkpoint:test-continuation",
                "resume:test-continuation",
                "checkpoint:test-continuation"
            ),
            events
        )
    }

    // =============================================================================
    // Performance Tests
    // =============================================================================

    @Test
    fun testPeriodicCheckpointPerformance() {
        val strategy = PeriodicCheckpoint(intervalMs = 1000L)

        // Should be very fast to check
        val start = System.nanoTime()
        repeat(10000) {
            strategy.shouldCheckpoint()
        }
        val duration = System.nanoTime() - start

        // Should complete in less than 10ms for 10k checks
        assertTrue(duration < 10_000_000, "Periodic checkpoint too slow: ${duration}ns")
    }

    @Test
    fun testOperationCountPerformance() {
        val strategy = OperationCountCheckpoint(operationCount = 1000000)

        // Should be very fast to increment counter
        val start = System.nanoTime()
        repeat(10000) {
            strategy.shouldCheckpoint()
        }
        val duration = System.nanoTime() - start

        // Should complete in less than 5ms for 10k increments
        assertTrue(duration < 5_000_000, "Operation count too slow: ${duration}ns")
    }

    @Test
    fun testExplicitCheckpointPerformance() {
        val strategy = ExplicitCheckpoint()

        // Should be very fast to check flag
        val start = System.nanoTime()
        repeat(100000) {
            strategy.shouldCheckpoint()
        }
        val duration = System.nanoTime() - start

        // Should complete in less than 10ms for 100k checks
        assertTrue(duration < 10_000_000, "Explicit checkpoint too slow: ${duration}ns")
    }

    // =============================================================================
    // Concurrency Tests
    // =============================================================================

    @Test
    fun testPeriodicCheckpointThreadSafety() {
        val strategy = PeriodicCheckpoint(intervalMs = 50L)
        val results = mutableListOf<Boolean>()

        // Multiple threads checking simultaneously
        val threads = (1..10).map {
            Thread {
                repeat(10) {
                    synchronized(results) {
                        results.add(strategy.shouldCheckpoint())
                    }
                    Thread.sleep(10)
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Should have 100 results
        assertEquals(100, results.size)

        // Should have some true and some false
        assertTrue(results.contains(true))
        assertTrue(results.contains(false))
    }

    @Test
    fun testOperationCountThreadSafety() {
        val strategy = OperationCountCheckpoint(operationCount = 10)
        val checkpoints = mutableListOf<Int>()

        // Multiple threads incrementing counter
        val threads = (1..5).map { threadId ->
            Thread {
                repeat(20) {
                    if (strategy.shouldCheckpoint()) {
                        synchronized(checkpoints) {
                            checkpoints.add(threadId)
                        }
                    }
                }
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Should have checkpoints from multiple threads
        assertTrue(checkpoints.isNotEmpty())
    }

    @Test
    fun testExplicitCheckpointThreadSafety() {
        val strategy = ExplicitCheckpoint()
        val checkpoints = mutableListOf<Int>()

        // One thread requesting, multiple checking
        val requester = Thread {
            repeat(10) {
                Thread.sleep(10)
                strategy.requestCheckpoint()
            }
        }

        val checkers = (1..5).map { threadId ->
            Thread {
                repeat(20) {
                    if (strategy.shouldCheckpoint()) {
                        synchronized(checkpoints) {
                            checkpoints.add(threadId)
                        }
                    }
                    Thread.sleep(5)
                }
            }
        }

        requester.start()
        checkers.forEach { it.start() }
        requester.join()
        checkers.forEach { it.join() }

        // Should have some checkpoints
        assertTrue(checkpoints.isNotEmpty())
    }

    // =============================================================================
    // Edge Cases
    // =============================================================================

    @Test
    fun testPeriodicCheckpointClockSkew() {
        val strategy = PeriodicCheckpoint(intervalMs = 100L)

        assertTrue(strategy.shouldCheckpoint()) // t=0

        // Simulate clock going backwards (shouldn't happen in practice)
        // Strategy should handle gracefully
        Thread.sleep(50)
        assertFalse(strategy.shouldCheckpoint())

        Thread.sleep(60)
        assertTrue(strategy.shouldCheckpoint())
    }

    @Test
    fun testOperationCountOverflow() {
        val strategy = OperationCountCheckpoint(operationCount = 2)

        // Normal cycle
        assertFalse(strategy.shouldCheckpoint())
        assertTrue(strategy.shouldCheckpoint())

        // Many cycles - test for overflow
        repeat(1000000) {
            strategy.shouldCheckpoint()
        }

        // Should still work correctly
        assertFalse(strategy.shouldCheckpoint())
        assertTrue(strategy.shouldCheckpoint())
    }
}
