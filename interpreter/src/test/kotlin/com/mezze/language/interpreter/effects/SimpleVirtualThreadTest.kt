package com.mezze.language.interpreter.effects

import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeString
import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.concurrency.LoomConcurrencyManager
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
        val future: java.util.concurrent.Future<MezzeValue> = java.util.concurrent.CompletableFuture.completedFuture(MezzeString("test-result"))
        
        val contextWithFuture = effectContext.withVirtualThreadFuture(futureId, future)
        
        val retrievedFuture = contextWithFuture.getVirtualThreadFuture(futureId)
        assertNotNull(retrievedFuture)
        assertEquals(MezzeString("test-result"), retrievedFuture?.get())
    }
    
    @Test
    fun `should execute simple state machine`() {
        val stateMachine = EffectStateMachineBuilder()
            .addComputeState("start") { context ->
                MezzeString("Hello Virtual Threads")
            }
            .setFinalStateId("final")
            .build()
        
        val result = stateMachine.execute(effectContext)
        
        assertTrue(result is MezzeString)
        assertEquals("Hello Virtual Threads", (result as MezzeString).value)
    }
    
    @Test
    fun `should handle effect handlers with virtual threads`() {
        val testHandler = SimpleEffectHandler(
            "TestEffect",
            mapOf(
                "greet" to VirtualThreadEffectOperation("greet") { args, context ->
                    val name = (args["value"] as? MezzeString)?.value ?: "World"
                    MezzeString("Hello, $name!")
                }
            )
        )

        val contextWithHandler = effectContext.copy(
            activeHandlers = mapOf("TestEffect" to testHandler)
        )

        val result = testHandler.handleSync("greet", MezzeString("Mezze"), contextWithHandler)

        assertTrue(result is MezzeString)
        assertEquals("Hello, Mezze!", (result as MezzeString).value)
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