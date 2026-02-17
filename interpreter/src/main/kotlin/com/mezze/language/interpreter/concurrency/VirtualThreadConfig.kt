package com.mezze.language.interpreter.concurrency

import java.time.Duration
import java.util.concurrent.ThreadFactory

/**
 * Configuration for virtual thread management and pool settings
 */
data class VirtualThreadConfig(
    val maxVirtualThreads: Int = Int.MAX_VALUE,
    val defaultTimeout: Duration = Duration.ofMinutes(5),
    val threadNamePrefix: String = "mezze-virtual",
    val enableMonitoring: Boolean = true,
    val enableStructuredConcurrency: Boolean = true,
    val carrierThreads: CarrierThreadConfig = CarrierThreadConfig()
) {
    companion object {
        fun default() = VirtualThreadConfig()
        
        fun forTesting() = VirtualThreadConfig(
            maxVirtualThreads = 1000,
            defaultTimeout = Duration.ofSeconds(30),
            enableMonitoring = true
        )
        
        fun forProduction() = VirtualThreadConfig(
            maxVirtualThreads = 1_000_000,
            defaultTimeout = Duration.ofMinutes(30),
            enableMonitoring = true,
            carrierThreads = CarrierThreadConfig(
                corePoolSize = Runtime.getRuntime().availableProcessors(),
                maxPoolSize = Runtime.getRuntime().availableProcessors() * 2
            )
        )
    }
}

/**
 * Configuration for the underlying carrier thread pool
 */
data class CarrierThreadConfig(
    val corePoolSize: Int = Runtime.getRuntime().availableProcessors(),
    val maxPoolSize: Int = Runtime.getRuntime().availableProcessors() * 2,
    val keepAliveTime: Duration = Duration.ofMinutes(1),
    val threadNamePrefix: String = "mezze-carrier"
)

/**
 * Factory for creating configured virtual threads
 */
class ConfiguredVirtualThreadFactory(
    private val config: VirtualThreadConfig
) : ThreadFactory {
    private val threadCounter = java.util.concurrent.atomic.AtomicLong(0)
    
    override fun newThread(r: Runnable): Thread {
        val threadName = "${config.threadNamePrefix}-${threadCounter.incrementAndGet()}"
        
        return Thread.ofVirtual()
            .name(threadName)
            .unstarted(r)
    }
    
    fun newThread(name: String, r: Runnable): Thread {
        return Thread.ofVirtual()
            .name(name)
            .unstarted(r)
    }
}

/**
 * Virtual thread pool manager with resource limits and monitoring
 */
class VirtualThreadPoolManager(
    private val config: VirtualThreadConfig
) {
    private val activeThreads = java.util.concurrent.ConcurrentHashMap<Long, VirtualThreadInfo>()
    private val threadFactory = ConfiguredVirtualThreadFactory(config)
    
    fun createThread(name: String? = null, task: Runnable): Thread {
        checkThreadLimit()
        
        val threadName = name ?: generateThreadName()
        val thread = threadFactory.newThread(threadName, wrapTask(task, threadName))
        
        if (config.enableMonitoring) {
            val info = VirtualThreadInfo(
                threadId = thread.threadId(),
                name = threadName,
                createdAt = System.nanoTime(),
                state = Thread.State.NEW
            )
            activeThreads[thread.threadId()] = info
        }
        
        return thread
    }
    
    fun startThread(name: String? = null, task: Runnable): Thread {
        val thread = createThread(name, task)
        thread.start()
        return thread
    }
    
    private fun checkThreadLimit() {
        if (activeThreads.size >= config.maxVirtualThreads) {
            throw IllegalStateException(
                "Maximum virtual thread limit reached: ${config.maxVirtualThreads}"
            )
        }
    }
    
    private fun generateThreadName(): String {
        return "${config.threadNamePrefix}-${System.nanoTime()}"
    }
    
    private fun wrapTask(task: Runnable, threadName: String): Runnable {
        return Runnable {
            try {
                updateThreadState(Thread.currentThread().threadId(), Thread.State.RUNNABLE)
                task.run()
            } catch (e: Exception) {
                updateThreadState(Thread.currentThread().threadId(), Thread.State.TERMINATED)
                throw e
            } finally {
                if (config.enableMonitoring) {
                    activeThreads.remove(Thread.currentThread().threadId())
                }
            }
        }
    }
    
    private fun updateThreadState(threadId: Long, state: Thread.State) {
        if (config.enableMonitoring) {
            activeThreads[threadId]?.let { info ->
                activeThreads[threadId] = info.copy(state = state)
            }
        }
    }
    
    fun getActiveThreadCount(): Int = activeThreads.size
    
    fun getThreadInfo(): List<VirtualThreadInfo> = activeThreads.values.toList()
    
    fun shutdown() {
        // Cancel all active threads
        activeThreads.keys.forEach { threadId ->
            Thread.getAllStackTraces().keys
                .find { it.threadId() == threadId }
                ?.interrupt()
        }
        activeThreads.clear()
    }
}

/**
 * Information about a virtual thread for monitoring
 */
data class VirtualThreadInfo(
    val threadId: Long,
    val name: String,
    val createdAt: Long,
    val state: Thread.State
) {
    fun getAge(): Duration = Duration.ofNanos(System.nanoTime() - createdAt)
}