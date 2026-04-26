package com.mezze.interpreter.benchmark;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.nodes.MezzeRootNode;
import com.mezze.interpreter.nodes.arithmetic.*;
import com.mezze.interpreter.nodes.comparison.*;
import com.mezze.interpreter.nodes.control.*;
import com.mezze.interpreter.nodes.function.*;
import com.mezze.interpreter.nodes.literal.*;
import com.mezze.interpreter.nodes.variable.*;
import com.mezze.interpreter.values.*;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for function calls.
 *
 * These are the critical hot path tests.
 */
public class FunctionCallPerformanceTest {

    private static final int WARMUP_ITERATIONS = 50_000;
    private static final int BENCHMARK_ITERATIONS = 5_000_000;

    /**
     * Test: Baseline - pure Java arithmetic for comparison.
     */
    @Test
    void testBaselineJavaArithmetic() {
        // Warmup
        long sum = 0;
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            sum += (10L + 20L) * (50L - 30L);
        }

        // Benchmark
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            sum += (10L + 20L) * (50L - 30L);
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Baseline Java: %d iterations in %d ms (%.2f ns/op)%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000, (double) elapsed / BENCHMARK_ITERATIONS);

        assertTrue(sum != 0); // Prevent DCE
    }

    /**
     * Test: Simple function with ReadArgumentNode (no frame slots).
     * This tests the overhead of function calls themselves.
     */
    @Test
    void testSimpleFunctionCallWithArguments() {
        // Build: fn(a, b) = a + b
        // Uses ReadArgumentNode to read directly from frame.getArguments()
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();

        // Read arguments directly (closure=0, so param 0 is at index 0)
        MezzeNode readA = new ReadArgumentNode(0);
        MezzeNode readB = new ReadArgumentNode(1);

        MezzeNode add = AddNodeGen.create(readA, readB);

        // Use empty slotTypes to avoid frame slot binding (use ReadArgumentNode pattern)
        MezzeRootNode root = new MezzeRootNode(null, fd, add, "add", 0, 2, new int[0]);
        CallTarget target = root.getCallTarget();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            Object result = target.call(10L, 20L);
            assertEquals(30L, result);
        }

        // Benchmark
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            target.call(10L, 20L);
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Simple function (a+b): %d iterations in %d ms (%.2f ns/op)%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000, (double) elapsed / BENCHMARK_ITERATIONS);

        assertEquals(30L, target.call(10L, 20L));
    }

    /**
     * Test: Function with MezzeFunction value and InvokeNode.
     */
    @Test
    void testMezzeFunctionInvocation() {
        // Create inner function: fn(a, b) = a + b
        FrameDescriptor innerFd = FrameDescriptor.newBuilder().build();
        MezzeNode readA = new ReadArgumentNode(0);
        MezzeNode readB = new ReadArgumentNode(1);
        MezzeNode add = AddNodeGen.create(readA, readB);
        // Use empty slotTypes for ReadArgumentNode pattern
        MezzeRootNode innerRoot = new MezzeRootNode(null, innerFd, add, "add", 0, 2, new int[0]);
        CallTarget innerTarget = innerRoot.getCallTarget();

        // Create MezzeFunction (no closure values)
        MezzeFunction addFunc = new MezzeFunction(innerTarget, "add", new Object[0]);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            Object[] args = new Object[]{10L, 20L};
            Object result = innerTarget.call(args);
            assertEquals(30L, result);
        }

        // Benchmark direct CallTarget call
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            innerTarget.call(10L, 20L);
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Direct CallTarget: %d iterations in %d ms (%.2f ns/op)%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000, (double) elapsed / BENCHMARK_ITERATIONS);
    }

    /**
     * Test: Function with closure values.
     */
    @Test
    void testClosurePerformance() {
        // Create function: fn(a) = a + capturedValue
        // Where capturedValue is in closure slot 0
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();

        // Closure value at index 0, parameter at index 1
        MezzeNode readCaptured = new ReadArgumentNode(0);
        MezzeNode readParam = new ReadArgumentNode(1);
        MezzeNode add = AddNodeGen.create(readCaptured, readParam);

        // Use empty slotTypes for ReadArgumentNode pattern
        MezzeRootNode root = new MezzeRootNode(null, fd, add, "addClosure", 1, 1, new int[0]);
        CallTarget target = root.getCallTarget();

        // Create MezzeFunction with captured value
        MezzeFunction func = new MezzeFunction(target, "addClosure", new Object[]{100L}); // captured = 100

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            Object[] args = new Object[]{100L, 50L}; // closure + param
            Object result = target.call(args);
            assertEquals(150L, result);
        }

        // Benchmark
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            // Simulate what InvokeNode does
            Object[] closureValues = func.getClosureValues();
            Object[] args = new Object[closureValues.length + 1];
            args[0] = closureValues[0];
            args[1] = 50L;
            target.call(args);
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Closure function: %d iterations in %d ms (%.2f ns/op)%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000, (double) elapsed / BENCHMARK_ITERATIONS);
    }

    /**
     * Test: If-then-else with different branch taken.
     */
    @Test
    void testBranchPrediction() {
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();

        // Build: if n > 0 then n else -n
        MezzeNode readN = new ReadArgumentNode(0);
        MezzeNode zero = new IntLiteralNode(0L);
        MezzeNode condition = GreaterThanNodeGen.create(readN, zero);
        MezzeNode negateN = NegateNodeGen.create(new ReadArgumentNode(0));

        MezzeNode ifNode = IfNode.create(condition, new ReadArgumentNode(0), negateN);

        // Use empty slotTypes for ReadArgumentNode pattern
        MezzeRootNode root = new MezzeRootNode(null, fd, ifNode, "abs", 0, 1, new int[0]);
        CallTarget target = root.getCallTarget();

        // Warmup with biased condition (mostly positive)
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            target.call((long) (i % 100) - 10); // Mix of positive and negative
        }

        // Benchmark with consistent positive values (predictable branch)
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            target.call(42L);
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Predictable branch: %d iterations in %d ms (%.2f ns/op)%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000, (double) elapsed / BENCHMARK_ITERATIONS);

        // Verify correctness
        assertEquals(42L, target.call(42L));
        assertEquals(42L, target.call(-42L));
    }

    /**
     * Test: Record creation and field access together.
     */
    @Test
    void testRecordWorkflow() {
        // Create shape
        String[] fieldNames = {"x", "y"};
        MezzeRecord.FieldLocation[] locations = new MezzeRecord.FieldLocation[2];
        locations[0] = new MezzeRecord.FieldLocation(MezzeRecord.FieldLocation.StorageType.LONG, 0);
        locations[1] = new MezzeRecord.FieldLocation(MezzeRecord.FieldLocation.StorageType.LONG, 1);
        MezzeRecord.Shape shape = new MezzeRecord.Shape(fieldNames, locations);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            // Create record
            MezzeRecord point = new MezzeRecord(shape, new long[]{i, i * 2}, new double[0], new Object[0]);
            // Access fields
            long x = point.getLongField(0);
            long y = point.getLongField(1);
            assertEquals(i, x);
            assertEquals(i * 2, y);
        }

        // Benchmark
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            // Create record (simulates RecordLiteralNode output)
            MezzeRecord point = new MezzeRecord(shape, new long[]{10L, 20L}, new double[0], new Object[0]);
            // Access fields (simulates RecordFieldAccessNode)
            sum += point.getLongField(0) + point.getLongField(1);
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Record create+access: %d iterations in %d ms (%.2f ns/op)%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000, (double) elapsed / BENCHMARK_ITERATIONS);

        assertTrue(sum > 0);
    }
}
