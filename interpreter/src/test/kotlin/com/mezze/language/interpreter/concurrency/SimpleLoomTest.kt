package com.mezze.language.interpreter.concurrency

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SimpleLoomTest {
    
    @Test
    fun `should create virtual thread pool manager`() {
        val config = VirtualThreadConfig.forTesting()
        val poolManager = VirtualThreadPoolManager(config)
        
        try {
            assertEquals(0, poolManager.getActiveThreadCount())
            
            val latch = CountDownLatch(1)
            val result = AtomicReference<String>()
            
            val thread = poolManager.startThread("test") {
                result.set("Hello from virtual thread")
                latch.countDown()
            }
            
            assertTrue(thread.isVirtual)
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals("Hello from virtual thread", result.get())
        } finally {
            poolManager.shutdown()
        }
    }
    
    @Test
    fun `should create execution context`() {
        val context = ExecutionContext(
            threadId = 123L,
            threadName = "test-thread",
            metadata = mapOf("key" to "value")
        )
        
        assertEquals(123L, context.threadId)
        assertEquals("test-thread", context.threadName)
        assertEquals("value", context.metadata["key"])
        
        val enhanced = context.withMetadata("new-key", "new-value")
        assertEquals("value", enhanced.metadata["key"])
        assertEquals("new-value", enhanced.metadata["new-key"])
    }
    
    @Test
    fun `should create virtual thread config`() {
        val defaultConfig = VirtualThreadConfig.default()
        assertEquals(Int.MAX_VALUE, defaultConfig.maxVirtualThreads)
        
        val testConfig = VirtualThreadConfig.forTesting()
        assertEquals(1000, testConfig.maxVirtualThreads)
        
        val prodConfig = VirtualThreadConfig.forProduction()
        assertEquals(1_000_000, prodConfig.maxVirtualThreads)
    }
    
    @Test
    fun `should create configured thread factory`() {
        val config = VirtualThreadConfig(threadNamePrefix = "custom")
        val factory = ConfiguredVirtualThreadFactory(config)
        
        val thread1 = factory.newThread { }
        val thread2 = factory.newThread { }
        
        assertTrue(thread1.name.startsWith("custom-"))
        assertTrue(thread2.name.startsWith("custom-"))
        assertNotEquals(thread1.name, thread2.name)
        assertTrue(thread1.isVirtual)
        assertTrue(thread2.isVirtual)
    }
}