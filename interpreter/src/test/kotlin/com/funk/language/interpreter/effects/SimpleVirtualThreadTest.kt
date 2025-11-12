package com.funk.language.interpreter.effects

import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkString
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.concurrency.LoomConcurrencyManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach

class SimpleVirtualThreadTest {
    
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
    fun `should create effect context with virtual thread support`() {
        assertNotNull(effectContext.concurrencyManager)
        assertNotNull(effectContext.cancellationToken)
        assertFalse(effectContext.isCancelled())
    }
    
    @Test
    fun `should support cancellation`() {
        assertFalse(effectContext.isCancelled())
        
        effectContext.cancel("Test cancellation")
        
        assertTrue(effectContext.isCancelled())
        
        assertThrows(EffectCancellationException::class.java) {
            effectContext.throwIfCancelled()
        }
    }
    
    @Test
    fun `should create child contexts`() {
        val childContext = effectContext.createChildContext("child-test")
        
        assertNotNull(childContext.executionContext)
        assertEquals("child-test", childContext.executionContext?.threadName)
        assertNotEquals(effectContext.executionId, childContext.executionId)
    }
    
    @Test
    fun `should handle virtual thread futures`() {
        val futureId = "test-future"
        val future: java.util.concurrent.Future<FunkValue> = java.util.concurrent.CompletableFuture.completedFuture(FunkString("test-result"))
        
        val contextWithFuture = effectContext.withVirtualThreadFuture(futureId, future)
        
        val retrievedFuture = contextWithFuture.getVirtualThreadFuture(futureId)
        assertNotNull(retrievedFuture)
        assertEquals(FunkString("test-result"), retrievedFuture?.get())
    }
    
    @Test
    fun `should execute simple state machine`() {
        val stateMachine = EffectStateMachineBuilder()
            .addComputeState("start") { context ->
                FunkString("Hello Virtual Threads")
            }
            .setFinalStateId("final")
            .build()
        
        val result = stateMachine.execute(effectContext)
        
        assertTrue(result is FunkString)
        assertEquals("Hello Virtual Threads", (result as FunkString).value)
    }
    
    @Test
    fun `should handle effect handlers with virtual threads`() {
        val testHandler = SimpleEffectHandler(
            "TestEffect",
            mapOf(
                "greet" to VirtualThreadEffectOperation("greet") { args, context ->
                    val name = (args["value"] as? FunkString)?.value ?: "World"
                    FunkString("Hello, $name!")
                }
            )
        )

        val contextWithHandler = effectContext.copy(
            activeHandlers = mapOf("TestEffect" to testHandler)
        )

        val result = testHandler.handleSync("greet", FunkString("Funk"), contextWithHandler)

        assertTrue(result is FunkString)
        assertEquals("Hello, Funk!", (result as FunkString).value)
    }
    
    @Test
    fun `should support cancellation tokens`() {
        val token = CancellationToken()
        
        assertFalse(token.isCancelled())
        assertNull(token.getCancellationReason())
        
        token.cancel("Test reason")
        
        assertTrue(token.isCancelled())
        assertEquals("Test reason", token.getCancellationReason())
        
        assertThrows(EffectCancellationException::class.java) {
            token.throwIfCancelled()
        }
    }
    
    @Test
    fun `should support child cancellation tokens`() {
        val parentToken = CancellationToken()
        val childToken = parentToken.createChild()
        
        assertFalse(parentToken.isCancelled())
        assertFalse(childToken.isCancelled())
        
        parentToken.cancel("Parent cancelled")
        
        assertTrue(parentToken.isCancelled())
        assertTrue(childToken.isCancelled())
    }
}