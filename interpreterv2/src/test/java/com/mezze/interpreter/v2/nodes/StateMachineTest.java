package com.mezze.interpreter.v2.nodes;

import com.mezze.interpreter.v2.nodes.literal.IntLiteralNode;
import com.mezze.interpreter.v2.nodes.statemachine.StateMachineNode;
import com.mezze.interpreter.v2.nodes.variable.ReadLocalNode;
import com.mezze.interpreter.v2.nodes.variable.WriteLocalNode;
import com.mezze.interpreter.v2.nodes.continuation.MezzeContinuationSuspended;
import com.mezze.interpreter.v2.values.MezzeUnit;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for state machine support in V2.
 *
 * Run with: ./gradlew :interpreterv2:test --tests "*StateMachineTest*"
 */
public class StateMachineTest {

    /**
     * Test: Simple COMPUTE state that returns a value
     */
    @Test
    void testSimpleComputeState() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int inputSlot = builder.addSlot(FrameSlotKind.Object, "input", null);
        int stateSlot = builder.addSlot(FrameSlotKind.Long, "state", null);
        FrameDescriptor fd = builder.build();

        // Single compute state that returns 42
        MezzeNode[] stateNodes = { new IntLiteralNode(42) };
        int[] stateTypes = { StateMachineNode.STATE_COMPUTE };
        int[] nextStates = { -1 }; // Terminal

        StateMachineNode smNode = new StateMachineNode(
                stateNodes,
                stateTypes,
                nextStates,
                inputSlot,
                stateSlot,
                new int[0],
                new String[0],
                0  // Initial state
        );

        MezzeRootNode root = new MezzeRootNode(null, fd, smNode, "test-sm", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertEquals(42L, result);
    }

    /**
     * Test: COMPLETE state returns final value
     */
    @Test
    void testCompleteState() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int inputSlot = builder.addSlot(FrameSlotKind.Object, "input", null);
        int stateSlot = builder.addSlot(FrameSlotKind.Long, "state", null);
        FrameDescriptor fd = builder.build();

        // Single complete state
        MezzeNode[] stateNodes = { new IntLiteralNode(100) };
        int[] stateTypes = { StateMachineNode.STATE_COMPLETE };
        int[] nextStates = { -1 };

        StateMachineNode smNode = new StateMachineNode(
                stateNodes,
                stateTypes,
                nextStates,
                inputSlot,
                stateSlot,
                new int[0],
                new String[0],
                0
        );

        MezzeRootNode root = new MezzeRootNode(null, fd, smNode, "test-complete", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertEquals(100L, result);
    }

    /**
     * Test: Multi-state COMPUTE chain
     * State 0: compute 10, go to state 1
     * State 1: compute 20, go to state 2
     * State 2: complete with 30
     */
    @Test
    void testMultiStateCompute() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int inputSlot = builder.addSlot(FrameSlotKind.Object, "input", null);
        int stateSlot = builder.addSlot(FrameSlotKind.Long, "state", null);
        FrameDescriptor fd = builder.build();

        MezzeNode[] stateNodes = {
                new IntLiteralNode(10),
                new IntLiteralNode(20),
                new IntLiteralNode(30)
        };
        int[] stateTypes = {
                StateMachineNode.STATE_COMPUTE,
                StateMachineNode.STATE_COMPUTE,
                StateMachineNode.STATE_COMPLETE
        };
        int[] nextStates = { 1, 2, -1 };

        StateMachineNode smNode = new StateMachineNode(
                stateNodes,
                stateTypes,
                nextStates,
                inputSlot,
                stateSlot,
                new int[0],
                new String[0],
                0
        );

        MezzeRootNode root = new MezzeRootNode(null, fd, smNode, "test-chain", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        // Should return the last state's result
        assertEquals(30L, result);
    }

    /**
     * Test: YIELD state suspends execution
     */
    @Test
    void testYieldStateSuspends() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int inputSlot = builder.addSlot(FrameSlotKind.Object, "input", null);
        int stateSlot = builder.addSlot(FrameSlotKind.Long, "state", null);
        FrameDescriptor fd = builder.build();

        // State 0: yield 42, go to state 1
        // State 1: complete with 100
        MezzeNode[] stateNodes = {
                new IntLiteralNode(42),
                new IntLiteralNode(100)
        };
        int[] stateTypes = {
                StateMachineNode.STATE_YIELD,
                StateMachineNode.STATE_COMPLETE
        };
        int[] nextStates = { 1, -1 };

        StateMachineNode smNode = new StateMachineNode(
                stateNodes,
                stateTypes,
                nextStates,
                inputSlot,
                stateSlot,
                new int[0],
                new String[0],
                0
        );

        MezzeRootNode root = new MezzeRootNode(null, fd, smNode, "test-yield", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        // Should return a suspended continuation
        assertTrue(result instanceof MezzeContinuationSuspended);
        MezzeContinuationSuspended suspended = (MezzeContinuationSuspended) result;

        // The yielded value should be 42
        assertEquals(42L, suspended.getSuspendedValue());

        // The continuation should be resumable
        assertNotNull(suspended.getContinuation());
    }

    /**
     * Test: State machine with captured variable
     */
    @Test
    void testCapturedVariable() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int inputSlot = builder.addSlot(FrameSlotKind.Object, "input", null);
        int stateSlot = builder.addSlot(FrameSlotKind.Long, "state", null);
        int xSlot = builder.addSlot(FrameSlotKind.Object, "x", null);
        FrameDescriptor fd = builder.build();

        // State 0: write 50 to x, go to state 1
        // State 1: read x and return it
        MezzeNode[] stateNodes = {
                WriteLocalNode.createObject(new IntLiteralNode(50), xSlot),
                new ReadLocalNode(xSlot, ReadLocalNode.KIND_OBJECT)
        };
        int[] stateTypes = {
                StateMachineNode.STATE_COMPUTE,
                StateMachineNode.STATE_COMPLETE
        };
        int[] nextStates = { 1, -1 };

        StateMachineNode smNode = new StateMachineNode(
                stateNodes,
                stateTypes,
                nextStates,
                inputSlot,
                stateSlot,
                new int[] { xSlot },
                new String[] { "x" },
                0
        );

        MezzeRootNode root = new MezzeRootNode(null, fd, smNode, "test-captured", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertEquals(50L, result);
    }

    /**
     * Performance test for state machine execution
     */
    @Test
    void testStateMachinePerformance() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int inputSlot = builder.addSlot(FrameSlotKind.Object, "input", null);
        int stateSlot = builder.addSlot(FrameSlotKind.Long, "state", null);
        FrameDescriptor fd = builder.build();

        // Chain of 10 compute states
        int numStates = 10;
        MezzeNode[] stateNodes = new MezzeNode[numStates];
        int[] stateTypes = new int[numStates];
        int[] nextStates = new int[numStates];

        for (int i = 0; i < numStates - 1; i++) {
            stateNodes[i] = new IntLiteralNode(i);
            stateTypes[i] = StateMachineNode.STATE_COMPUTE;
            nextStates[i] = i + 1;
        }
        stateNodes[numStates - 1] = new IntLiteralNode(999);
        stateTypes[numStates - 1] = StateMachineNode.STATE_COMPLETE;
        nextStates[numStates - 1] = -1;

        StateMachineNode smNode = new StateMachineNode(
                stateNodes,
                stateTypes,
                nextStates,
                inputSlot,
                stateSlot,
                new int[0],
                new String[0],
                0
        );

        MezzeRootNode root = new MezzeRootNode(null, fd, smNode, "test-perf", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();

        // Warmup
        for (int i = 0; i < 10_000; i++) {
            target.call();
        }

        // Benchmark
        int iterations = 100_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            target.call();
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("State machine (10 states): %d iterations in %d ms (%.2f ns/op)%n",
                iterations, elapsed / 1_000_000, (double) elapsed / iterations);

        // Verify result
        assertEquals(999L, target.call());
    }
}
