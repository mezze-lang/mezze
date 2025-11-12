package com.funk.language.interpreter.effects

import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkString
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.concurrency.LoomConcurrencyManager

/**
 * Demo class to verify virtual thread integration with effect system
 */
class VirtualThreadIntegrationDemo {
    
    fun demonstrateIntegration(): String {
        val results = mutableListOf<String>()
        
        try {
            // Test 1: Create effect context with virtual thread support
            val concurrencyManager = LoomConcurrencyManager()
            val effectContext = EffectContext.create(
                concurrencyManager = concurrencyManager
            )
            
            results.add("✓ Created EffectContext with virtual thread support")
            
            // Test 2: Test cancellation support
            val cancellationToken = CancellationToken()
            results.add("✓ Created CancellationToken")
            
            cancellationToken.cancel("Demo cancellation")
            if (cancellationToken.isCancelled()) {
                results.add("✓ Cancellation token works correctly")
            }
            
            // Test 3: Test child context creation
            val childContext = effectContext.createChildContext("demo-child")
            if (childContext.executionContext?.threadName == "demo-child") {
                results.add("✓ Child context creation works")
            }
            
            // Test 4: Test virtual thread future handling
            val future = java.util.concurrent.CompletableFuture.completedFuture<FunkValue>(FunkString("demo-result"))
            val contextWithFuture = effectContext.withVirtualThreadFuture("demo-future", future)
            val retrievedFuture = contextWithFuture.getVirtualThreadFuture("demo-future")
            if (retrievedFuture != null && retrievedFuture.get() is FunkString) {
                results.add("✓ Virtual thread future handling works")
            }
            
            // Test 5: Test simple state machine execution
            val stateMachine = EffectStateMachineBuilder()
                .addComputeState("demo-state") { context ->
                    FunkString("Demo execution successful")
                }
                .setFinalStateId("final")
                .build()
            
            val result = stateMachine.execute(effectContext)
            if (result is FunkString && result.value == "Demo execution successful") {
                results.add("✓ State machine execution with virtual threads works")
            }
            
            // Test 6: Test effect handler with virtual threads
            val testHandler = SimpleEffectHandler(
                "DemoEffect",
                mapOf(
                    "test" to VirtualThreadEffectOperation("test") { args, context ->
                        FunkString("Handler executed successfully")
                    }
                )
            )
            
            val handlerResult = testHandler.handleSync("test", FunkString("input"), effectContext)
            if (handlerResult is FunkString && handlerResult.value == "Handler executed successfully") {
                results.add("✓ Effect handler with virtual threads works")
            }
            
            concurrencyManager.shutdown()
            results.add("✓ Concurrency manager shutdown successful")
            
        } catch (e: Exception) {
            results.add("✗ Error: ${e.message}")
            e.printStackTrace()
        }
        
        return results.joinToString("\n")
    }
}

fun main() {
    println("Virtual Thread Effect System Integration Demo")
    println("=".repeat(50))
    
    val demo = VirtualThreadIntegrationDemo()
    val results = demo.demonstrateIntegration()
    
    println(results)
    println("=".repeat(50))
    println("Demo completed")
}