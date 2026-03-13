package com.mezze.interpreter.v2.nodes.mutation;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.profiles.LoopConditionProfile;
import com.mezze.interpreter.v2.values.MezzeFunction;

/**
 * Optimized for-range loop for mutation blocks.
 *
 * Uses Truffle's LoopNode for OSR (on-stack replacement) optimization.
 * The loop counter is kept in a frame slot for efficient access.
 *
 * Usage: for_range {start = 0, end = 100, body = {i} -> ...}
 */
@NodeInfo(shortName = "for-range")
public final class ForRangeNode extends MezzeNode {

    @Child private MezzeNode startNode;
    @Child private MezzeNode endNode;
    @Child private MezzeNode bodyFnNode;
    @Child private LoopNode loopNode;

    // Frame slots for loop state
    private final int indexSlot;
    private final int endSlot;
    private final int bodySlot;

    public ForRangeNode(MezzeNode startNode, MezzeNode endNode, MezzeNode bodyFnNode,
                        FrameDescriptor.Builder builder) {
        this.startNode = startNode;
        this.endNode = endNode;
        this.bodyFnNode = bodyFnNode;

        // Allocate frame slots for loop state
        this.indexSlot = builder.addSlot(FrameSlotKind.Long, "for_range_index", null);
        this.endSlot = builder.addSlot(FrameSlotKind.Long, "for_range_end", null);
        this.bodySlot = builder.addSlot(FrameSlotKind.Object, "for_range_body", null);
    }

    // Alternative constructor when slots are pre-allocated
    public ForRangeNode(MezzeNode startNode, MezzeNode endNode, MezzeNode bodyFnNode,
                        int indexSlot, int endSlot, int bodySlot) {
        this.startNode = startNode;
        this.endNode = endNode;
        this.bodyFnNode = bodyFnNode;
        this.indexSlot = indexSlot;
        this.endSlot = endSlot;
        this.bodySlot = bodySlot;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        long start = (long) startNode.execute(frame);
        long end = (long) endNode.execute(frame);
        Object bodyFn = bodyFnNode.execute(frame);

        if (!(bodyFn instanceof MezzeFunction body)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("for_range body must be a function");
        }

        // Store loop state in frame
        frame.setLong(indexSlot, start);
        frame.setLong(endSlot, end);
        frame.setObject(bodySlot, body);

        // Create loop node lazily
        if (loopNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            loopNode = insert(Truffle.getRuntime().createLoopNode(
                new ForRangeRepeatingNode(indexSlot, endSlot, bodySlot)
            ));
        }

        loopNode.execute(frame);
        return MezzeRecord.empty();
    }

    /**
     * The repeating node that executes each iteration.
     * Truffle's OSR will optimize this for long-running loops.
     */
    private static final class ForRangeRepeatingNode extends Node implements RepeatingNode {

        private final int indexSlot;
        private final int endSlot;
        private final int bodySlot;

        @Child private DirectCallNode callNode;

        private final LoopConditionProfile loopProfile = LoopConditionProfile.createCountingProfile();

        // Cached closure values array - avoid allocation per iteration
        @CompilerDirectives.CompilationFinal(dimensions = 1)
        private Object[] cachedCallArgs;

        @CompilerDirectives.CompilationFinal
        private int cachedClosureLen = -1;

        ForRangeRepeatingNode(int indexSlot, int endSlot, int bodySlot) {
            this.indexSlot = indexSlot;
            this.endSlot = endSlot;
            this.bodySlot = bodySlot;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            long index = frame.getLong(indexSlot);
            long end = frame.getLong(endSlot);

            if (loopProfile.profile(index < end)) {
                MezzeFunction body = (MezzeFunction) frame.getObject(bodySlot);

                // Initialize call args on first iteration
                if (callNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    callNode = insert(DirectCallNode.create(body.getCallTarget()));
                    Object[] closureValues = body.getClosureValues();
                    cachedClosureLen = closureValues.length;
                    cachedCallArgs = new Object[cachedClosureLen + 1];
                    System.arraycopy(closureValues, 0, cachedCallArgs, 0, cachedClosureLen);
                }

                // Create argument record with current index
                MezzeRecord argRecord = MezzeRecord.fromFields(
                    new String[]{"i"},
                    new Object[]{index}
                );
                cachedCallArgs[cachedClosureLen] = argRecord;

                // Call body
                callNode.call(cachedCallArgs);

                // Increment index
                frame.setLong(indexSlot, index + 1);
                return true; // Continue loop
            }

            return false; // Exit loop
        }
    }
}
