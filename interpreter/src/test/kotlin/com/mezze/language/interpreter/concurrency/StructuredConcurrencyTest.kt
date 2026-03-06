package com.mezze.language.interpreter.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

/**
 * Tests for structured concurrency scope cleanup and cancellation.
 * Tests the LoomConcurrencyManager infrastructure directly (not Mezze code).
 *
 * Tests requirements 8.1, 8.2: Structured concurrency and cancellation
 */
class StructuredConcurrencyTest {

    private lateinit var concurrencyManager: LoomConcurrencyManager

    @BeforeEach
    fun setUp() {
        concurrencyManager = LoomConcurrencyManager()
    }

    @AfterEach
    fun tearDown() {
        concurrencyManager.shutdown()
    }

    @Test
    @Timeout(10)
    fun `should execute all tasks in structured scope`() {
        val results = mutableListOf<Int>()
        val completedTasks = AtomicInteger(0)

        val scopeResult = concurrencyManager.withStructuredScope("test-scope") { scope ->
            val futures = (1..5).map { i ->
                scope.submit {
                    Thread.sleep(50) // Simulate work
                    completedTasks.incrementAndGet()
                    synchronized(results) {
                        results.add(i)
                    }
                    i * 2
                }
            }

            futures.map { it.get() }
        }

        assertEquals(listOf(2, 4, 6, 8, 10), scopeResult.sorted())
        assertEquals(5, completedTasks.get())
        assertEquals(5, results.size)
    }

    @Test
    @Timeout(10)
    fun `should automatically clean up scope when completed`() {
        val scopeName = "cleanup-test-scope"

        concurrencyManager.withStructuredScope(scopeName) { scope ->
            repeat(3) { i ->
                scope.submit {
                    Thread.sleep(50)
                    "task-$i"
                }
            }
            "scope-completed"
        }

        // Scope should be cleaned up automatically
        val finalStats = concurrencyManager.getThreadStatistics()
        assertFalse(finalStats.scopeNames.contains(scopeName),
            "Scope should be cleaned up after completion")
    }

    @Test
    @Timeout(10)
    fun `should cancel scope and interrupt tasks`() {
        // Use a latch to ensure tasks have started before cancellation
        val tasksStarted = CountDownLatch(5)
        val tasksInterrupted = AtomicInteger(0)
        val tasksCompleted = AtomicInteger(0)
        val scopeName = "cancellation-test"

        val scopeThread = Thread {
            try {
                concurrencyManager.withStructuredScope(scopeName) { scope ->
                    repeat(5) { i ->
                        scope.submit {
                            tasksStarted.countDown()
                            try {
                                // Long sleep to ensure we're still running when cancelled
                                Thread.sleep(10000)
                                tasksCompleted.incrementAndGet()
                                "completed-$i"
                            } catch (e: InterruptedException) {
                                tasksInterrupted.incrementAndGet()
                                throw e
                            }
                        }
                    }
                    // Keep scope alive while we wait
                    Thread.sleep(10000)
                    "scope-result"
                }
            } catch (e: Exception) {
                // Expected when scope is cancelled
            }
        }

        scopeThread.start()

        // Wait for all tasks to start
        assertTrue(tasksStarted.await(5, TimeUnit.SECONDS), "Tasks should start within timeout")

        // Cancel the scope
        concurrencyManager.cancelScope(scopeName)

        // Wait for scope thread to finish
        scopeThread.join(5000)

        // Verify that not all tasks completed (some should have been interrupted/cancelled)
        assertTrue(tasksCompleted.get() < 5,
            "Not all tasks should have completed when scope was cancelled (completed: ${tasksCompleted.get()})")
    }

    @Test
    @Timeout(10)
    fun `should handle exceptions in structured scope`() {
        val successfulTasks = AtomicInteger(0)

        val exception = assertThrows(Exception::class.java) {
            concurrencyManager.withStructuredScope("exception-scope") { scope ->
                val futures = (1..5).map { i ->
                    scope.submit {
                        if (i == 3) {
                            throw RuntimeException("Task $i failed intentionally")
                        } else {
                            Thread.sleep(50)
                            successfulTasks.incrementAndGet()
                            "result-$i"
                        }
                    }
                }

                // Get all results - should propagate the exception
                futures.map { it.get() }
            }
        }

        // Exception may be wrapped in ExecutionException
        val rootCause = if (exception is java.util.concurrent.ExecutionException) {
            exception.cause
        } else {
            exception
        }
        assertTrue(rootCause?.message?.contains("Task 3 failed") == true,
            "Should propagate the exception from the failed task, got: ${rootCause?.message}")
    }

    @Test
    @Timeout(10)
    fun `should support nested structured scopes`() {
        val outerResults = mutableListOf<String>()
        val innerResults = mutableListOf<String>()

        concurrencyManager.withStructuredScope("outer-scope") { outerScope ->
            outerScope.submit {
                synchronized(outerResults) { outerResults.add("outer-1") }

                // Nested scope
                concurrencyManager.withStructuredScope("inner-scope") { innerScope ->
                    innerScope.submit {
                        synchronized(innerResults) { innerResults.add("inner-1") }
                        "inner-result-1"
                    }
                    innerScope.submit {
                        synchronized(innerResults) { innerResults.add("inner-2") }
                        "inner-result-2"
                    }
                    "inner-scope-complete"
                }

                "outer-result-1"
            }

            outerScope.submit {
                synchronized(outerResults) { outerResults.add("outer-2") }
                "outer-result-2"
            }

            "outer-scope-complete"
        }

        assertEquals(2, outerResults.size, "Should have 2 outer results")
        assertEquals(2, innerResults.size, "Should have 2 inner results")
    }

    @Test
    @Timeout(10)
    fun `should handle scope timeout properly`() {
        val tasksStarted = AtomicInteger(0)

        val exception = assertThrows(java.util.concurrent.TimeoutException::class.java) {
            concurrencyManager.withStructuredScope(
                name = "timeout-scope",
                timeout = Duration.ofMillis(200)
            ) { scope ->
                scope.submit {
                    tasksStarted.incrementAndGet()
                    Thread.sleep(5000) // Much longer than timeout
                    "should-not-complete"
                }
                "scope-result"
            }
        }

        assertTrue(exception.message?.contains("timed out") == true,
            "Should timeout with appropriate message")
        assertTrue(tasksStarted.get() > 0, "Task should have started before timeout")
    }

    @Test
    @Timeout(10)
    fun `should execute tasks concurrently`() {
        val taskStartTimes = ConcurrentHashMap<Int, Long>()
        val taskEndTimes = ConcurrentHashMap<Int, Long>()

        concurrencyManager.withStructuredScope("concurrent-scope") { scope ->
            val futures = (1..3).map { i ->
                scope.submit {
                    taskStartTimes[i] = System.currentTimeMillis()
                    Thread.sleep(200)
                    taskEndTimes[i] = System.currentTimeMillis()
                    "result-$i"
                }
            }
            futures.map { it.get() }
        }

        // Verify that tasks ran concurrently by checking overlap
        val starts = taskStartTimes.values.toList()
        val ends = taskEndTimes.values.toList()

        // The max start time should be less than the min end time for concurrent execution
        // (i.e., all tasks started before any finished)
        val maxStart = starts.maxOrNull()!!
        val minEnd = ends.minOrNull()!!

        assertTrue(maxStart < minEnd,
            "Tasks should run concurrently (maxStart=$maxStart < minEnd=$minEnd)")
    }

    @Test
    @Timeout(10)
    fun `should track active scopes in statistics`() {
        val scopeStarted = CountDownLatch(1)
        val canFinish = CountDownLatch(1)

        val scopeThread = Thread {
            concurrencyManager.withStructuredScope("tracked-scope") { scope ->
                scopeStarted.countDown()
                scope.submit {
                    canFinish.await()
                    "task-done"
                }
                "scope-done"
            }
        }

        scopeThread.start()

        // Wait for scope to start
        assertTrue(scopeStarted.await(5, TimeUnit.SECONDS))
        Thread.sleep(50) // Brief pause to ensure scope is registered

        // Check that scope is tracked
        val stats = concurrencyManager.getThreadStatistics()
        assertTrue(stats.scopeNames.contains("tracked-scope"),
            "Active scope should be tracked in statistics")

        // Allow scope to finish
        canFinish.countDown()
        scopeThread.join(5000)

        // Check that scope is cleaned up
        val finalStats = concurrencyManager.getThreadStatistics()
        assertFalse(finalStats.scopeNames.contains("tracked-scope"),
            "Completed scope should be removed from statistics")
    }
}
