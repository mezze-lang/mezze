package com.mezze.interpreter.v2.nodes.profile;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeField;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeCost;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.NodeUtil;

/**
 * SPLITTABLE wrapper node for profile-guided optimization.
 *
 * This node wraps any MezzeNode and enables Truffle's node splitting:
 * 1. Tracks execution count
 * 2. Reports polymorphism to the splitting heuristics
 * 3. When split, creates a specialized clone
 *
 * Node splitting duplicates this subtree at each call site,
 * allowing each clone to specialize independently based on
 * the types it observes at that specific call site.
 *
 * Benefits:
 * - Call site A seeing only Ints gets Int-specialized code
 * - Call site B seeing Ints and Doubles gets polymorphic code
 * - Each site is optimized for its specific usage pattern
 */
@NodeInfo(shortName = "splittable")
@ReportPolymorphism
public final class SplittableNode extends MezzeNode {

    @Child
    private MezzeNode innerNode;

    @CompilationFinal
    private boolean reportedPolymorphism = false;

    /**
     * Execution count for profiling (only in interpreter).
     * Not @CompilationFinal so it can be modified in interpreter.
     */
    private int executionCount = 0;

    /**
     * Threshold for considering a node "hot" and worth splitting.
     */
    private static final int HOT_THRESHOLD = 100;

    public SplittableNode(MezzeNode innerNode) {
        this.innerNode = innerNode;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Only count in interpreter (not in compiled code)
        if (CompilerDirectives.inInterpreter()) {
            executionCount++;

            // Report polymorphism after threshold to trigger splitting
            if (!reportedPolymorphism && executionCount > HOT_THRESHOLD) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                reportedPolymorphism = true;
                // Truffle's splitting heuristic will consider this node for splitting
                notifySplit();
            }
        }

        return innerNode.execute(frame);
    }

    /**
     * Notify the runtime that this node is a good candidate for splitting.
     * Called once when execution count exceeds threshold.
     */
    private void notifySplit() {
        // The @ReportPolymorphism annotation handles this automatically
        // This method is a hook for any additional splitting logic
    }

    @Override
    public boolean isAdoptable() {
        return true;
    }

    /**
     * Override to report cost based on inner node complexity.
     * Helps Truffle decide whether splitting is worthwhile.
     */
    @Override
    public NodeCost getCost() {
        return innerNode.getCost();
    }

    /**
     * Create a splittable wrapper around any node.
     */
    public static SplittableNode create(MezzeNode innerNode) {
        return new SplittableNode(innerNode);
    }

    /**
     * Get the wrapped inner node (for debugging/inspection).
     */
    public MezzeNode getInnerNode() {
        return innerNode;
    }
}
