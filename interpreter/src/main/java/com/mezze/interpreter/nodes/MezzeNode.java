package com.mezze.interpreter.nodes;

import com.mezze.interpreter.MezzeTypes;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Base class for all Mezze AST nodes.
 *
 * Uses Truffle DSL TypeSystemReference for automatic type coercion
 * and specialization support.
 */
@TypeSystemReference(MezzeTypes.class)
@NodeInfo(language = "mezze", description = "Base Mezze node")
public abstract class MezzeNode extends Node {

    /**
     * Execute this node and return the result.
     * This is the primary execution method.
     */
    public abstract Object execute(VirtualFrame frame);

    /**
     * Execute expecting a long result.
     * Throws UnexpectedResultException if result is not a long.
     */
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object result = execute(frame);
        if (result instanceof Long l) {
            return l;
        }
        throw new UnexpectedResultException(result);
    }

    /**
     * Execute expecting a double result.
     * Throws UnexpectedResultException if result is not a double.
     */
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        Object result = execute(frame);
        if (result instanceof Double d) {
            return d;
        }
        // Implicit cast from long to double
        if (result instanceof Long l) {
            return l.doubleValue();
        }
        throw new UnexpectedResultException(result);
    }

    /**
     * Execute expecting a boolean result.
     * Throws UnexpectedResultException if result is not a boolean.
     */
    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        Object result = execute(frame);
        if (result instanceof Boolean b) {
            return b;
        }
        throw new UnexpectedResultException(result);
    }

    /**
     * Get the Mezze language instance.
     */
    protected MezzeLanguage getLanguage() {
        return MezzeLanguage.get(this);
    }

    /**
     * Get the current Mezze context.
     */
    protected MezzeContext getContext() {
        return MezzeContext.get(this);
    }
}
