package com.mezze.interpreter.nodes;

import com.mezze.interpreter.nodes.literal.IntLiteralNode;
import com.mezze.interpreter.nodes.record.RecordLiteralNode;
import com.mezze.interpreter.nodes.variable.ReadLocalNode;
import com.mezze.interpreter.nodes.variant.MatchBranchNode;
import com.mezze.interpreter.nodes.variant.MatchNode;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for record pattern matching in V2.
 *
 * Run with: ./gradlew :interpreterv2:test --tests "*RecordPatternMatchTest*"
 */
public class RecordPatternMatchTest {

    /**
     * Test: match {x = 10, y = 20} { {x, y} -> x }
     */
    @Test
    void testBasicRecordPattern() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotX = builder.addSlot(FrameSlotKind.Object, "x", null);
        int slotY = builder.addSlot(FrameSlotKind.Object, "y", null);
        FrameDescriptor fd = builder.build();

        // Input: {x = 10, y = 20}
        MezzeNode inputNode = RecordLiteralNode.allObjects(
                new String[]{"x", "y"},
                new MezzeNode[]{new IntLiteralNode(10), new IntLiteralNode(20)}
        );

        // Pattern: {x, y}
        String[] fieldNames = {"x", "y"};
        int[] fieldSlots = {slotX, slotY};

        // Body returns x
        MezzeNode bodyNode = new ReadLocalNode(slotX, ReadLocalNode.KIND_OBJECT);

        MatchBranchNode branch = MatchBranchNode.recordPattern(fieldNames, fieldSlots, false, bodyNode);
        MatchNode matchNode = new MatchNode(inputNode, new MatchBranchNode[]{branch}, -1);

        MezzeRootNode root = new MezzeRootNode(null, fd, matchNode, "test-record", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertEquals(10L, result);
    }

    /**
     * Test open pattern: match {x = 10, y = 20, z = 30} { {x, ...} -> x }
     */
    @Test
    void testOpenRecordPattern() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotX = builder.addSlot(FrameSlotKind.Object, "x", null);
        FrameDescriptor fd = builder.build();

        // Input: {x = 10, y = 20, z = 30}
        MezzeNode inputNode = RecordLiteralNode.allObjects(
                new String[]{"x", "y", "z"},
                new MezzeNode[]{new IntLiteralNode(10), new IntLiteralNode(20), new IntLiteralNode(30)}
        );

        // Pattern: {x, ...} - open pattern
        String[] fieldNames = {"x"};
        int[] fieldSlots = {slotX};

        MezzeNode bodyNode = new ReadLocalNode(slotX, ReadLocalNode.KIND_OBJECT);

        MatchBranchNode branch = MatchBranchNode.recordPattern(fieldNames, fieldSlots, true, bodyNode);
        MatchNode matchNode = new MatchNode(inputNode, new MatchBranchNode[]{branch}, -1);

        MezzeRootNode root = new MezzeRootNode(null, fd, matchNode, "test-open", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertEquals(10L, result);
    }

    /**
     * Test: match {name = "Alice", age = 30} { {name = n, age = a} -> n }
     */
    @Test
    void testNamedFieldPattern() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotN = builder.addSlot(FrameSlotKind.Object, "n", null);
        int slotA = builder.addSlot(FrameSlotKind.Object, "a", null);
        FrameDescriptor fd = builder.build();

        // Input: {name = "Alice", age = 30}
        MezzeNode inputNode = RecordLiteralNode.allObjects(
                new String[]{"name", "age"},
                new MezzeNode[]{
                        new com.mezze.interpreter.nodes.literal.StringLiteralNode("Alice"),
                        new IntLiteralNode(30)
                }
        );

        // Pattern: {name = n, age = a}
        String[] fieldNames = {"name", "age"};
        int[] fieldSlots = {slotN, slotA};

        MezzeNode bodyNode = new ReadLocalNode(slotN, ReadLocalNode.KIND_OBJECT);

        MatchBranchNode branch = MatchBranchNode.recordPattern(fieldNames, fieldSlots, false, bodyNode);
        MatchNode matchNode = new MatchNode(inputNode, new MatchBranchNode[]{branch}, -1);

        MezzeRootNode root = new MezzeRootNode(null, fd, matchNode, "test-named", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertEquals("Alice", result);
    }

    /**
     * Performance test for record pattern matching.
     */
    @Test
    void testRecordPatternPerformance() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotX = builder.addSlot(FrameSlotKind.Object, "x", null);
        int slotY = builder.addSlot(FrameSlotKind.Object, "y", null);
        FrameDescriptor fd = builder.build();

        MezzeNode inputNode = RecordLiteralNode.allObjects(
                new String[]{"x", "y"},
                new MezzeNode[]{new IntLiteralNode(10), new IntLiteralNode(20)}
        );

        String[] fieldNames = {"x", "y"};
        int[] fieldSlots = {slotX, slotY};

        MezzeNode bodyNode = new IntLiteralNode(100);

        MatchBranchNode branch = MatchBranchNode.recordPattern(fieldNames, fieldSlots, false, bodyNode);
        MatchNode matchNode = new MatchNode(inputNode, new MatchBranchNode[]{branch}, -1);

        MezzeRootNode root = new MezzeRootNode(null, fd, matchNode, "test-perf", 0, 0, new int[0]);
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

        System.out.printf("Record pattern match: %d iterations in %d ms (%.2f ns/op)%n",
                iterations, elapsed / 1_000_000, (double) elapsed / iterations);

        assertEquals(100L, target.call());
    }
}
