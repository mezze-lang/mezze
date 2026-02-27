package com.mezze.interpreter.v2.nodes.function;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.nodes.MezzeRootNode;
import com.mezze.interpreter.v2.runtime.MezzeLanguage;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Function definition node.
 *
 * When executed, creates a MezzeFunction by:
 * 1. Creating the CallTarget (lazily, once)
 * 2. Capturing closure variables from the current frame
 *
 * Closure variables are captured eagerly as an Object[] for fast access.
 */
@NodeInfo(shortName = "function-def")
public final class FunctionDefinitionNode extends MezzeNode {

    private final String name;

    @CompilationFinal(dimensions = 1)
    private final int[] closureSlots;

    @CompilationFinal
    private final int closureCount;

    @CompilationFinal
    private final int parameterCount;

    @Child
    private MezzeNode bodyNode;

    @CompilationFinal
    private final FrameDescriptor frameDescriptor;

    @CompilationFinal
    private CallTarget callTarget;

    public FunctionDefinitionNode(
            String name,
            int[] closureSlots,
            int parameterCount,
            MezzeNode bodyNode,
            FrameDescriptor frameDescriptor
    ) {
        this.name = name;
        this.closureSlots = closureSlots;
        this.closureCount = closureSlots.length;
        this.parameterCount = parameterCount;
        this.bodyNode = bodyNode;
        this.frameDescriptor = frameDescriptor;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Lazily create CallTarget
        if (callTarget == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            createCallTarget();
        }

        // Capture closure variables from current frame
        Object[] closureValues;
        if (closureCount > 0) {
            closureValues = new Object[closureCount];
            for (int i = 0; i < closureCount; i++) {
                closureValues[i] = frame.getObject(closureSlots[i]);
            }
        } else {
            closureValues = new Object[0];
        }

        return new MezzeFunction(callTarget, name, closureValues);
    }

    private void createCallTarget() {
        MezzeRootNode rootNode = new MezzeRootNode(
            MezzeLanguage.get(this),
            frameDescriptor,
            bodyNode,
            name,
            closureCount,
            parameterCount
        );
        callTarget = rootNode.getCallTarget();
    }
}
