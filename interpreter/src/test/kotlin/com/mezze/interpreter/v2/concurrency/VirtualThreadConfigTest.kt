package com.mezze.interpreter.v2.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Tests for virtual thread configuration and pool management.
 *
 * Migrated from V1 VirtualThreadConfigTest. The V1 version tested custom
 * infrastructure classes (VirtualThreadConfig, VirtualThreadPoolManager,
 * ConfiguredVirtualThreadFactory). This V2 version tests equivalent concepts
 * using Java's built-in virtual thread APIs.
 *
 * Tests:
 * - Virtual thread factory configuration
 * - Thread pool management with virtual threads
 * - Thread naming and tracking
 * - Thread limits and monitoring
 */
class VirtualThreadConfigTest {

    private var executor: java.util.concurrent.ExecutorService? = null

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @AfterEach
    fun tearDown() {
        executor?.shutdown()
        executor?.awaitTermination(5, TimeUnit.SECONDS)
    }

    // =============================================================================
    // Virtual Thread Factory Configuration
    // =============================================================================

    @Test
    fun `should create virtual threads with factory`() {
        val factory = Thread.ofVirtual().name("test-thread-", 0).factory()

        val thread = factory.newThread { }

        assertTrue(thread.isVirtual)
        assertTrue(thread.name.startsWith("test-thread-"))
        assertEquals(Thread.State.NEW, thread.state)
    }

    @Test
    fun `should generate unique thread names with counter`() {
        val factory = Thread.ofVirtual().name("unique-", 0).factory()

        val thread1 = factory.newThread { }
        val thread2 = factory.newThread { }

        assertNotEquals(thread1.name, thread2.name)
        assertTrue(thread1.name.startsWith("unique-"))
        assertTrue(thread2.name.startsWith("unique-"))
    }

    @Test
    fun `should create threads with custom prefix`() {
        val factory = Thread.ofVirtual().name("custom-prefix-", 0).factory()

        val thread = factory.newThread { }

        assertTrue(thread.name.startsWith("custom-prefix-"))
        assertTrue(thread.isVirtual)
    }

    // =============================================================================
    // Virtual Thread Executor Configuration
    // =============================================================================

    @Test
    fun `should create virtual thread executor`() {
        executor = Executors.newVirtualThreadPerTaskExecutor()

        val latch = CountDownLatch(3)
        val threads = mutableListOf<Thread>()
        val threadsLock = Object()

        repeat(3) { i ->
            executor!!.submit {
                synchronized(threadsLock) {
                    threads.add(Thread.currentThread())
                }
                Thread.sleep(50)
                latch.countDown()
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(3, threads.size)
        threads.forEach { assertTrue(it.isVirtual) }
    }

    @Test
    fun `should track active threads in executor`() {
        executor = Executors.newVirtualThreadPerTaskExecutor()

        val startLatch = CountDownLatch(2)
        val finishLatch = CountDownLatch(1)
        val activeCount = AtomicInteger(0)

        repeat(2) { i ->
            executor!!.submit {
                activeCount.incrementAndGet()
                startLatch.countDown()
                finishLatch.await()
                activeCount.decrementAndGet()
            }
        }

        // Wait for threads to start
        assertTrue(startLatch.await(5, TimeUnit.SECONDS))

        // Should have 2 active threads
        assertEquals(2, activeCount.get())

        // Let threads finish
        finishLatch.countDown()

        // Give threads time to clean up
        Thread.sleep(100)
        assertEquals(0, activeCount.get())
    }

    // =============================================================================
    // Thread Pool with Virtual Threads
    // =============================================================================

    @Test
    fun `should handle many concurrent virtual threads`() {
        executor = Executors.newVirtualThreadPerTaskExecutor()

        val threadCount = 100
        val latch = CountDownLatch(threadCount)
        val counter = AtomicInteger(0)

        repeat(threadCount) {
            executor!!.submit {
                Thread.sleep(10)
                counter.incrementAndGet()
                latch.countDown()
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(threadCount, counter.get())
    }

    @Test
    fun `should execute threads concurrently not sequentially`() {
        executor = Executors.newVirtualThreadPerTaskExecutor()

        val threadCount = 10
        val latch = CountDownLatch(threadCount)
        val sleepTime = 100L

        val startTime = System.currentTimeMillis()

        repeat(threadCount) {
            executor!!.submit {
                Thread.sleep(sleepTime)
                latch.countDown()
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime

        // If executed concurrently, should complete in roughly 100ms
        // If sequential, would take 1000ms (10 * 100ms)
        assertTrue(duration < 500, "Duration was ${duration}ms, expected < 500ms (concurrent execution)")
    }

    // =============================================================================
    // Thread Factory with Custom Names
    // =============================================================================

    @Test
    fun `should create named virtual threads`() {
        val factory = Thread.ofVirtual().name("named-", 0).factory()
        executor = Executors.newThreadPerTaskExecutor(factory)

        val threadNames = java.util.concurrent.CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(3)

        repeat(3) {
            executor!!.submit {
                threadNames.add(Thread.currentThread().name)
                latch.countDown()
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(3, threadNames.size)
        threadNames.forEach { name ->
            assertTrue(name.startsWith("named-"))
        }
    }

    @Test
    fun `should track thread information`() {
        val factory = Thread.ofVirtual().name("info-test-", 0).factory()
        executor = Executors.newThreadPerTaskExecutor(factory)

        val threadInfo = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val latch = CountDownLatch(1)
        val finishLatch = CountDownLatch(1)

        executor!!.submit {
            val thread = Thread.currentThread()
            threadInfo["name"] = 0L
            threadInfo["id"] = thread.threadId()
            threadInfo["isVirtual"] = if (thread.isVirtual) 1L else 0L
            latch.countDown()
            finishLatch.await()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))

        assertTrue(threadInfo["id"]!! > 0)
        assertEquals(1L, threadInfo["isVirtual"])

        finishLatch.countDown()
    }

    // =============================================================================
    // Thread Limits and Semaphore Control
    // =============================================================================

    @Test
    fun `should enforce thread limit with semaphore`() {
        val maxConcurrent = 2
        val semaphore = java.util.concurrent.Semaphore(maxConcurrent)
        executor = Executors.newVirtualThreadPerTaskExecutor()

        val activeCount = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)
        val latch = CountDownLatch(5)

        repeat(5) {
            executor!!.submit {
                semaphore.acquire()
                try {
                    val current = activeCount.incrementAndGet()
                    maxObserved.updateAndGet { max -> maxOf(max, current) }
                    Thread.sleep(50)
                    activeCount.decrementAndGet()
                    latch.countDown()
                } finally {
                    semaphore.release()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(maxObserved.get() <= maxConcurrent,
            "Max concurrent was ${maxObserved.get()}, expected <= $maxConcurrent")
    }

    // =============================================================================
    // Shutdown and Cleanup
    // =============================================================================

    @Test
    fun `should shutdown executor gracefully`() {
        executor = Executors.newVirtualThreadPerTaskExecutor()

        val latch = CountDownLatch(3)

        repeat(3) {
            executor!!.submit {
                Thread.sleep(50)
                latch.countDown()
            }
        }

        executor!!.shutdown()

        // Should wait for tasks to complete
        assertTrue(executor!!.awaitTermination(5, TimeUnit.SECONDS))
        assertTrue(latch.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `should support immediate shutdown`() {
        executor = Executors.newVirtualThreadPerTaskExecutor()

        val startedLatch = CountDownLatch(1)
        val interruptedLatch = CountDownLatch(1)

        executor!!.submit {
            startedLatch.countDown()
            try {
                Thread.sleep(5000)
            } catch (e: InterruptedException) {
                interruptedLatch.countDown()
            }
        }

        // Wait for task to start
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS))

        // Shutdown immediately
        executor!!.shutdownNow()

        // Task should be interrupted
        assertTrue(interruptedLatch.await(1, TimeUnit.SECONDS))
    }
}
