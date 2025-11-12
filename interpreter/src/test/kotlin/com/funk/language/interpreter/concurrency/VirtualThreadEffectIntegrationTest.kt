package com.funk.language.interpreter.effects

import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkString
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.concurrency.LoomConcurrencyManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class VirtualThreadEffectIntegrationTest {
    
    private lateinit var concurrencyManager: LoomConcurrencyManager
    private lateinit var effectContext: EffectContext
    
    @BeforeEach
    fun setUp() {
        concurrencyManager = LoomConcurrencyManager()
        effectContext = EffectContext.create(
            concurrencyManager = concurrencyManager
        )
    }
    
    @AfterEach
    fun tearDown() {
        concurrencyManager.shutdown()
    }
    
    @Test
    fun `should execute simple effect state machine with virtual threads`() {
        // Create a simple computation state
        val stateMachine = EffectStateMachineBuilder()
            .addComputeState("compute1") { context ->
                FunkString("Hello from virtual thread")
            }
            .addComputeState("compute2") { context ->
                val previous = context.getVariable("state_compute1_result") as? FunkString
                FunkString("${previous?.value} - processed")
            }
            .setFinalStateId("final")
            .build()
        
        val result = stateMachine.execute(effectContext)
        
        assertTrue(result is FunkString)
        assertEquals("Hello from virtual thread - processed", (result as FunkString).value)
    }
    
    @Test
    fun `should handle effect operations in virtual threads`() {
        val counter = AtomicInteger(0)
        
        // Create a test effect handler
        val testHandler = SimpleEffectHandler(
            "TestEffect",
            mapOf(
                "increment" to VirtualThreadEffectOperation("increment") { args, context ->
                    val current = counter.incrementAndGet()
                    FunkInt(current.toLong())
                }
            )
        )
        
        val contextWithHandler = effectContext.copy(
            activeHandlers = mapOf("TestEffect" to testHandler)
        )
        
        // Create state machine with effect call
        val effectCall = EffectCall(
            effectName = "TestEffect",
            operation = "increment",
            arguments = FunkInt(1L),
            context = contextWithHandler
        )
        
        val stateMachine = EffectStateMachineBuilder()
            .addAsyncEffectState("effect1", effectCall, "final")
            .setFinalStateId("final")
            .build()
        
        val result = stateMachine.execute(contextWithHandler)
        
        assertTrue(result is FunkInt)
        assertEquals(1, (result as FunkInt).value)
        assertEquals(1, counter.get())
    }
    
    @Test
    fun `should support cancellation of effect operations`() {
        val latch = CountDownLatch(1)
        val cancelled = AtomicReference<Boolean>(false)
        
        // Create a long-running effect handler
        val longRunningHandler = SimpleEffectHandler(
            "LongRunning",
            mapOf(
                "sleep" to VirtualThreadEffectOperation("sleep") { args, context ->
                    // Check for cancellation periodically instead of relying on thread interruption
                    val startTime = System.currentTimeMillis()
                    val duration = 5000L
                    
                    while (System.currentTimeMillis() - startTime < duration) {
                        if (context.cancellationToken.isCancelled()) {
                            cancelled.set(true)
                            latch.countDown()
                            throw EffectCancellationException("Operation was cancelled")
                        }
                        try {
                            Thread.sleep(50) // Check every 50ms
                        } catch (e: InterruptedException) {
                            cancelled.set(true)
                            latch.countDown()
                            throw EffectCancellationException("Operation was cancelled")
                        }
                    }
                    FunkString("Completed")
                }
            )
        )
        
        val contextWithHandler = effectContext.copy(
            activeHandlers = mapOf("LongRunning" to longRunningHandler)
        )
        
        val effectCall = EffectCall(
            effectName = "LongRunning",
            operation = "sleep",
            arguments = FunkInt(5000L),
            context = contextWithHandler
        )
        
        val stateMachine = EffectStateMachineBuilder()
            .addAsyncEffectState("sleep1", effectCall, "final")
            .setFinalStateId("final")
            .build()
        
        // Execute in a separate thread so we can cancel it
        val executionThread = Thread {
            try {
                stateMachine.execute(contextWithHandler)
            } catch (e: Exception) {
                // Expected due to cancellation
            }
        }
        
        executionThread.start()
        Thread.sleep(100) // Let execution start
        
        // Cancel the context
        contextWithHandler.cancel("Test cancellation")
        
        // Wait for cancellation to take effect
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(cancelled.get())
        
        executionThread.interrupt()
        executionThread.join(1000)
    }
    
    @Test
    fun `should execute parallel effect operations`() {
        val executor = VirtualThreadStateMachineExecutor(concurrencyManager)
        val counter = AtomicInteger(0)
        
        // Create multiple state machines
        val stateMachines = (1..5).map { i ->
            EffectStateMachineBuilder()
                .addComputeState("compute$i") { context ->
                    Thread.sleep(100) // Simulate work
                    val current = counter.incrementAndGet()
                    FunkInt(current.toLong())
                }
                .setFinalStateId("final")
                .build()
        }
        
        val contexts = stateMachines.map { effectContext }
        
        val startTime = System.currentTimeMillis()
        val results = executor.executeParallel(stateMachines, contexts)
        val endTime = System.currentTimeMillis()
        
        assertEquals(5, results.size)
        assertEquals(5, counter.get())
        
        // Should complete in roughly 100ms (parallel) not 500ms (sequential)
        val duration = endTime - startTime
        assertTrue(duration < 300, "Duration was ${duration}ms, expected < 300ms")
        
        // All results should be FunkInt
        results.forEach { result ->
            assertTrue(result is FunkInt)
        }
    }
    
    @Test

    fun `should handle structured concurrency scopes`() {
        val results = java.util.concurrent.CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(3)

        concurrencyManager.withStructuredScope("test-scope") { scope ->
            // Submit multiple tasks
            val futures = (1..3).map { i ->
                scope.submit {
                    Thread.sleep(50)
                    results.add("Task $i")
                    latch.countDown()
                    "Result $i"
                }
            }

            // Wait for all tasks - this ensures all tasks complete
            futures.map { it.get() }
        }

        val latchCompleted = latch.await(2, TimeUnit.SECONDS)
        println("Latch completed: $latchCompleted")
        println("Results size: ${results.size}")
        println("Results: $results")
        assertTrue(latchCompleted, "Latch did not complete in time")
        assertEquals(3, results.size, "Expected 3 results but got ${results.size}: $results")
        assertTrue(results.contains("Task 1"))
        assertTrue(results.contains("Task 2"))
        assertTrue(results.contains("Task 3"))
    }
    
    @Test
    fun `should preserve execution context across virtual threads`() {
        val contextRef = AtomicReference<String>()
        val latch = CountDownLatch(1)
        
        val testHandler = SimpleEffectHandler(
            "ContextTest",
            mapOf(
                "getContext" to VirtualThreadEffectOperation("getContext") { args, context ->
                    val executionContext = context.executionContext
                    contextRef.set(executionContext?.threadName)
                    latch.countDown()
                    FunkString(executionContext?.threadName ?: "no-context")
                }
            )
        )
        
        val contextWithHandler = effectContext.copy(
            activeHandlers = mapOf("ContextTest" to testHandler)
        )
        
        val effectCall = EffectCall(
            effectName = "ContextTest",
            operation = "getContext",
            arguments = FunkString("test"),
            context = contextWithHandler
        )
        
        val stateMachine = EffectStateMachineBuilder()
            .addAsyncEffectState("context1", effectCall, "final")
            .setFinalStateId("final")
            .build()
        
        val result = stateMachine.execute(contextWithHandler)
        
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertTrue(result is FunkString)
        
        // The context should contain information about the virtual thread
        val contextName = contextRef.get()
        assertNotNull(contextName)
        assertTrue(contextName!!.contains("effect"))
    }
}