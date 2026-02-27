package com.mezze.interpreter.v2.nodes;

import com.mezze.interpreter.v2.runtime.MezzeLanguage;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Root node for Mezze functions.
 *
 * The FrameDescriptor uses typed slots based on inferredType:
 * - FrameSlotKind.Long for Int parameters
 * - FrameSlotKind.Double for Float parameters
 * - FrameSlotKind.Boolean for Bool parameters
 * - FrameSlotKind.Object for everything else
 */
@NodeInfo(language = "mezze", description = "Mezze function root")
public final class MezzeRootNode extends RootNode {

    private final String name;

    @Child private MezzeNode bodyNode;

    // Number of closure variables (prepended to arguments)
    private final int closureCount;

    // Number of function parameters
    private final int parameterCount;

    public MezzeRootNode(
            MezzeLanguage language,
            FrameDescriptor frameDescriptor,
            MezzeNode bodyNode,
            String name,
            int closureCount,
            int parameterCount
    ) {
        super(language, frameDescriptor);
        this.bodyNode = bodyNode;
        this.name = name;
        this.closureCount = closureCount;
        this.parameterCount = parameterCount;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Arguments layout: [closure0, closure1, ..., param0, param1, ...]
        // These are already bound to frame slots by the caller or
        // need to be extracted from frame.getArguments()

        return bodyNode.execute(frame);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "MezzeRootNode[" + name + "]";
    }

    public int getClosureCount() {
        return closureCount;
    }

    public int getParameterCount() {
        return parameterCount;
    }
}
