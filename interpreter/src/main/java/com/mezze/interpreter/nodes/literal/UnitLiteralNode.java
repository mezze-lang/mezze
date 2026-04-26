package com.mezze.interpreter.nodes.literal;

import com.mezze.interpreter.nodes.MezzeNode;
import com.mezze.interpreter.values.MezzeUnit;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.NodeInfo;

/**
 * Unit literal node ({}).
 */
@NodeInfo(shortName = "unit")
public final class UnitLiteralNode extends MezzeNode {

    @Override
    public Object execute(VirtualFrame frame) {
        return MezzeUnit.INSTANCE;
    }
}
