package com.mezze.interpreter.v2.nodes.function;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.mezze.interpreter.v2.values.MezzeFunction;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Dictionary-passed ability method call node.
 *
 * Reads the resolved ability impl function from a frame slot (populated by the caller
 * via dictionary passing) and calls it directly. No runtime type checking, no fallbacks,
 * no candidate scanning. The typechecker resolved the impl at compile time.
 *
 * This is a direct call through a dictionary parameter — the Haskell model.
 */
@NodeInfo(shortName = "dict-call")
public final class DictMethodCallNode extends MezzeNode {

    private final int dictSlot;

    @Child private MezzeNode argNode;
    @Child private DirectCallNode callNode;

    @CompilerDirectives.CompilationFinal
    private MezzeFunction cachedFunction;

    private DictMethodCallNode(int dictSlot, MezzeNode argNode) {
        this.dictSlot = dictSlot;
        this.argNode = argNode;
    }

    public static DictMethodCallNode create(int dictSlot, MezzeNode argNode) {
        return new DictMethodCallNode(dictSlot, argNode);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object dictValue = frame.getValue(dictSlot);

        if (!(dictValue instanceof MezzeFunction fn)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException(
                "Dictionary slot " + dictSlot + " does not contain a function: " +
                (dictValue == null ? "null" : dictValue.getClass().getSimpleName()));
        }

        // Monomorphic inline cache: if same function as last time, reuse DirectCallNode
        if (fn != cachedFunction) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            cachedFunction = fn;
            callNode = insert(DirectCallNode.create(fn.getCallTarget()));
        }

        Object arg = argNode.execute(frame);

        // Call with closure values (captures) prepended
        Object[] closureValues = fn.getClosureValues();
        if (closureValues.length == 0) {
            return callNode.call(arg);
        } else {
            Object[] args = new Object[closureValues.length + 1];
            System.arraycopy(closureValues, 0, args, 0, closureValues.length);
            args[closureValues.length] = arg;
            return callNode.call(args);
        }
    }
}
