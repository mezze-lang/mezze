package com.funk.language.interpreter

import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class StateMachineExecutionTest {
    private lateinit var executor: StateMachineExecutor

    @BeforeEach
    fun setUp() {
        executor = StateMachineExecutor()
    }

    @Test
    fun testStateMachineCreation() {
        val variables = mutableMapOf<String, FunkValue>(
            "x" to FunkInt(42),
            "y" to FunkString("test")
        )
        
        val effectContext = EffectContext(
            handlers = mapOf("Log" to FunkRecord(emptyMap()))
        )
        
        val stateMachine = StateMachine.create(variables, effectContext)
        
        assertEquals(0, stateMachine.currentState)
        assertEquals(variables, stateMachine.localVariables)
        assertEquals(effectContext, stateMachine.effectContext)
        assertEquals(ExecutionState.RUNNING, stateMachine.executionState)
        assertTrue(stateMachine.continuations.isEmpty())
    }

    @Test
    fun testStateMachineVariableManagement() {
        val stateMachine = StateMachine.create()
        
        val withVariable = stateMachine.withVariable("test", FunkString("value"))
        
        assertEquals(FunkString("value"), withVariable.localVariables["test"])
        assertNotSame(stateMachine, withVariable) // Should create new instance
    }

    @Test
    fun testStateMachineSuspension() {
        val stateMachine = StateMachine.create()
        val continuation = LetContinuation("result", 1)
        
        val suspended = stateMachine.suspend(continuation)
        
        assertEquals(ExecutionState.SUSPENDED, suspended.executionState)
        assertEquals(1, suspended.continuations.size)
        assertEquals(continuation, suspended.continuations.first())
    }

    @Test
    fun testStateMachineResumption() {
        val stateMachine = StateMachine.create()
        val continuation = LetContinuation("result", 1)
        val suspended = stateMachine.suspend(continuation)
        
        val resumed = suspended.resume(FunkInt(42))
        
        assertEquals(ExecutionState.RUNNING, resumed.executionState)
        assertEquals(FunkInt(42), resumed.localVariables["result"])
        assertTrue(resumed.continuations.isEmpty())
    }

    @Test
    fun testStateMachineErrorHandling() {
        val stateMachine = StateMachine.create()
        val continuation = LetContinuation("result", 1)
        val suspended = stateMachine.suspend(continuation)
        
        val error = RuntimeException("Test error")
        val failed = suspended.handleError(error)
        
        assertEquals(ExecutionState.FAILED, failed.executionState)
    }

    @Test
    fun testLetContinuation() {
        val continuation = LetContinuation("x", 2)
        val stateMachine = StateMachine.create()
        
        val resumed = continuation.resume(FunkString("value"), stateMachine)
        
        assertEquals(FunkString("value"), resumed.localVariables["x"])
        assertEquals(2, resumed.currentState)
        assertEquals(ExecutionState.RUNNING, resumed.executionState)
    }

    @Test
    fun testExpressionContinuation() {
        val continuation = ExpressionContinuation(3)
        val stateMachine = StateMachine.create()
        
        val resumed = continuation.resume(FunkNull, stateMachine)
        
        assertEquals(3, resumed.currentState)
        assertEquals(ExecutionState.RUNNING, resumed.executionState)
    }

    @Test
    fun testResultContinuation() {
        val continuation = ResultContinuation(FunkString("final"))
        val stateMachine = StateMachine.create()
        
        val completed = continuation.resume(FunkNull, stateMachine)
        
        assertEquals(ExecutionState.COMPLETED, completed.executionState)
    }

    @Test
    fun testSuspensionPoint() {
        val stateMachine = StateMachine.create()
        val asyncOperation: CompletableFuture<FunkValue> = CompletableFuture.completedFuture(FunkInt(100))
        
        val suspensionPoint = SuspensionPoint(stateMachine, asyncOperation)
        
        val completed = suspensionPoint.complete(FunkInt(100))
        assertNotNull(completed)
    }

    @Test
    fun testSuspensionPointError() {
        val stateMachine = StateMachine.create()
        val asyncOperation = CompletableFuture<FunkValue>()
        
        val suspensionPoint = SuspensionPoint(stateMachine, asyncOperation)
        val error = RuntimeException("Async error")
        
        val failed = suspensionPoint.completeExceptionally(error)
        assertEquals(ExecutionState.FAILED, failed.executionState)
    }

    @Test
    fun testStateMachineExecutorExecution() {
        val stateMachine = StateMachine.create()
        
        val future = executor.execute(stateMachine)
        val result = future.get(1, TimeUnit.SECONDS)
        
        assertNotNull(result)
        // Should complete with FunkNull for empty state machine
        assertTrue(result is FunkNull)
    }

    @Test
    fun testStateMachineExecutorSuspension() {
        val stateMachine = StateMachine.create()
        val asyncOperation = CompletableFuture<FunkValue>()
        
        // Start suspension
        val future = executor.suspend(stateMachine, asyncOperation)
        
        // Complete the async operation
        asyncOperation.complete(FunkString("async result"))
        
        val result = future.get(1, TimeUnit.SECONDS)
        assertNotNull(result)
    }

    @Test
    fun testStateMachineExecutorStatus() {
        val stateMachine1 = StateMachine.create()
        val stateMachine2 = StateMachine.create()
        
        executor.execute(stateMachine1)
        executor.execute(stateMachine2)
        
        // Give the state machines time to complete
        Thread.sleep(100)
        
        val status = executor.getStatus()
        assertTrue(status.isNotEmpty())
        // Empty state machines remain in RUNNING state until they have work to complete
        assertTrue(status.values.all { it == ExecutionState.RUNNING })
    }

    @Test
    fun testEffectContextHandlerManagement() {
        val handler1 = FunkRecord(mapOf("op1" to FunkString("handler1")))
        val handler2 = FunkRecord(mapOf("op2" to FunkString("handler2")))
        
        val effectContext = EffectContext()
        val withHandlers = effectContext.withHandlers(mapOf(
            "Effect1" to handler1,
            "Effect2" to handler2
        ))
        
        assertEquals(handler1, withHandlers.handlers["Effect1"])
        assertEquals(handler2, withHandlers.handlers["Effect2"])
        assertNotSame(effectContext, withHandlers) // Should create new instance
    }

    @Test
    fun testEffectContextCurrentHandler() {
        val handler = FunkRecord(mapOf("operation" to FunkString("test")))
        val effectContext = EffectContext()
        
        val withCurrentHandler = effectContext.withCurrentHandler(handler)
        
        assertEquals(handler, withCurrentHandler.currentHandler)
        assertNotSame(effectContext, withCurrentHandler)
    }

    @Test
    fun testMultipleContinuations() {
        val stateMachine = StateMachine.create()
        val continuation1 = LetContinuation("x", 1)
        val continuation2 = ExpressionContinuation(2)
        
        val suspended1 = stateMachine.suspend(continuation1)
        val suspended2 = suspended1.suspend(continuation2)
        
        assertEquals(2, suspended2.continuations.size)
        assertEquals(continuation1, suspended2.continuations[0])
        assertEquals(continuation2, suspended2.continuations[1])
        
        // Resume should process the last continuation
        val resumed = suspended2.resume(FunkInt(42))
        assertEquals(1, resumed.continuations.size) // One continuation removed
        assertEquals(2, resumed.currentState) // State updated by ExpressionContinuation
    }

    @Test
    fun testStateMachineStateTransitions() {
        val stateMachine = StateMachine.create()
        assertEquals(ExecutionState.RUNNING, stateMachine.executionState)
        
        val continuation = LetContinuation("x", 1)
        val suspended = stateMachine.suspend(continuation)
        assertEquals(ExecutionState.SUSPENDED, suspended.executionState)
        
        val resumed = suspended.resume(FunkInt(42))
        assertEquals(ExecutionState.RUNNING, resumed.executionState)
        
        val error = RuntimeException("Test")
        val failed = resumed.handleError(error)
        assertEquals(ExecutionState.FAILED, failed.executionState)
    }

    @Test
    fun testStateMachineImmutability() {
        val originalVariables = mutableMapOf<String, FunkValue>("x" to FunkInt(1))
        val stateMachine = StateMachine.create(originalVariables)
        
        val modified = stateMachine.withVariable("y", FunkString("test"))
        
        // Original should be unchanged
        assertFalse(stateMachine.localVariables.containsKey("y"))
        // Modified should have new variable
        assertTrue(modified.localVariables.containsKey("y"))
        assertEquals(FunkString("test"), modified.localVariables["y"])
    }
}
