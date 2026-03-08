package com.mezze.interpreter.v2.benchmark;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.nodes.MezzeRootNode;
import com.mezze.interpreter.v2.nodes.arithmetic.*;
import com.mezze.interpreter.v2.nodes.comparison.*;
import com.mezze.interpreter.v2.nodes.control.*;
import com.mezze.interpreter.v2.nodes.literal.*;
import com.mezze.interpreter.v2.nodes.variable.*;
import com.mezze.interpreter.v2.values.*;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for interpreter v2 nodes.
 *
 * These tests verify that nodes execute correctly and measure performance.
 * Run with: ./gradlew :interpreterv2:test --tests "*NodePerformanceTest*"
 *
 * For JIT compilation analysis, add JVM flags:
 *   -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation
 */
public class NodePerformanceTest {

    private static final int WARMUP_ITERATIONS = 10_000;
    private static final int BENCHMARK_ITERATIONS = 1_000_000;

    /**
     * Test: Integer arithmetic should stay unboxed.
     * After JIT: No allocations, pure register operations.
     */
    @Test
    void testIntegerArithmeticPerformance() {
        // Build: (a + b) * (c - d)
        // Frame layout: slot 0 = a, slot 1 = b, slot 2 = c, slot 3 = d (all Long)
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotA = builder.addSlot(FrameSlotKind.Long, "a", null);
        int slotB = builder.addSlot(FrameSlotKind.Long, "b", null);
        int slotC = builder.addSlot(FrameSlotKind.Long, "c", null);
        int slotD = builder.addSlot(FrameSlotKind.Long, "d", null);
        FrameDescriptor fd = builder.build();

        // Create read nodes for typed Long slots
        MezzeNode readA = new ReadLocalNode(slotA, ReadLocalNode.KIND_LONG);
        MezzeNode readB = new ReadLocalNode(slotB, ReadLocalNode.KIND_LONG);
        MezzeNode readC = new ReadLocalNode(slotC, ReadLocalNode.KIND_LONG);
        MezzeNode readD = new ReadLocalNode(slotD, ReadLocalNode.KIND_LONG);

        // Build expression tree: (a + b) * (c - d)
        MezzeNode add = AddNodeGen.create(readA, readB);
        MezzeNode sub = SubtractNodeGen.create(readC, readD);
        MezzeNode mul = MultiplyNodeGen.create(add, sub);

        // Create root node with typed slot types
        int[] slotTypes = {0, 0, 0, 0}; // All Long
        MezzeRootNode root = new MezzeRootNode(null, fd, mul, "arithmetic", 0, 4, slotTypes);
        CallTarget target = root.getCallTarget();

        // Warmup - allows JIT to compile
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            // Arguments: a=10, b=20, c=50, d=30
            Object result = target.call(10L, 20L, 50L, 30L);
            assertEquals(600L, result); // (10+20) * (50-30) = 30 * 20 = 600
        }

        // Benchmark
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            target.call(10L, 20L, 50L, 30L);
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Integer arithmetic: %d iterations in %d ms (%.2f ns/op)%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000, (double) elapsed / BENCHMARK_ITERATIONS);

        // Verify correctness one more time
        assertEquals(600L, target.call(10L, 20L, 50L, 30L));
    }

    /**
     * Test: Record field access should be O(1) with no hash lookups.
     */
    @Test
    void testRecordFieldAccessPerformance() {
        // Create a record shape with typed fields
        String[] fieldNames = {"x", "y", "z"};
        MezzeRecord.FieldLocation[] locations = new MezzeRecord.FieldLocation[3];
        locations[0] = new MezzeRecord.FieldLocation(MezzeRecord.FieldLocation.StorageType.LONG, 0);
        locations[1] = new MezzeRecord.FieldLocation(MezzeRecord.FieldLocation.StorageType.LONG, 1);
        locations[2] = new MezzeRecord.FieldLocation(MezzeRecord.FieldLocation.StorageType.LONG, 2);
        MezzeRecord.Shape shape = new MezzeRecord.Shape(fieldNames, locations);

        // Create record with Long fields
        MezzeRecord record = new MezzeRecord(shape, new long[]{100L, 200L, 300L}, new double[0], new Object[0]);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            long x = record.getLongField(0);
            long y = record.getLongField(1);
            long z = record.getLongField(2);
            assertEquals(100L, x);
            assertEquals(200L, y);
            assertEquals(300L, z);
        }

        // Benchmark direct field access (should be array index access)
        long start = System.nanoTime();
        long sum = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            sum += record.getLongField(0);
            sum += record.getLongField(1);
            sum += record.getLongField(2);
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Record field access: %d iterations in %d ms (%.2f ns/op)%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000, (double) elapsed / BENCHMARK_ITERATIONS);

        // Prevent dead code elimination
        assertTrue(sum > 0);
        assertEquals(600_000_000L, sum);
    }

    /**
     * Test: Variant tag dispatch should be O(1) integer comparison.
     */
    @Test
    void testVariantMatchPerformance() {
        // Create a TagRegistry for Option type: Some = 0, None = 1
        MezzeVariant.TagRegistry optionRegistry = new MezzeVariant.TagRegistry(
            new String[]{"Some", "None"}
        );

        // Create variants with integer tags
        MezzeVariant some = new MezzeVariant(optionRegistry, 0, new Object[]{42L}); // Some(42) - tag 0
        MezzeVariant none = new MezzeVariant(optionRegistry, 1, new Object[0]);      // None - tag 1

        // Verify tag values
        assertEquals(0, some.getTag());
        assertEquals(1, none.getTag());
        assertEquals("Some", some.getConstructorName());
        assertEquals("None", none.getConstructorName());

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            int tag = some.getTag();
            assertEquals(0, tag);
        }

        // Benchmark tag dispatch (simulates match expression)
        long start = System.nanoTime();
        int count = 0;
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            // Simulate: match some { Some(x) -> x, None -> 0 }
            if (some.getTag() == 0) {
                count++;
                Object[] payload = some.getPayload();
                if ((long) payload[0] == 42L) count++;
            }
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Variant match: %d iterations in %d ms (%.2f ns/op)%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000, (double) elapsed / BENCHMARK_ITERATIONS);

        assertEquals(BENCHMARK_ITERATIONS * 2, count);
    }

    /**
     * Test: Boolean operations should stay unboxed.
     */
    @Test
    void testBooleanOperationsPerformance() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotA = builder.addSlot(FrameSlotKind.Boolean, "a", null);
        int slotB = builder.addSlot(FrameSlotKind.Boolean, "b", null);
        FrameDescriptor fd = builder.build();

        MezzeNode readA = new ReadLocalNode(slotA, ReadLocalNode.KIND_BOOLEAN);
        MezzeNode readB = new ReadLocalNode(slotB, ReadLocalNode.KIND_BOOLEAN);

        // a && b (short-circuit AND)
        MezzeNode andNode = AndNode.create(readA, readB);

        int[] slotTypes = {2, 2}; // Both Boolean
        MezzeRootNode root = new MezzeRootNode(null, fd, andNode, "and", 0, 2, slotTypes);
        CallTarget target = root.getCallTarget();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            Object result = target.call(true, true);
            assertEquals(true, result);
        }

        // Benchmark
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            target.call(true, false);
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Boolean AND: %d iterations in %d ms (%.2f ns/op)%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000, (double) elapsed / BENCHMARK_ITERATIONS);

        // Verify short-circuit: false && anything = false
        assertEquals(false, target.call(false, true));
    }

    /**
     * Test: Comparison operations on primitives should stay unboxed.
     */
    @Test
    void testComparisonPerformance() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotA = builder.addSlot(FrameSlotKind.Long, "a", null);
        int slotB = builder.addSlot(FrameSlotKind.Long, "b", null);
        FrameDescriptor fd = builder.build();

        MezzeNode readA = new ReadLocalNode(slotA, ReadLocalNode.KIND_LONG);
        MezzeNode readB = new ReadLocalNode(slotB, ReadLocalNode.KIND_LONG);

        // a < b
        MezzeNode lessThan = LessThanNodeGen.create(readA, readB);

        int[] slotTypes = {0, 0}; // Both Long
        MezzeRootNode root = new MezzeRootNode(null, fd, lessThan, "lt", 0, 2, slotTypes);
        CallTarget target = root.getCallTarget();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            Object result = target.call(5L, 10L);
            assertEquals(true, result);
        }

        // Benchmark
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            target.call(5L, 10L);
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Integer comparison: %d iterations in %d ms (%.2f ns/op)%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000, (double) elapsed / BENCHMARK_ITERATIONS);

        // Verify boundary cases
        assertEquals(true, target.call(5L, 10L));
        assertEquals(false, target.call(10L, 5L));
        assertEquals(false, target.call(5L, 5L));
    }

    /**
     * Test: If-then-else with branch profiling.
     */
    @Test
    void testIfElsePerformance() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotCond = builder.addSlot(FrameSlotKind.Boolean, "cond", null);
        FrameDescriptor fd = builder.build();

        MezzeNode readCond = new ReadLocalNode(slotCond, ReadLocalNode.KIND_BOOLEAN);
        MezzeNode thenBranch = new IntLiteralNode(1L);
        MezzeNode elseBranch = new IntLiteralNode(0L);

        MezzeNode ifNode = IfNode.create(readCond, thenBranch, elseBranch);

        int[] slotTypes = {2}; // Boolean
        MezzeRootNode root = new MezzeRootNode(null, fd, ifNode, "if", 0, 1, slotTypes);
        CallTarget target = root.getCallTarget();

        // Warmup with biased condition (mostly true)
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            Object result = target.call(i % 10 != 0); // 90% true
            assertTrue(result instanceof Long);
        }

        // Benchmark with consistent branch
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            target.call(true);
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("If-then-else: %d iterations in %d ms (%.2f ns/op)%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000, (double) elapsed / BENCHMARK_ITERATIONS);

        // Verify correctness
        assertEquals(1L, target.call(true));
        assertEquals(0L, target.call(false));
    }

    /**
     * Test: Double arithmetic should stay unboxed.
     */
    @Test
    void testDoubleArithmeticPerformance() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotA = builder.addSlot(FrameSlotKind.Double, "a", null);
        int slotB = builder.addSlot(FrameSlotKind.Double, "b", null);
        FrameDescriptor fd = builder.build();

        MezzeNode readA = new ReadLocalNode(slotA, ReadLocalNode.KIND_DOUBLE);
        MezzeNode readB = new ReadLocalNode(slotB, ReadLocalNode.KIND_DOUBLE);

        // a + b
        MezzeNode add = AddNodeGen.create(readA, readB);

        int[] slotTypes = {1, 1}; // Both Double
        MezzeRootNode root = new MezzeRootNode(null, fd, add, "addDouble", 0, 2, slotTypes);
        CallTarget target = root.getCallTarget();

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            Object result = target.call(1.5, 2.5);
            assertEquals(4.0, result);
        }

        // Benchmark
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            target.call(1.5, 2.5);
        }
        long elapsed = System.nanoTime() - start;

        System.out.printf("Double arithmetic: %d iterations in %d ms (%.2f ns/op)%n",
                BENCHMARK_ITERATIONS, elapsed / 1_000_000, (double) elapsed / BENCHMARK_ITERATIONS);

        // Verify correctness
        assertEquals(4.0, target.call(1.5, 2.5));
    }
}
