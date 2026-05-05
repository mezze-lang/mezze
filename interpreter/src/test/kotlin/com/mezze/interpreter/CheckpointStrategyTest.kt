package com.mezze.interpreter

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
 *
 * Migrated from V1 to V2. These are standalone strategy classes that don't depend
 * on the interpreter runtime, so they can be tested in isolation.
 */
class CheckpointStrategyTest {

    // =============================================================================
    // Checkpoint Strategy Interface and Implementations
    // =============================================================================

    /**
     * Simple continuation state machine for testing hooks.
     */
    data class ContinuationStateMachine(
        val id: String,
        val currentState: Int,
        val states: List<Any>
    )

    /**
     * Strategy interface for determining when to checkpoint.
     */
    interface CheckpointStrategy {
        fun shouldCheckpoint(): Boolean
        fun onCheckpoint(state: ContinuationStateMachine) {}
        fun onResume(state: ContinuationStateMachine) {}
    }

    /**
     * Always use continuations - maximum durability.
     */
    open class AlwaysContinuation : CheckpointStrategy {
        override fun shouldCheckpoint(): Boolean = true
    }

    /**
     * Always use Loom virtual threads - maximum performance.
     */
    open class AlwaysLoom : CheckpointStrategy {
        override fun shouldCheckpoint(): Boolean = false
    }

    /**
     * Checkpoint periodically based on time intervals.
     *
     * The clock is injectable so tests can drive time deterministically
     * (the default supplier is `System.currentTimeMillis`, so production
     * code stays unchanged).
     */
    open class PeriodicCheckpoint(
        private val intervalMs: Long,
        private val clock: () -> Long = System::currentTimeMillis,
    ) : CheckpointStrategy {
        private var lastCheckpoint = 0L

        override fun shouldCheckpoint(): Boolean {
            val now = clock()
            return if (now - lastCheckpoint >= intervalMs) {
                lastCheckpoint = now
                true
            } else {
                false
            }
        }

        override fun onCheckpoint(state: ContinuationStateMachine) {
            lastCheckpoint = clock()
        }
    }

    /**
     * Checkpoint after a specific number of operations.
     */
    open class OperationCountCheckpoint(private val operationCount: Int) : CheckpointStrategy {
        private var currentCount = 0

        override fun shouldCheckpoint(): Boolean {
            currentCount++
            return if (currentCount >= operationCount) {
                currentCount = 0
                true
            } else {
                false
            }
        }

        override fun onCheckpoint(state: ContinuationStateMachine) {
            currentCount = 0
        }
    }

    /**
     * Explicit checkpoint control - checkpoints only when requested.
     */
    open class ExplicitCheckpoint : CheckpointStrategy {
        @Volatile
        private var checkpointRequested = false

        override fun shouldCheckpoint(): Boolean {
            val result = checkpointRequested
            if (result) {
                checkpointRequested = false
            }
            return result
        }

        fun requestCheckpoint() {
            checkpointRequested = true
        }

        override fun onCheckpoint(state: ContinuationStateMachine) {
            checkpointRequested = false
        }
    }

    /**
     * Never checkpoint - equivalent to AlwaysLoom.
     */
    class NeverCheckpoint : CheckpointStrategy {
        override fun shouldCheckpoint(): Boolean = false
    }

    // =============================================================================
    // Test Setup
    // =============================================================================

    private lateinit var mockContinuation: ContinuationStateMachine

    @BeforeEach
    fun setUp() {
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
        var now = 1_000L
        val strategy = PeriodicCheckpoint(intervalMs = 100L, clock = { now })

        // First call should checkpoint
        assertTrue(strategy.shouldCheckpoint())

        // Immediate next call should not
        assertFalse(strategy.shouldCheckpoint())

        // Advance past interval
        now += 150

        // Should checkpoint again
        assertTrue(strategy.shouldCheckpoint())
    }

    @Test
    fun testPeriodicCheckpointTiming() {
        // Drive time with a virtual clock to keep the assertion deterministic
        // regardless of host scheduling jitter. Start at a time past the
        // interval so the first call mirrors production behaviour (where
        // lastCheckpoint=0 and System.currentTimeMillis is huge).
        var now = 1_000L
        val strategy = PeriodicCheckpoint(intervalMs = 50L, clock = { now })

        // First checkpoint: now=1000, lastCheckpoint=0 → 1000 >= 50 → true,
        // lastCheckpoint becomes 1000.
        assertTrue(strategy.shouldCheckpoint())

        // Within interval - should not checkpoint
        now += 25  // 1025-1000 = 25 < 50
        assertFalse(strategy.shouldCheckpoint())

        // Still within interval
        now += 10  // 1035-1000 = 35 < 50
        assertFalse(strategy.shouldCheckpoint())

        // After interval
        now += 20  // 1055-1000 = 55 >= 50
        assertTrue(strategy.shouldCheckpoint())
    }

    @Test
    fun testPeriodicCheckpointMultipleCycles() {
        var now = 1_000L
        val strategy = PeriodicCheckpoint(intervalMs = 50L, clock = { now })
        val checkpoints = mutableListOf<Boolean>()

        // First call: 1000-0 >= 50 → true; lastCheckpoint=1000
        checkpoints.add(strategy.shouldCheckpoint())
        now += 25  // 1025-1000 = 25 < 50
        checkpoints.add(strategy.shouldCheckpoint())

        // Cycle 2
        now += 30  // 1055-1000 = 55 >= 50 → true; lastCheckpoint=1055
        checkpoints.add(strategy.shouldCheckpoint())
        now += 25  // 1080-1055 = 25 < 50
        checkpoints.add(strategy.shouldCheckpoint())

        // Cycle 3
        now += 30  // 1110-1055 = 55 >= 50 → true
        checkpoints.add(strategy.shouldCheckpoint())

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
        var now = 100_000L
        val strategy = PeriodicCheckpoint(intervalMs = 10000L, clock = { now }) // 10 seconds

        // First checkpoint
        assertTrue(strategy.shouldCheckpoint())

        // Many calls within interval should all be false
        repeat(100) {
            assertFalse(strategy.shouldCheckpoint())
            now += 10
        }
    }

    @Test
    fun testPeriodicCheckpointReset() {
        var now = 1_000L
        val strategy = PeriodicCheckpoint(intervalMs = 100L, clock = { now })

        assertTrue(strategy.shouldCheckpoint()) // Resets timer; lastCheckpoint=1000

        now += 50  // 1050-1000 = 50 < 100
        assertFalse(strategy.shouldCheckpoint())

        now += 60  // 1110-1000 = 110 >= 100
        assertTrue(strategy.shouldCheckpoint()) // Resets timer; lastCheckpoint=1110

        now += 50  // 1160-1110 = 50 < 100
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
        var now = 1_000L
        val periodic = PeriodicCheckpoint(intervalMs = 50L, clock = { now })
        val count = OperationCountCheckpoint(operationCount = 3)
        val explicit = ExplicitCheckpoint()

        // Initial state differences
        assertTrue(periodic.shouldCheckpoint()) // Periodic checkpoints immediately
        assertFalse(count.shouldCheckpoint())   // Count needs operations
        assertFalse(explicit.shouldCheckpoint()) // Explicit needs request

        // After advancing past interval
        now += 60
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
        var now = 1_000L
        val strategy = PeriodicCheckpoint(intervalMs = 100L, clock = { now })

        assertTrue(strategy.shouldCheckpoint())

        now += 50
        assertFalse(strategy.shouldCheckpoint())

        now += 60
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
