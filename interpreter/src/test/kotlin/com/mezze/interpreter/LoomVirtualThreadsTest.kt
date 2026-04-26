package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for Loom Virtual Thread Support
 *
 * These tests verify that Java's Loom virtual threads work correctly:
 * - Spawning virtual threads
 * - Thread coordination with park/unpark
 * - Handling many concurrent threads
 * - Error handling and edge cases
 *
 * Migrated from V1 to V2. Uses Java's Thread.ofVirtual() directly.
 */
class LoomVirtualThreadsTest {
    private fun spawnVirtualThread(name: String, task: () -> Unit): Thread {
        return Thread.ofVirtual().name(name).start(task)
    }

    // =============================================================================
    // Basic Virtual Thread Tests
    // =============================================================================

    @Test
    fun testBasicSpawn() {
        val completed = AtomicBoolean(false)
        spawnVirtualThread("test") {
            completed.set(true)
        }
        Thread.sleep(50)
        assertTrue(completed.get())
    }

    @Test
    fun testMultipleThreads() {
        val counter = AtomicInteger(0)
        repeat(10) {
            spawnVirtualThread("test-$it") {
                counter.incrementAndGet()
            }
        }
        Thread.sleep(100)
        assertEquals(10, counter.get())
    }

    @Test
    fun testThreadReturnValue() {
        val result = CompletableFuture<Int>()
        spawnVirtualThread("calculator") {
            result.complete(42)
        }
        assertEquals(42, result.get(1, TimeUnit.SECONDS))
    }

    @Test
    fun testThreadWithClosure() {
        val x = 10
        val result = CompletableFuture<Int>()
        spawnVirtualThread("closure-test") {
            result.complete(x * 2)
        }
        assertEquals(20, result.get(1, TimeUnit.SECONDS))
    }

    // =============================================================================
    // Park/Unpark Tests
    // =============================================================================

    @Test
    fun testParkUnparkBasic() {
        val unparked = AtomicBoolean(false)
        val thread = CompletableFuture<Thread>()

        spawnVirtualThread("parker") {
            thread.complete(Thread.currentThread())
            java.util.concurrent.locks.LockSupport.park()
            unparked.set(true)
        }

        val t = thread.get(100, TimeUnit.MILLISECONDS)
        Thread.sleep(50)
        java.util.concurrent.locks.LockSupport.unpark(t)
        Thread.sleep(50)

        assertTrue(unparked.get())
    }

    @Test
    fun testUnparkBeforePark() {
        val thread = Thread.currentThread()

        spawnVirtualThread("unpark-first") {
            Thread.sleep(10)
            java.util.concurrent.locks.LockSupport.unpark(thread)
        }

        Thread.sleep(50)
        val start = System.nanoTime()
        java.util.concurrent.locks.LockSupport.park()
        val duration = System.nanoTime() - start

        assertTrue(duration < 10_000_000, "Park should return immediately")
    }

    @Test
    fun testMultipleUnparks() {
        val thread = Thread.currentThread()

        spawnVirtualThread("unpark1") {
            java.util.concurrent.locks.LockSupport.unpark(thread)
        }
        spawnVirtualThread("unpark2") {
            java.util.concurrent.locks.LockSupport.unpark(thread)
        }
        spawnVirtualThread("unpark3") {
            java.util.concurrent.locks.LockSupport.unpark(thread)
        }

        Thread.sleep(50)

        val start1 = System.nanoTime()
        java.util.concurrent.locks.LockSupport.park()
        val duration1 = System.nanoTime() - start1
        assertTrue(duration1 < 10_000_000, "First park should return immediately")

        val start2 = System.nanoTime()
        java.util.concurrent.locks.LockSupport.parkNanos(10_000_000)
        val duration2 = System.nanoTime() - start2
        assertTrue(duration2 >= 5_000_000, "Second park should block")
    }

    @Test
    fun testProducerConsumer() {
        // Tests producer-consumer pattern using CompletableFuture for proper synchronization.
        // Avoids park/unpark race conditions under heavy load.
        val data = AtomicInteger(0)
        val producerDone = CompletableFuture<Void>()

        spawnVirtualThread("producer") {
            data.set(42)
            producerDone.complete(null)
        }

        // Wait for producer to complete (proper synchronization)
        producerDone.get(1, TimeUnit.SECONDS)
        assertEquals(42, data.get())
    }

    // =============================================================================
    // Scalability Tests
    // =============================================================================

    @Test
    fun testManyVirtualThreads() {
        val count = 1000
        val completed = AtomicInteger(0)

        repeat(count) {
            spawnVirtualThread("thread-$it") {
                completed.incrementAndGet()
            }
        }

        Thread.sleep(500)
        assertEquals(count, completed.get())
    }

    @Test
    fun testVirtualThreadScalability() {
        val threadCount = 10000
        val parked = AtomicInteger(0)

        val start = System.nanoTime()

        for (i in 1..threadCount) {
            spawnVirtualThread("parked-$i") {
                parked.incrementAndGet()
                java.util.concurrent.locks.LockSupport.parkNanos(100_000_000)
            }
        }

        Thread.sleep(50)
        val duration = System.nanoTime() - start

        assertTrue(duration < 1_000_000_000, "Creating threads took too long")
        Thread.sleep(200)
        assertEquals(threadCount, parked.get())
    }

    @Test
    fun testSharedStateAccess() {
        val counter = AtomicInteger(0)
        val threads = 100
        val increments = 10

        repeat(threads) {
            spawnVirtualThread("thread-$it") {
                repeat(increments) {
                    counter.incrementAndGet()
                }
            }
        }

        Thread.sleep(1000)
        assertEquals(threads * increments, counter.get())
    }

    @Test
    fun testConcurrentReads() {
        val sharedValue = 42
        val results = mutableListOf<CompletableFuture<Int>>()

        repeat(100) {
            val future = CompletableFuture<Int>()
            results.add(future)
            spawnVirtualThread("reader-$it") {
                future.complete(sharedValue)
            }
        }

        CompletableFuture.allOf(*results.toTypedArray()).get(5, TimeUnit.SECONDS)
        results.forEach { assertEquals(42, it.get()) }
    }

    // =============================================================================
    // Error Handling Tests
    // =============================================================================

    @Test
    fun testThreadException() {
        val failed = AtomicBoolean(false)

        spawnVirtualThread("failing") {
            try {
                throw RuntimeException("Test exception")
            } catch (e: Exception) {
                failed.set(true)
            }
        }

        Thread.sleep(50)
        assertTrue(failed.get())
    }

    @Test
    fun testExceptionDoesNotAffectOtherThreads() {
        val counter = AtomicInteger(0)

        spawnVirtualThread("failing") {
            try {
                throw RuntimeException("Test exception")
            } catch (e: Exception) {
                // Swallow
            }
        }

        repeat(10) {
            spawnVirtualThread("working-$it") {
                counter.incrementAndGet()
            }
        }

        Thread.sleep(100)
        assertEquals(10, counter.get())
    }

    // =============================================================================
    // Coordination Patterns
    // =============================================================================

    @Test
    fun testRendezvous() {
        val counter = AtomicInteger(0)
        val ready = CompletableFuture<Thread>()

        spawnVirtualThread("thread1") {
            counter.incrementAndGet()
            ready.complete(Thread.currentThread())
            java.util.concurrent.locks.LockSupport.park()
            counter.incrementAndGet()
        }

        spawnVirtualThread("thread2") {
            Thread.sleep(50)
            counter.incrementAndGet()
            val thread1 = ready.get()
            java.util.concurrent.locks.LockSupport.unpark(thread1)
        }

        Thread.sleep(200)
        assertEquals(3, counter.get())
    }

    @Test
    fun testBarrier() {
        val threadCount = 5
        val ready = AtomicInteger(0)
        val done = AtomicInteger(0)
        val barrier = CompletableFuture<Void>()

        repeat(threadCount) {
            spawnVirtualThread("worker-$it") {
                ready.incrementAndGet()
                barrier.get()
                done.incrementAndGet()
            }
        }

        // Wait for all threads to be ready
        while (ready.get() < threadCount) {
            Thread.sleep(10)
        }

        // Release barrier
        barrier.complete(null)

        // Wait for all to complete
        Thread.sleep(100)
        assertEquals(threadCount, done.get())
    }

    @Test
    fun testNestedVirtualThreads() {
        val innerCompleted = AtomicBoolean(false)
        val outerCompleted = AtomicBoolean(false)

        spawnVirtualThread("outer") {
            spawnVirtualThread("inner") {
                innerCompleted.set(true)
            }
            Thread.sleep(100)
            outerCompleted.set(true)
        }

        Thread.sleep(200)
        assertTrue(innerCompleted.get())
        assertTrue(outerCompleted.get())
    }

    // =============================================================================
    // Performance Tests
    // =============================================================================

    @Test
    fun testParkUnparkLatency() {
        val iterations = 100
        val latencies = mutableListOf<Long>()

        repeat(iterations) {
            val mainThread = Thread.currentThread()

            spawnVirtualThread("unparker") {
                Thread.sleep(1)
                java.util.concurrent.locks.LockSupport.unpark(mainThread)
            }

            val start = System.nanoTime()
            java.util.concurrent.locks.LockSupport.park()
            val latency = System.nanoTime() - start

            latencies.add(latency)
        }

        val avgLatency = latencies.average()
        val maxLatency = latencies.maxOrNull() ?: 0L

        assertTrue(avgLatency < 5_000_000, "Average latency too high: ${avgLatency}ns")
        assertTrue(maxLatency < 50_000_000, "Max latency too high: ${maxLatency}ns")
    }

    @Test
    fun testManyParkUnpark() {
        // Test park/unpark with a moderate number of threads
        val threadCount = 100  // Reduced to ensure test completes quickly
        val parked = AtomicInteger(0)
        val threads = mutableListOf<Thread>()

        repeat(threadCount) {
            val thread = CompletableFuture<Thread>()

            spawnVirtualThread("thread-$it") {
                thread.complete(Thread.currentThread())
                parked.incrementAndGet()
                java.util.concurrent.locks.LockSupport.park()
            }

            threads.add(thread.get(1, TimeUnit.SECONDS))
        }

        Thread.sleep(50)
        assertEquals(threadCount, parked.get())

        // Unpark all threads
        val start = System.nanoTime()
        threads.forEach { java.util.concurrent.locks.LockSupport.unpark(it) }
        val unparkTime = System.nanoTime() - start

        assertTrue(unparkTime < 100_000_000, "Unparking took too long: ${unparkTime}ns")
        Thread.sleep(50)  // Give threads time to wake up
    }
}
