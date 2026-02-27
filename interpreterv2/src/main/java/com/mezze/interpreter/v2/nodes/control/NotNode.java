package com.mezze.interpreter.v2.nodes.control;

import com.mezze.interpreter.v2.nodes.MezzeNode;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Logical NOT node.
 */
@NodeChild("operand")
@NodeInfo(shortName = "not")
public abstract class NotNode extends MezzeNode {

    @Specialization
    protected boolean not(boolean value) {
        return !value;
    }
}
