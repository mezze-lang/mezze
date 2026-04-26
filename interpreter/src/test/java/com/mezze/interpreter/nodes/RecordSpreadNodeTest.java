package com.mezze.interpreter.nodes;

import com.mezze.interpreter.nodes.literal.IntLiteralNode;
import com.mezze.interpreter.nodes.record.RecordLiteralNode;
import com.mezze.interpreter.nodes.record.RecordSpreadNode;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RecordSpreadNode - the super-optimized record spread implementation.
 *
 * Run with: ./gradlew :interpreterv2:test --tests "*RecordSpreadNodeTest*"
 */
public class RecordSpreadNodeTest {

    /**
     * Test basic spread: {...record, field = value}
     */
    @Test
    void testBasicSpread() {
        // Create source record: {x = 10, y = 20}
        MezzeNode sourceRecordNode = RecordLiteralNode.allObjects(
            new String[]{"x", "y"},
            new MezzeNode[]{new IntLiteralNode(10), new IntLiteralNode(20)}
        );

        // Create spread: {...source, z = 30}
        MezzeNode spreadNode = RecordSpreadNode.create(
            new MezzeNode[]{sourceRecordNode},
            new String[]{"z"},
            new MezzeNode[]{new IntLiteralNode(30)}
        );

        // Execute
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeRootNode root = new MezzeRootNode(null, fd, spreadNode, "test-spread", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        // Verify
        assertTrue(result instanceof MezzeRecord, "Result should be MezzeRecord");
        MezzeRecord rec = (MezzeRecord) result;
        assertEquals(3, rec.getShape().getFieldCount(), "Should have 3 fields");
        assertEquals(10L, rec.getField(0), "x should be 10");
        assertEquals(20L, rec.getField(1), "y should be 20");
        assertEquals(30L, rec.getField(2), "z should be 30");
    }

    /**
     * Test spread with override: {...record, x = newValue}
     */
    @Test
    void testSpreadWithOverride() {
        // Create source record: {x = 10, y = 20}
        MezzeNode sourceRecordNode = RecordLiteralNode.allObjects(
            new String[]{"x", "y"},
            new MezzeNode[]{new IntLiteralNode(10), new IntLiteralNode(20)}
        );

        // Create spread with override: {...source, x = 100}
        MezzeNode spreadNode = RecordSpreadNode.create(
            new MezzeNode[]{sourceRecordNode},
            new String[]{"x"},
            new MezzeNode[]{new IntLiteralNode(100)}
        );

        // Execute
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeRootNode root = new MezzeRootNode(null, fd, spreadNode, "test-override", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        // Verify
        MezzeRecord rec = (MezzeRecord) result;
        assertEquals(2, rec.getShape().getFieldCount(), "Should have 2 fields");
        assertEquals(100L, rec.getField(0), "x should be overridden to 100");
        assertEquals(20L, rec.getField(1), "y should remain 20");
    }

    /**
     * Test multiple spreads: {...rec1, ...rec2}
     */
    @Test
    void testMultipleSpreads() {
        // Create first record: {a = 1, b = 2}
        MezzeNode record1 = RecordLiteralNode.allObjects(
            new String[]{"a", "b"},
            new MezzeNode[]{new IntLiteralNode(1), new IntLiteralNode(2)}
        );

        // Create second record: {c = 3, d = 4}
        MezzeNode record2 = RecordLiteralNode.allObjects(
            new String[]{"c", "d"},
            new MezzeNode[]{new IntLiteralNode(3), new IntLiteralNode(4)}
        );

        // Create spread: {...rec1, ...rec2}
        MezzeNode spreadNode = RecordSpreadNode.create(
            new MezzeNode[]{record1, record2},
            new String[]{},
            new MezzeNode[]{}
        );

        // Execute
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeRootNode root = new MezzeRootNode(null, fd, spreadNode, "test-multi", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        // Verify
        MezzeRecord rec = (MezzeRecord) result;
        assertEquals(4, rec.getShape().getFieldCount(), "Should have 4 fields");
    }

    /**
     * Test spread with overlapping fields (later spread overrides earlier).
     */
    @Test
    void testSpreadOverlappingFields() {
        // Create first record: {x = 1, y = 2}
        MezzeNode record1 = RecordLiteralNode.allObjects(
            new String[]{"x", "y"},
            new MezzeNode[]{new IntLiteralNode(1), new IntLiteralNode(2)}
        );

        // Create second record: {y = 200, z = 3}
        MezzeNode record2 = RecordLiteralNode.allObjects(
            new String[]{"y", "z"},
            new MezzeNode[]{new IntLiteralNode(200), new IntLiteralNode(3)}
        );

        // Create spread: {...rec1, ...rec2}
        MezzeNode spreadNode = RecordSpreadNode.create(
            new MezzeNode[]{record1, record2},
            new String[]{},
            new MezzeNode[]{}
        );

        // Execute
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeRootNode root = new MezzeRootNode(null, fd, spreadNode, "test-overlap", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        // Verify - y should be overridden by record2
        MezzeRecord rec = (MezzeRecord) result;
        assertEquals(3, rec.getShape().getFieldCount(), "Should have 3 unique fields");
        // Fields are merged: x from rec1, y from rec2 (override), z from rec2
        assertEquals(1L, rec.getField(0), "x should be 1");
        assertEquals(200L, rec.getField(1), "y should be 200 (overridden)");
        assertEquals(3L, rec.getField(2), "z should be 3");
    }

    /**
     * Test shape caching - same shape pattern should be cached.
     */
    @Test
    void testShapeCaching() {
        // Create source record: {x = 10, y = 20}
        MezzeNode sourceRecordNode = RecordLiteralNode.allObjects(
            new String[]{"x", "y"},
            new MezzeNode[]{new IntLiteralNode(10), new IntLiteralNode(20)}
        );

        // Create spread: {...source, z = 30}
        MezzeNode spreadNode = RecordSpreadNode.create(
            new MezzeNode[]{sourceRecordNode},
            new String[]{"z"},
            new MezzeNode[]{new IntLiteralNode(30)}
        );

        // Execute multiple times - should use cached shape path
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeRootNode root = new MezzeRootNode(null, fd, spreadNode, "test-cache", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();

        // Warmup
        for (int i = 0; i < 1000; i++) {
            Object result = target.call();
            assertTrue(result instanceof MezzeRecord);
        }

        // After warmup, fast path should be active
        Object result = target.call();
        MezzeRecord rec = (MezzeRecord) result;
        assertEquals(3, rec.getShape().getFieldCount());
    }

    /**
     * Performance test - spread should be efficient after JIT warmup.
     */
    @Test
    void testSpreadPerformance() {
        // Create source record: {x = 10, y = 20}
        MezzeNode sourceRecordNode = RecordLiteralNode.allObjects(
            new String[]{"x", "y"},
            new MezzeNode[]{new IntLiteralNode(10), new IntLiteralNode(20)}
        );

        // Create spread: {...source, z = 30}
        MezzeNode spreadNode = RecordSpreadNode.create(
            new MezzeNode[]{sourceRecordNode},
            new String[]{"z"},
            new MezzeNode[]{new IntLiteralNode(30)}
        );

        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeRootNode root = new MezzeRootNode(null, fd, spreadNode, "test-perf", 0, 0, new int[0]);
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

        System.out.printf("Record spread: %d iterations in %d ms (%.2f ns/op)%n",
                iterations, elapsed / 1_000_000, (double) elapsed / iterations);

        // Verify still works correctly
        MezzeRecord rec = (MezzeRecord) target.call();
        assertEquals(3, rec.getShape().getFieldCount());
    }
}
