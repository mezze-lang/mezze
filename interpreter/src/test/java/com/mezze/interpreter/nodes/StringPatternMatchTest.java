package com.mezze.interpreter.nodes;

import com.mezze.interpreter.nodes.literal.IntLiteralNode;
import com.mezze.interpreter.nodes.literal.StringLiteralNode;
import com.mezze.interpreter.nodes.variable.ReadLocalNode;
import com.mezze.interpreter.nodes.variant.MatchBranchNode;
import com.mezze.interpreter.nodes.variant.MatchNode;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for string interpolation pattern matching in V2.
 *
 * Run with: ./gradlew :interpreterv2:test --tests "*StringPatternMatchTest*"
 */
public class StringPatternMatchTest {

    /**
     * Test: match "hello world" { "hello $(name)" -> name }
     */
    @Test
    void testPrefixCapture() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotName = builder.addSlot(FrameSlotKind.Object, "name", null);
        FrameDescriptor fd = builder.build();

        // Input: "hello world"
        MezzeNode inputNode = new StringLiteralNode("hello world");

        // Pattern: "hello $(name)" - LITERAL, CAPTURE
        int[] partTypes = {0, 1}; // LITERAL, CAPTURE
        String[] literals = {"hello ", null};
        int[] captureSlots = {-1, slotName};

        // Body returns the captured name
        MezzeNode bodyNode = new ReadLocalNode(slotName, ReadLocalNode.KIND_OBJECT);

        MatchBranchNode branch = MatchBranchNode.stringPattern(partTypes, literals, captureSlots, bodyNode);
        MatchNode matchNode = new MatchNode(inputNode, new MatchBranchNode[]{branch}, -1);

        MezzeRootNode root = new MezzeRootNode(null, fd, matchNode, "test-prefix", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertEquals("world", result);
    }

    /**
     * Test: match "hello world goodbye" { "hello $(x) goodbye" -> x }
     */
    @Test
    void testPrefixSuffixCapture() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotX = builder.addSlot(FrameSlotKind.Object, "x", null);
        FrameDescriptor fd = builder.build();

        // Input: "hello world goodbye"
        MezzeNode inputNode = new StringLiteralNode("hello world goodbye");

        // Pattern: "hello $(x) goodbye" - LITERAL, CAPTURE, LITERAL
        int[] partTypes = {0, 1, 0}; // LITERAL, CAPTURE, LITERAL
        String[] literals = {"hello ", null, " goodbye"};
        int[] captureSlots = {-1, slotX, -1};

        MezzeNode bodyNode = new ReadLocalNode(slotX, ReadLocalNode.KIND_OBJECT);

        MatchBranchNode branch = MatchBranchNode.stringPattern(partTypes, literals, captureSlots, bodyNode);
        MatchNode matchNode = new MatchNode(inputNode, new MatchBranchNode[]{branch}, -1);

        MezzeRootNode root = new MezzeRootNode(null, fd, matchNode, "test-both", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertEquals("world", result);
    }

    /**
     * Test: match "foo-bar" { "$(a)-$(b)" -> a }
     */
    @Test
    void testTwoCaptures() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotA = builder.addSlot(FrameSlotKind.Object, "a", null);
        int slotB = builder.addSlot(FrameSlotKind.Object, "b", null);
        FrameDescriptor fd = builder.build();

        // Input: "foo-bar"
        MezzeNode inputNode = new StringLiteralNode("foo-bar");

        // Pattern: "$(a)-$(b)" - CAPTURE, LITERAL, CAPTURE
        int[] partTypes = {1, 0, 1}; // CAPTURE, LITERAL, CAPTURE
        String[] literals = {null, "-", null};
        int[] captureSlots = {slotA, -1, slotB};

        MezzeNode bodyNode = new ReadLocalNode(slotA, ReadLocalNode.KIND_OBJECT);

        MatchBranchNode branch = MatchBranchNode.stringPattern(partTypes, literals, captureSlots, bodyNode);
        MatchNode matchNode = new MatchNode(inputNode, new MatchBranchNode[]{branch}, -1);

        MezzeRootNode root = new MezzeRootNode(null, fd, matchNode, "test-two", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertEquals("foo", result);
    }

    /**
     * Test: match "anything" { "$(x)" -> x } - captures everything
     */
    @Test
    void testCaptureAll() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotX = builder.addSlot(FrameSlotKind.Object, "x", null);
        FrameDescriptor fd = builder.build();

        // Input: "anything"
        MezzeNode inputNode = new StringLiteralNode("anything");

        // Pattern: "$(x)" - just CAPTURE
        int[] partTypes = {1}; // CAPTURE
        String[] literals = {null};
        int[] captureSlots = {slotX};

        MezzeNode bodyNode = new ReadLocalNode(slotX, ReadLocalNode.KIND_OBJECT);

        MatchBranchNode branch = MatchBranchNode.stringPattern(partTypes, literals, captureSlots, bodyNode);
        MatchNode matchNode = new MatchNode(inputNode, new MatchBranchNode[]{branch}, -1);

        MezzeRootNode root = new MezzeRootNode(null, fd, matchNode, "test-all", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertEquals("anything", result);
    }

    /**
     * Performance test for string pattern matching.
     */
    @Test
    void testStringPatternPerformance() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotX = builder.addSlot(FrameSlotKind.Object, "x", null);
        FrameDescriptor fd = builder.build();

        MezzeNode inputNode = new StringLiteralNode("hello world goodbye");

        int[] partTypes = {0, 1, 0};
        String[] literals = {"hello ", null, " goodbye"};
        int[] captureSlots = {-1, slotX, -1};

        MezzeNode bodyNode = new IntLiteralNode(100);

        MatchBranchNode branch = MatchBranchNode.stringPattern(partTypes, literals, captureSlots, bodyNode);
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

        System.out.printf("String pattern match: %d iterations in %d ms (%.2f ns/op)%n",
                iterations, elapsed / 1_000_000, (double) elapsed / iterations);

        assertEquals(100L, target.call());
    }
}
