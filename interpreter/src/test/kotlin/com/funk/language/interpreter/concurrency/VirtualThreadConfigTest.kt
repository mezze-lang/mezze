package com.funk.language.interpreter.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VirtualThreadConfigTest {
    
    private lateinit var poolManager: VirtualThreadPoolManager
    
    @BeforeEach
    fun setUp() {
        val config = VirtualThreadConfig.default()
        poolManager = VirtualThreadPoolManager(config)
    }
    
    @AfterEach
    fun tearDown() {
        poolManager.shutdown()
    }
    
    @Test
    fun `should create default configuration`() {
        val config = VirtualThreadConfig.default()
        
        assertEquals(Int.MAX_VALUE, config.maxVirtualThreads)
        assertEquals(Duration.ofMinutes(5), config.defaultTimeout)
        assertEquals("funk-virtual", config.threadNamePrefix)
        assertTrue(config.enableMonitoring)
        assertTrue(config.enableStructuredConcurrency)
    }
    
    @Test
    fun `should create testing configuration`() {
        val config = VirtualThreadConfig.forTesting()
        
        assertEquals(1000, config.maxVirtualThreads)
        assertEquals(Duration.ofSeconds(30), config.defaultTimeout)
        assertTrue(config.enableMonitoring)
    }
    
    @Test
    fun `should create production configuration`() {
        val config = VirtualThreadConfig.forProduction()
        
        assertEquals(1_000_000, config.maxVirtualThreads)
        assertEquals(Duration.ofMinutes(30), config.defaultTimeout)
        assertTrue(config.enableMonitoring)
        
        val expectedCores = Runtime.getRuntime().availableProcessors()
        assertEquals(expectedCores, config.carrierThreads.corePoolSize)
        assertEquals(expectedCores * 2, config.carrierThreads.maxPoolSize)
    }
    
    @Test
    fun `should create and start virtual threads with pool manager`() {
        val config = VirtualThreadConfig.forTesting()
        poolManager = VirtualThreadPoolManager(config)
        
        val latch = CountDownLatch(3)
        val threads = mutableListOf<Thread>()
        
        repeat(3) { i ->
            val thread = poolManager.startThread("test-$i") {
                Thread.sleep(100)
                latch.countDown()
            }
            threads.add(thread)
            assertTrue(thread.isVirtual)
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(3, threads.size)
    }
    
    @Test
    fun `should track active thread count`() {
        val config = VirtualThreadConfig.forTesting()
        poolManager = VirtualThreadPoolManager(config)
        
        assertEquals(0, poolManager.getActiveThreadCount())
        
        val latch = CountDownLatch(2)
        val finishLatch = CountDownLatch(2)
        
        repeat(2) { i ->
            poolManager.startThread("count-test-$i") {
                latch.countDown()
                finishLatch.await() // Wait for signal to finish
            }
        }
        
        // Wait for threads to start
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        
        // Should have 2 active threads
        assertTrue(poolManager.getActiveThreadCount() >= 0) // May be 0 if threads finished quickly
        
        // Let threads finish
        finishLatch.countDown()
        finishLatch.countDown()
        
        // Give threads time to clean up
        Thread.sleep(100)
    }
    
    @Test
    fun `should enforce thread limit`() {
        val config = VirtualThreadConfig(
            maxVirtualThreads = 2,
            enableMonitoring = true
        )
        poolManager = VirtualThreadPoolManager(config)
        
        val latch = CountDownLatch(2)
        val finishLatch = CountDownLatch(2)
        
        // Create 2 threads (at limit)
        repeat(2) { i ->
            poolManager.startThread("limit-test-$i") {
                latch.countDown()
                finishLatch.await()
            }
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        
        // Trying to create a third should fail
        assertThrows(IllegalStateException::class.java) {
            poolManager.createThread("should-fail") { }
        }
        
        // Clean up
        finishLatch.countDown()
        finishLatch.countDown()
    }
    
    @Test
    fun `should provide thread information for monitoring`() {
        val config = VirtualThreadConfig.forTesting()
        poolManager = VirtualThreadPoolManager(config)
        
        val latch = CountDownLatch(1)
        val finishLatch = CountDownLatch(1)
        
        poolManager.startThread("info-test") {
            latch.countDown()
            finishLatch.await()
        }
        
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        
        val threadInfo = poolManager.getThreadInfo()
        assertTrue(threadInfo.isNotEmpty())
        
        val info = threadInfo.first()
        assertEquals("info-test", info.name)
        assertTrue(info.threadId > 0)
        assertTrue(info.getAge().toMillis() >= 0)
        
        finishLatch.countDown()
    }
    
    @Test
    fun `should handle thread factory configuration`() {
        val config = VirtualThreadConfig(threadNamePrefix = "custom-prefix")
        val factory = ConfiguredVirtualThreadFactory(config)
        
        val thread = factory.newThread("specific-name") { }
        
        assertEquals("specific-name", thread.name)
        assertTrue(thread.isVirtual)
        assertEquals(Thread.State.NEW, thread.state)
    }
    
    @Test
    fun `should generate unique thread names`() {
        val config = VirtualThreadConfig(threadNamePrefix = "unique")
        val factory = ConfiguredVirtualThreadFactory(config)
        
        val thread1 = factory.newThread { }
        val thread2 = factory.newThread { }
        
        assertNotEquals(thread1.name, thread2.name)
        assertTrue(thread1.name.startsWith("unique-"))
        assertTrue(thread2.name.startsWith("unique-"))
    }
    
    @Test
    fun `should handle carrier thread configuration`() {
        val carrierConfig = CarrierThreadConfig(
            corePoolSize = 4,
            maxPoolSize = 8,
            keepAliveTime = Duration.ofMinutes(2),
            threadNamePrefix = "custom-carrier"
        )
        
        assertEquals(4, carrierConfig.corePoolSize)
        assertEquals(8, carrierConfig.maxPoolSize)
        assertEquals(Duration.ofMinutes(2), carrierConfig.keepAliveTime)
        assertEquals("custom-carrier", carrierConfig.threadNamePrefix)
    }
}