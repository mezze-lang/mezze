package com.mezze.interpreter.v2.nodes.function;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Function invocation node.
 *
 * Uses inline caching:
 * - Monomorphic: DirectCallNode for single call target (fastest)
 * - Polymorphic: Up to 3 cached DirectCallNodes
 * - Megamorphic: IndirectCallNode (slowest)
 */
@NodeChild("functionNode")
@NodeInfo(shortName = "invoke")
public abstract class InvokeNode extends MezzeNode {

    @Children
    private final MezzeNode[] argumentNodes;

    protected InvokeNode(MezzeNode[] argumentNodes) {
        this.argumentNodes = argumentNodes;
    }

    /**
     * Monomorphic call - same function every time (most common).
     */
    @Specialization(
        guards = "function.getCallTarget() == cachedTarget",
        limit = "3"
    )
    @ExplodeLoop
    protected Object callDirect(
            VirtualFrame frame,
            MezzeFunction function,
            @Cached("function.getCallTarget()") com.oracle.truffle.api.CallTarget cachedTarget,
            @Cached("create(function.getCallTarget())") DirectCallNode callNode
    ) {
        // Evaluate arguments
        Object[] arguments = evaluateArguments(frame, function);
        return callNode.call(arguments);
    }

    /**
     * Megamorphic call - different functions (rare).
     */
    @Specialization(replaces = "callDirect")
    @ExplodeLoop
    protected Object callIndirect(
            VirtualFrame frame,
            MezzeFunction function,
            @Cached IndirectCallNode callNode
    ) {
        Object[] arguments = evaluateArguments(frame, function);
        return callNode.call(function.getCallTarget(), arguments);
    }

    @ExplodeLoop
    private Object[] evaluateArguments(VirtualFrame frame, MezzeFunction function) {
        Object[] closureValues = function.getClosureValues();
        Object[] arguments = new Object[closureValues.length + argumentNodes.length];

        // Copy closure values first
        System.arraycopy(closureValues, 0, arguments, 0, closureValues.length);

        // Evaluate and copy argument values
        for (int i = 0; i < argumentNodes.length; i++) {
            arguments[closureValues.length + i] = argumentNodes[i].execute(frame);
        }

        return arguments;
    }

    /**
     * Create an invoke node.
     */
    public static InvokeNode create(MezzeNode functionNode, MezzeNode[] argumentNodes) {
        return InvokeNodeGen.create(argumentNodes, functionNode);
    }
}
