package com.mezze.language.interpreter

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class StateMachineExecutionTest {
    private lateinit var executor: StateMachineExecutor

    @BeforeEach
    fun setUp() {
        executor = StateMachineExecutor()
        // Set project root for stdlib module resolution
        TestUtils.setupProjectRoot()
        // Clear module cache for fresh resolution
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @Test
    fun testStateMachineCreation() {
        val variables = mutableMapOf<String, MezzeValue>(
            "x" to MezzeInt(42),
            "y" to MezzeString("test")
        )
        
        val effectContext = StateMachineEffectContext(
            handlers = mapOf("Log" to MezzeRecord(emptyMap()))
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
        
        val withVariable = stateMachine.withVariable("test", MezzeString("value"))
        
        assertEquals(MezzeString("value"), withVariable.localVariables["test"])
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
        
        val resumed = suspended.resume(MezzeInt(42))
        
        assertEquals(ExecutionState.RUNNING, resumed.executionState)
        assertEquals(MezzeInt(42), resumed.localVariables["result"])
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
        
        val resumed = continuation.resume(MezzeString("value"), stateMachine)
        
        assertEquals(MezzeString("value"), resumed.localVariables["x"])
        assertEquals(2, resumed.currentState)
        assertEquals(ExecutionState.RUNNING, resumed.executionState)
    }

    @Test
    fun testExpressionContinuation() {
        val continuation = ExpressionContinuation(3)
        val stateMachine = StateMachine.create()
        
        val resumed = continuation.resume(MezzeNull, stateMachine)
        
        assertEquals(3, resumed.currentState)
        assertEquals(ExecutionState.RUNNING, resumed.executionState)
    }

    @Test
    fun testResultContinuation() {
        val continuation = ResultContinuation(MezzeString("final"))
        val stateMachine = StateMachine.create()
        
        val completed = continuation.resume(MezzeNull, stateMachine)
        
        assertEquals(ExecutionState.COMPLETED, completed.executionState)
    }

    @Test
    fun testStateMachineSuspensionPoint() {
        val stateMachine = StateMachine.create()
        val asyncOperation: CompletableFuture<MezzeValue> = CompletableFuture.completedFuture(MezzeInt(100))
        
        val suspensionPoint = StateMachineSuspensionPoint(stateMachine, asyncOperation)
        
        val completed = suspensionPoint.complete(MezzeInt(100))
        assertNotNull(completed)
    }

    @Test
    fun testStateMachineSuspensionPointError() {
        val stateMachine = StateMachine.create()
        val asyncOperation = CompletableFuture<MezzeValue>()
        
        val suspensionPoint = StateMachineSuspensionPoint(stateMachine, asyncOperation)
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
        // Should complete with MezzeNull for empty state machine
        assertTrue(result is MezzeNull)
    }

    @Test
    fun testStateMachineExecutorSuspension() {
        val stateMachine = StateMachine.create()
        val asyncOperation = CompletableFuture<MezzeValue>()
        
        // Start suspension
        val future = executor.suspend(stateMachine, asyncOperation)
        
        // Complete the async operation
        asyncOperation.complete(MezzeString("async result"))
        
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
    fun testStateMachineEffectContextHandlerManagement() {
        val handler1 = MezzeRecord(mapOf("op1" to MezzeString("handler1")))
        val handler2 = MezzeRecord(mapOf("op2" to MezzeString("handler2")))
        
        val effectContext = StateMachineEffectContext()
        val withHandlers = effectContext.withHandlers(mapOf(
            "Effect1" to handler1,
            "Effect2" to handler2
        ))
        
        assertEquals(handler1, withHandlers.handlers["Effect1"])
        assertEquals(handler2, withHandlers.handlers["Effect2"])
        assertNotSame(effectContext, withHandlers) // Should create new instance
    }

    @Test
    fun testStateMachineEffectContextCurrentHandler() {
        val handler = MezzeRecord(mapOf("operation" to MezzeString("test")))
        val effectContext = StateMachineEffectContext()
        
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
        val resumed = suspended2.resume(MezzeInt(42))
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
        
        val resumed = suspended.resume(MezzeInt(42))
        assertEquals(ExecutionState.RUNNING, resumed.executionState)
        
        val error = RuntimeException("Test")
        val failed = resumed.handleError(error)
        assertEquals(ExecutionState.FAILED, failed.executionState)
    }

    @Test
    fun testStateMachineImmutability() {
        val originalVariables = mutableMapOf<String, MezzeValue>("x" to MezzeInt(1))
        val stateMachine = StateMachine.create(originalVariables)
        
        val modified = stateMachine.withVariable("y", MezzeString("test"))
        
        // Original should be unchanged
        assertFalse(stateMachine.localVariables.containsKey("y"))
        // Modified should have new variable
        assertTrue(modified.localVariables.containsKey("y"))
        assertEquals(MezzeString("test"), modified.localVariables["y"])
    }
}
