package com.mezze.interpreter.v2.nodes.control;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Logical NOT node.
 *
 * Static method is used for specialization to enable
 * better inlining by the JIT compiler.
 */
@NodeChild("operand")
@NodeInfo(shortName = "not")
public abstract class NotNode extends MezzeNode {

    @Specialization
    protected static boolean not(boolean value) {
        return !value;
    }
}
