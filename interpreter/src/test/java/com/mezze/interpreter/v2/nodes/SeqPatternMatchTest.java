package com.mezze.interpreter.v2.nodes;

import com.mezze.interpreter.v2.nodes.literal.IntLiteralNode;
import com.mezze.interpreter.v2.nodes.literal.StringLiteralNode;
import com.mezze.interpreter.v2.nodes.seq.SeqLiteralNode;
import com.mezze.interpreter.v2.nodes.variable.ReadLocalNode;
import com.mezze.interpreter.v2.nodes.variant.MatchBranchNode;
import com.mezze.interpreter.v2.nodes.variant.MatchNode;
import com.mezze.datastructures.list.MezzeList;
import com.mezze.datastructures.seq.MezzeSeq;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for seq pattern matching in V2.
 *
 * Run with: ./gradlew :interpreterv2:test --tests "*SeqPatternMatchTest*"
 */
public class SeqPatternMatchTest {

    /**
     * Test: match [] { [] -> 0 }
     */
    @Test
    void testEmptySeqPattern() {
        // Create empty seq
        MezzeNode emptySeqNode = SeqLiteralNode.create(new MezzeNode[0]);

        // Create match with empty seq pattern
        MatchBranchNode emptyBranch = MatchBranchNode.seqPattern(
                new int[0], -1, false, new IntLiteralNode(0)
        );

        MatchNode matchNode = new MatchNode(emptySeqNode, new MatchBranchNode[]{emptyBranch}, -1);

        // Execute
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        MezzeRootNode root = new MezzeRootNode(null, fd, matchNode, "test-empty", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertEquals(0L, result);
    }

    /**
     * Test: match [1, 2] { [x, y] -> x + y }
     */
    @Test
    void testTwoElementPattern() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotX = builder.addSlot(FrameSlotKind.Object, "x", null);
        int slotY = builder.addSlot(FrameSlotKind.Object, "y", null);
        FrameDescriptor fd = builder.build();

        // Create [1, 2]
        MezzeNode seqNode = SeqLiteralNode.create(new MezzeNode[]{
                new IntLiteralNode(1),
                new IntLiteralNode(2)
        });

        // Create body: return 42 (simplified - would normally use x + y)
        MezzeNode bodyNode = new IntLiteralNode(42);

        // Create match with [x, y] pattern
        MatchBranchNode branch = MatchBranchNode.seqPattern(
                new int[]{slotX, slotY}, -1, false, bodyNode
        );

        MatchNode matchNode = new MatchNode(seqNode, new MatchBranchNode[]{branch}, -1);

        // Execute
        MezzeRootNode root = new MezzeRootNode(null, fd, matchNode, "test-two", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertEquals(42L, result);
    }

    /**
     * Test: match [1, 2, 3] { [head, ...tail] -> head }
     */
    @Test
    void testRestPattern() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotHead = builder.addSlot(FrameSlotKind.Object, "head", null);
        int slotTail = builder.addSlot(FrameSlotKind.Object, "tail", null);
        FrameDescriptor fd = builder.build();

        // Create [1, 2, 3]
        MezzeNode seqNode = SeqLiteralNode.create(new MezzeNode[]{
                new IntLiteralNode(1),
                new IntLiteralNode(2),
                new IntLiteralNode(3)
        });

        // Body returns the head element (slot 0)
        MezzeNode bodyNode = new ReadLocalNode(slotHead, ReadLocalNode.KIND_OBJECT);

        // Create match with [head, ...tail] pattern
        MatchBranchNode branch = MatchBranchNode.seqPattern(
                new int[]{slotHead}, slotTail, true, bodyNode
        );

        MatchNode matchNode = new MatchNode(seqNode, new MatchBranchNode[]{branch}, -1);

        // Execute
        MezzeRootNode root = new MezzeRootNode(null, fd, matchNode, "test-rest", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertEquals(1L, result, "Head should be 1");
    }

    /**
     * Test multiple branches: match xs { [] -> 0, [x] -> 1, [x, y] -> 2, _ -> 3 }
     */
    @Test
    void testMultipleBranches() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotX = builder.addSlot(FrameSlotKind.Object, "x", null);
        int slotY = builder.addSlot(FrameSlotKind.Object, "y", null);
        int slotWild = builder.addSlot(FrameSlotKind.Object, "wild", null);
        FrameDescriptor fd = builder.build();

        // Create [1, 2]
        MezzeNode seqNode = SeqLiteralNode.create(new MezzeNode[]{
                new IntLiteralNode(1),
                new IntLiteralNode(2)
        });

        // Create branches
        MatchBranchNode emptyBranch = MatchBranchNode.seqPattern(
                new int[0], -1, false, new IntLiteralNode(0)
        );
        MatchBranchNode singleBranch = MatchBranchNode.seqPattern(
                new int[]{slotX}, -1, false, new IntLiteralNode(1)
        );
        MatchBranchNode pairBranch = MatchBranchNode.seqPattern(
                new int[]{slotX, slotY}, -1, false, new IntLiteralNode(2)
        );
        MatchBranchNode wildcardBranch = MatchBranchNode.wildcard(slotWild, new IntLiteralNode(3));

        MatchNode matchNode = new MatchNode(
                seqNode,
                new MatchBranchNode[]{emptyBranch, singleBranch, pairBranch, wildcardBranch},
                3 // wildcard index
        );

        // Execute
        MezzeRootNode root = new MezzeRootNode(null, fd, matchNode, "test-multi", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        assertEquals(2L, result, "Should match [x, y] pattern");
    }

    /**
     * Test: match [1, 2, 3] { [_, ...rest] -> rest.size }
     */
    @Test
    void testRestSeqPatternBindsCorrectly() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotRest = builder.addSlot(FrameSlotKind.Object, "rest", null);
        FrameDescriptor fd = builder.build();

        // Create [1, 2, 3]
        MezzeNode seqNode = SeqLiteralNode.create(new MezzeNode[]{
                new IntLiteralNode(1),
                new IntLiteralNode(2),
                new IntLiteralNode(3)
        });

        // Body returns the rest list
        MezzeNode bodyNode = new ReadLocalNode(slotRest, ReadLocalNode.KIND_OBJECT);

        // Create match with [_, ...rest] pattern (-1 for first slot = no binding)
        MatchBranchNode branch = MatchBranchNode.seqPattern(
                new int[]{-1}, slotRest, true, bodyNode
        );

        MatchNode matchNode = new MatchNode(seqNode, new MatchBranchNode[]{branch}, -1);

        // Execute
        MezzeRootNode root = new MezzeRootNode(null, fd, matchNode, "test-rest-bind", 0, 0, new int[0]);
        CallTarget target = root.getCallTarget();
        Object result = target.call();

        // V2 uses MezzeSeq for sequences
        assertTrue(result instanceof MezzeSeq, "Rest should be MezzeSeq, got: " +
            (result == null ? "null" : result.getClass().getName()));
        MezzeSeq rest = (MezzeSeq) result;
        assertEquals(2, rest.size(), "Rest should have 2 elements [2, 3]");
        assertEquals(2L, rest.get(0), "First rest element should be 2");
        assertEquals(3L, rest.get(1), "Second rest element should be 3");
    }

    /**
     * Performance test for seq pattern matching.
     */
    @Test
    void testSeqPatternPerformance() {
        FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
        int slotX = builder.addSlot(FrameSlotKind.Object, "x", null);
        int slotY = builder.addSlot(FrameSlotKind.Object, "y", null);
        FrameDescriptor fd = builder.build();

        // Create [1, 2]
        MezzeNode seqNode = SeqLiteralNode.create(new MezzeNode[]{
                new IntLiteralNode(1),
                new IntLiteralNode(2)
        });

        MatchBranchNode branch = MatchBranchNode.seqPattern(
                new int[]{slotX, slotY}, -1, false, new IntLiteralNode(100)
        );

        MatchNode matchNode = new MatchNode(seqNode, new MatchBranchNode[]{branch}, -1);

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

        System.out.printf("Seq pattern match: %d iterations in %d ms (%.2f ns/op)%n",
                iterations, elapsed / 1_000_000, (double) elapsed / iterations);

        // Verify still works
        assertEquals(100L, target.call());
    }
}
